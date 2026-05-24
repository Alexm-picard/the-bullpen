"""Convert the production pitch_outcome_post LightGBM booster to ONNX (Phase 2b.3).

Sister script to ``export_pre_onnx.py``. Reads ``/contracts/feature_pipeline_post.json``
for the canonical 41-feature column order + schema_hash, validates the model
matches the contract, then runs ``onnxmltools.convert_lightgbm`` with
``zipmap=False`` so the Java side gets a dense Nx5 probability matrix.

Boundary discipline (Risk Register G1):
    Numeric features + integer-encoded categoricals (pitcher_throws_int,
    batter_stand_int, park_id_int, pitch_type_int) pass through ONNX
    unchanged. The string→int mappings + TE lookups live alongside the
    model (``park_id_mapping.json``, ``pitch_type_mapping.json``,
    ``pitcher_te.json``, ``batter_te.json``) so the Java
    ``FeaturePipelinePitchPost`` reproduces the Python feature vector
    bit-for-bit.

Outputs alongside ``model.lgb``:

    model.onnx          — ONNX-format booster (5-class multinomial,
                          41-feature input)
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
import onnx
import onnxmltools
from onnxmltools.convert.common.data_types import FloatTensorType  # type: ignore[import-untyped]

from bullpen_training.logging_config import configure_logging, get_logger
from bullpen_training.pitch import PITCH_FEATURE_COLUMNS_POST

log = get_logger(__name__)

REPO_ROOT = Path(__file__).resolve().parents[4]
CONTRACT_PATH = REPO_ROOT / "contracts" / "feature_pipeline_post.json"
DEFAULT_ARTIFACTS_DIR = REPO_ROOT / "training" / "artifacts"
DEFAULT_MODEL_NAME = "pitch_outcome_post"
DEFAULT_VERSION = "v1"


def load_canonical_pipeline() -> dict[str, Any]:
    """Read /contracts/feature_pipeline_post.json and verify schema_hash.

    Drift between the file content and the declared hash is a hard fail —
    matches the pre-commit hook's algorithm exactly.
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
            f"/contracts/feature_pipeline_post.json schema_hash is stale "
            f"(declared={declared} computed={recomputed}); "
            "re-run the recompute snippet in .githooks/pre-commit and re-stage."
        )
    return spec


def _validate_model_matches_contract(spec: dict[str, Any]) -> None:
    if spec["model_name"] != DEFAULT_MODEL_NAME:
        raise RuntimeError(
            f"contract model_name is {spec['model_name']!r}; "
            f"export_post_onnx only handles {DEFAULT_MODEL_NAME!r} "
            "(if the post head was renamed, update DEFAULT_MODEL_NAME)"
        )
    if tuple(spec["feature_order"]) != PITCH_FEATURE_COLUMNS_POST:
        raise RuntimeError(
            "contract feature_order != PITCH_FEATURE_COLUMNS_POST — Python code "
            "+ contract drifted; reconcile before exporting"
        )


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
            "--model post --version v1` first"
        )

    booster = lgb.Booster(model_file=str(model_path))
    n_features = len(PITCH_FEATURE_COLUMNS_POST)
    initial_types = [("input", FloatTensorType([None, n_features]))]
    log.info(
        "converting multiclass LightGBM → ONNX",
        n_features=n_features,
        n_classes=len(spec["output"]["labels"]),
        opset=opset,
    )
    onnx_model = onnxmltools.convert.convert_lightgbm(
        booster,
        initial_types=initial_types,
        target_opset=opset,
        zipmap=False,
    )
    onnx.checker.check_model(onnx_model)
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
