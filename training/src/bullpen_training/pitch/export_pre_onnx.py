"""Convert the production pitch_outcome_pre LightGBM booster to ONNX (Phase 2a.8).

Boundary discipline (Risk Register G1 → closed end-to-end):
    Numeric features + integer-encoded categoricals (pitcher_throws_int,
    batter_stand_int, park_id_int) pass through ONNX unchanged. The
    string→int mappings + TE lookups live alongside the model
    (`park_id_mapping.json`, `pitcher_te.json`, `batter_te.json`) so the
    Java FeaturePipelinePitchPre reproduces the Python feature vector
    bit-for-bit.

Source of truth: `/contracts/feature_pipeline.json` (CLAUDE.md rule 7).
This export READS the canonical pipeline + validates the model matches it
(same algorithm as the toy exporter).

Outputs alongside `model.lgb`:

    model.onnx          — ONNX-format booster (5-class multinomial)

Determinism: re-running on the same model.lgb is byte-stable for the
ONNX bytes. (Cross-machine bit-identical is NOT guaranteed for the
.lgb file itself — see CLAUDE.md note.)
"""

from __future__ import annotations

import copy
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
import pandas as pd
from onnxmltools.convert.common.data_types import FloatTensorType  # type: ignore[import-untyped]

from bullpen_training.logging_config import configure_logging, get_logger
from bullpen_training.pitch import PITCH_FEATURE_COLUMNS

log = get_logger(__name__)

PARITY_ATOL = 1e-4  # LightGBM: float32 ONNX trees vs float64 booster sums; looser than the LR check
N_PARITY_ROWS = 256

REPO_ROOT = Path(__file__).resolve().parents[4]
CONTRACT_PATH = REPO_ROOT / "contracts" / "feature_pipeline.json"
DEFAULT_ARTIFACTS_DIR = REPO_ROOT / "training" / "artifacts"
DEFAULT_MODEL_NAME = "pitch_outcome_pre"
DEFAULT_VERSION = "v1"


def load_canonical_pipeline() -> dict[str, Any]:
    """Read /contracts/feature_pipeline.json and verify the declared
    schema_hash matches the hook's algorithm. Drift here is a hard fail.
    """
    spec = cast(dict[str, Any], json.loads(CONTRACT_PATH.read_text()))
    declared = spec["schema_hash"]
    canonical = copy.deepcopy(spec)
    canonical["schema_hash"] = ""
    recomputed = hashlib.sha256(
        json.dumps(canonical, sort_keys=True, separators=(",", ":")).encode()
    ).hexdigest()
    if declared != recomputed:
        raise RuntimeError(
            f"/contracts/feature_pipeline.json schema_hash is stale "
            f"(declared={declared} computed={recomputed}); "
            "re-run the recompute snippet in .githooks/pre-commit and re-stage."
        )
    return spec


def _validate_model_matches_contract(spec: dict[str, Any]) -> None:
    if spec["model_name"] != DEFAULT_MODEL_NAME:
        raise RuntimeError(
            f"contract model_name is {spec['model_name']!r}; "
            f"export_pre_onnx only handles {DEFAULT_MODEL_NAME!r} "
            "(if the production model changed names, update DEFAULT_MODEL_NAME)"
        )
    if tuple(spec["feature_order"]) != PITCH_FEATURE_COLUMNS:
        raise RuntimeError(
            "contract feature_order != PITCH_FEATURE_COLUMNS — Python code + "
            "contract drifted; reconcile before exporting"
        )


def _parity_check(
    booster: lgb.Booster, model: onnx.ModelProto, artifact_dir: Path, feature_cols: list[str]
) -> float:
    """Raw ONNX (ORT, last output) vs the LightGBM booster on real held-out rows.

    Calibration is applied downstream in Java for both paths, so raw-vs-raw is the
    correct comparison. A diff over PARITY_ATOL is the finding E2 exists to catch -
    investigate the export, do not loosen the tolerance to pass.
    """
    import onnxruntime as ort

    df = pd.read_parquet(artifact_dir / "training_data.parquet")
    x = df[feature_cols].head(N_PARITY_ROWS).to_numpy(dtype=np.float32)
    sess = ort.InferenceSession(model.SerializeToString())
    got = np.asarray(sess.run(None, {sess.get_inputs()[0].name: x})[-1], dtype=np.float64)
    want = np.asarray(booster.predict(x.astype(np.float64)), dtype=np.float64)
    max_diff = float(np.max(np.abs(got - want)))
    if max_diff > PARITY_ATOL:
        raise RuntimeError(f"ONNX vs booster parity max|diff|={max_diff:.2e} > {PARITY_ATOL}")
    return max_diff


def export(
    *,
    model_dir: Path | None = None,
    model_name: str = DEFAULT_MODEL_NAME,
    version: str = DEFAULT_VERSION,
    artifacts_dir: Path | None = None,
) -> dict[str, Any]:
    spec = load_canonical_pipeline()
    _validate_model_matches_contract(spec)
    opset = int(spec["onnx_opset"])

    if model_dir is None:
        base = artifacts_dir or DEFAULT_ARTIFACTS_DIR
        model_dir = base / model_name / version

    model_path = model_dir / "model.lgb"
    if not model_path.exists():
        raise FileNotFoundError(
            f"LightGBM model not found at {model_path}; run "
            "`uv run python -m bullpen_training.pitch.production "
            "--model lightgbm --version v1` first"
        )

    booster = lgb.Booster(model_file=str(model_path))
    n_features = len(PITCH_FEATURE_COLUMNS)
    initial_types = [("input", FloatTensorType([None, n_features]))]
    log.info(
        "converting multiclass LightGBM → ONNX",
        n_features=n_features,
        n_classes=len(spec["output"]["labels"]),
        opset=opset,
    )
    # zipmap=False keeps the probability tensor as a dense Nx5 float matrix
    # (instead of a list of dicts) — that's what the Java side wants.
    onnx_model = onnxmltools.convert.convert_lightgbm(
        booster,
        initial_types=initial_types,
        target_opset=opset,
        zipmap=False,
    )
    onnx.checker.check_model(onnx_model)
    parity = _parity_check(booster, onnx_model, model_dir, list(PITCH_FEATURE_COLUMNS))
    log.info("ONNX value-parity vs booster", max_abs_diff=parity, atol=PARITY_ATOL)
    onnx_path = model_dir / "model.onnx"
    onnxmltools.utils.save_model(onnx_model, str(onnx_path))

    onnx_sha = hashlib.sha256(onnx_path.read_bytes()).hexdigest()
    log.info(
        "export complete",
        onnx_path=str(onnx_path),
        onnx_sha256=onnx_sha,
        schema_hash=spec["schema_hash"],
        n_classes=len(spec["output"]["labels"]),
    )
    return {
        "onnx_path": str(onnx_path),
        "onnx_sha256": onnx_sha,
        "schema_hash": spec["schema_hash"],
        "n_features": n_features,
        "n_classes": len(spec["output"]["labels"]),
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
