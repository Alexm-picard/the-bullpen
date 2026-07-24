"""Convert the production pitch_type_pre LightGBM booster to ONNX (Phase 2a, decision [183]).

The 24 features (numeric + integer-encoded categoricals stand_i/throws_i/park_i) pass
through ONNX unchanged; the string->int mappings live alongside the model
(park_id_mapping.json) so the Java side reproduces the feature vector bit-for-bit. The
single-scalar TEMPERATURE calibrator (calibrator.json) is applied post-ONNX by the Java
serving consumer (report section 4), exactly as the pitch-outcome heads apply isotonic.

Source of truth: /contracts/feature_pipeline_pitchtype.json (CLAUDE.md rule 7). This
export READS the canonical pipeline + validates the model matches it.

Output alongside model.lgb: model.onnx (7-class multinomial, probabilities tensor at
onnx output index 1, zipmap=False -> a dense Nx7 float matrix, not a list of dicts).

The conversion + parity primitives (`convert_booster_to_onnx`, `onnx_raw_probabilities`,
`parity_max_diff`) take objects so the parity gate can run on a miniature model in CI
without a box-trained artifact; `export()` is the file-based box/Mac wrapper.
"""

from __future__ import annotations

import hashlib
import json
import logging
import os
from pathlib import Path
from typing import Any, cast

import click
import lightgbm as lgb
import numpy as np
import onnx
import onnxmltools
from onnxmltools.convert.common.data_types import FloatTensorType  # type: ignore[import-untyped]

from bullpen_training.logging_config import configure_logging, get_logger
from bullpen_training.pitch_type import PITCH_TYPE_FEATURE_COLUMNS
from bullpen_training.registry_client import feature_hasher

log = get_logger(__name__)

# LightGBM's ONNX trees sum in float32 while the booster sums in float64, so the parity
# is the LightGBM-export tolerance (matches pitch.export_pre_onnx), NOT the tighter LR
# 1e-7. Report section 4's 2e-07 was an optimistic single-measurement figure; a diff over
# this bar is a real export bug - investigate, do not loosen to pass.
PARITY_ATOL = 1e-4
N_PARITY_ROWS = 256

REPO_ROOT = Path(__file__).resolve().parents[4]
CONTRACT_PATH = REPO_ROOT / "contracts" / "feature_pipeline_pitchtype.json"
DEFAULT_ARTIFACTS_DIR = REPO_ROOT / "training" / "artifacts"
DEFAULT_MODEL_NAME = "pitch_type_pre"
DEFAULT_VERSION = "v1"


def load_canonical_pipeline() -> dict[str, Any]:
    """Read the pitch-type contract and verify its declared schema_hash. Drift is a hard fail."""
    spec = cast(dict[str, Any], json.loads(CONTRACT_PATH.read_text()))
    declared = spec["schema_hash"]
    recomputed = feature_hasher.compute(CONTRACT_PATH)
    if declared != recomputed:
        raise RuntimeError(
            f"{CONTRACT_PATH.name} schema_hash is stale (declared={declared} "
            f"computed={recomputed}); re-run the recompute snippet in .githooks/pre-commit."
        )
    return spec


def _validate_model_matches_contract(spec: dict[str, Any]) -> None:
    if spec["model_name"] != DEFAULT_MODEL_NAME:
        raise RuntimeError(
            f"contract model_name is {spec['model_name']!r}; export_onnx only handles "
            f"{DEFAULT_MODEL_NAME!r}"
        )
    if tuple(spec["feature_order"]) != PITCH_TYPE_FEATURE_COLUMNS:
        raise RuntimeError(
            "contract feature_order != PITCH_TYPE_FEATURE_COLUMNS - code + contract drifted; "
            "reconcile before exporting"
        )


def convert_booster_to_onnx(
    booster: lgb.Booster, *, n_features: int, opset: int
) -> onnx.ModelProto:
    """Convert a LightGBM multiclass booster to ONNX (zipmap=False, checked)."""
    initial_types = [("input", FloatTensorType([None, n_features]))]
    onnx_model = onnxmltools.convert.convert_lightgbm(
        booster,
        initial_types=initial_types,
        target_opset=opset,
        zipmap=False,
    )
    onnx.checker.check_model(onnx_model)
    return onnx_model


def onnx_raw_probabilities(onnx_model: onnx.ModelProto, x: np.ndarray) -> np.ndarray:
    """Run ORT and return the probability tensor (the last output; contract index 1)."""
    import onnxruntime as ort

    sess = ort.InferenceSession(onnx_model.SerializeToString())
    out = sess.run(None, {sess.get_inputs()[0].name: x.astype(np.float32)})
    return np.asarray(out[-1], dtype=np.float64)


def parity_max_diff(booster: lgb.Booster, onnx_model: onnx.ModelProto, x: np.ndarray) -> float:
    """max|ONNX raw probs - booster.predict| over x. Calibration is applied downstream for
    both paths, so raw-vs-raw is the correct comparison.

    Narrows x to float32 FIRST so both paths see identical float32-representable values: ORT
    runs float32 while the booster widens to float64, so a stray float64 x (with sub-float32
    precision) would otherwise inflate the diff purely from the truncation, not a real defect.
    """
    x32 = np.asarray(x, dtype=np.float32)
    got = onnx_raw_probabilities(onnx_model, x32)
    want = np.asarray(booster.predict(x32.astype(np.float64)), dtype=np.float64)
    return float(np.max(np.abs(got - want)))


def export(
    *,
    model_dir: Path | None = None,
    model_name: str = DEFAULT_MODEL_NAME,
    version: str = DEFAULT_VERSION,
    artifacts_dir: Path | None = None,
) -> dict[str, Any]:
    """File-based export (box/Mac): read model.lgb, convert, parity-check against the
    committed training_data.parquet, write model.onnx."""
    import pandas as pd

    spec = load_canonical_pipeline()
    _validate_model_matches_contract(spec)
    opset = int(spec["onnx_opset"])
    n_features = len(PITCH_TYPE_FEATURE_COLUMNS)

    if model_dir is None:
        base = artifacts_dir or DEFAULT_ARTIFACTS_DIR
        model_dir = base / model_name / version
    model_path = model_dir / "model.lgb"
    if not model_path.exists():
        raise FileNotFoundError(
            f"LightGBM model not found at {model_path}; run the pitch_type production "
            "orchestrator first."
        )

    booster = lgb.Booster(model_file=str(model_path))
    log.info(
        "converting multiclass LightGBM -> ONNX",
        n_features=n_features,
        n_classes=len(spec["output"]["labels"]),
        opset=opset,
    )
    onnx_model = convert_booster_to_onnx(booster, n_features=n_features, opset=opset)

    df = cast("pd.DataFrame", pd.read_parquet(model_dir / "training_data.parquet"))
    x = df[list(PITCH_TYPE_FEATURE_COLUMNS)].head(N_PARITY_ROWS).to_numpy(dtype=np.float32)
    parity = parity_max_diff(booster, onnx_model, x)
    if parity > PARITY_ATOL:
        raise RuntimeError(f"ONNX vs booster parity max|diff|={parity:.2e} > {PARITY_ATOL}")
    log.info("ONNX value-parity vs booster", max_abs_diff=parity, atol=PARITY_ATOL)

    onnx_path = model_dir / "model.onnx"
    onnxmltools.utils.save_model(onnx_model, str(onnx_path))
    onnx_sha = hashlib.sha256(onnx_path.read_bytes()).hexdigest()
    log.info("export complete", onnx_path=str(onnx_path), onnx_sha256=onnx_sha)
    return {
        "onnx_path": str(onnx_path),
        "onnx_sha256": onnx_sha,
        "schema_hash": spec["schema_hash"],
        "n_features": n_features,
        "n_classes": len(spec["output"]["labels"]),
        "parity_max_abs_diff": parity,
    }


@click.command()
@click.option("--model-name", type=str, default=DEFAULT_MODEL_NAME, show_default=True)
@click.option("--version", type=str, default=DEFAULT_VERSION, show_default=True)
@click.option(
    "--artifacts-dir",
    type=click.Path(file_okay=False, dir_okay=True, path_type=Path),
    default=None,
)
@click.option(
    "--log-format",
    type=click.Choice(["console", "json"], case_sensitive=False),
    default="console",
)
def main(model_name: str, version: str, artifacts_dir: Path | None, log_format: str) -> None:
    if log_format.lower() == "json":
        os.environ["LOG_FORMAT"] = "json"
    configure_logging(level=logging.INFO)
    export(model_name=model_name, version=version, artifacts_dir=artifacts_dir)


if __name__ == "__main__":
    main()
