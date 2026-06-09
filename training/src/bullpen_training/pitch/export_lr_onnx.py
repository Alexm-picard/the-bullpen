"""Export the pitch LR baseline (model.pkl) to model.onnx for registry serving.

The registry serves every model through ONNX Runtime (the batted-ball LR baseline
set the precedent: skl2onnx, scaler baked into the graph). The pitch LR pipeline is
Pipeline(SimpleImputer(median), StandardScaler, LogisticRegression) fit on the SAME
31-feature representation the LightGBM pre head consumes raw - the Java
FeaturePipelinePitchPre is encode-only (passthrough / categorical_map / TE lookup,
no standardization), so the imputer + scaler must live INSIDE the ONNX graph.
Java sends NaN for missing Tier-3 form values; the converted Imputer op handles them.

Leaves calibrator.json and metadata.json untouched: the pitch isotonic calibrator is
applied downstream in LoadedPitchModel (decision [38]), and register_snapshot writes
its own metadata at assembly time.

  uv run python -m bullpen_training.pitch.export_lr_onnx \\
      --model-name pitch_outcome_lr_baseline --version v1
"""

from __future__ import annotations

import copy
import hashlib
import json
from pathlib import Path
from typing import Any, cast

import click
import joblib
import numpy as np
import onnx
import pandas as pd
from skl2onnx import convert_sklearn
from skl2onnx.common.data_types import FloatTensorType

from bullpen_training.features import LABEL_CLASSES
from bullpen_training.pitch.register_snapshot import contract_path_for, feature_columns_for

DEFAULT_ARTIFACTS_DIR = Path(__file__).resolve().parents[3] / "artifacts"
TARGET_OPSET = 15  # the pitch contract's onnx_opset pin (feature_pipeline.json)
PARITY_ATOL = 1e-5  # linear model: float32 graph vs float64 sklearn stays well inside this
N_PARITY_ROWS = 256


def _verify_contract_hash(contract_path: Path) -> str:
    spec = cast(dict[str, Any], json.loads(contract_path.read_text()))
    canonical = copy.deepcopy(spec)
    canonical["schema_hash"] = ""
    recomputed = hashlib.sha256(
        json.dumps(canonical, sort_keys=True, separators=(",", ":")).encode()
    ).hexdigest()
    if spec["schema_hash"] != recomputed:
        raise RuntimeError(f"{contract_path.name} schema_hash stale")
    return cast(str, spec["schema_hash"])


def _parity_rows(artifact_dir: Path, feature_cols: list[str]) -> np.ndarray:
    """Real held-out rows from the training snapshot, plus synthetic NaN rows so the
    converted Imputer path is proven equivalent, not just the dense path."""
    df = pd.read_parquet(artifact_dir / "training_data.parquet")
    x = df[feature_cols].head(N_PARITY_ROWS).to_numpy(dtype=np.float32)
    nan_block = x[: min(32, len(x))].copy()
    nan_block[:, -11:] = np.nan  # Tier-3 form tail: the columns serving sends as NaN
    return np.vstack([x, nan_block])


def export(*, model_name: str, version: str, artifacts_dir: Path | None = None) -> dict[str, Any]:
    base = artifacts_dir or DEFAULT_ARTIFACTS_DIR
    artifact_dir = base / model_name / version
    pipeline = joblib.load(artifact_dir / "model.pkl")

    classes = list(getattr(pipeline, "classes_", pipeline[-1].classes_))
    if classes != list(range(len(LABEL_CLASSES))):
        raise RuntimeError(
            f"fitted class order {classes} is not canonical 0..{len(LABEL_CLASSES) - 1}; "
            "the Java calibrator assumes canonical order - refuse to export"
        )

    feature_cols = list(feature_columns_for(model_name))
    schema_hash = _verify_contract_hash(contract_path_for(model_name))

    model = cast(
        onnx.ModelProto,
        convert_sklearn(
            pipeline,
            initial_types=[("input", FloatTensorType([None, len(feature_cols)]))],
            target_opset=TARGET_OPSET,
            options={id(pipeline): {"zipmap": False}},
        ),
    )
    onnx.checker.check_model(model)

    # Value parity: ORT (read positionally like PitchOnnxModel: probs = last output)
    import onnxruntime as ort

    x = _parity_rows(artifact_dir, feature_cols)
    sess = ort.InferenceSession(model.SerializeToString())
    got = np.asarray(sess.run(None, {sess.get_inputs()[0].name: x})[-1], dtype=np.float64)
    want = np.asarray(pipeline.predict_proba(x.astype(np.float64)), dtype=np.float64)
    max_diff = float(np.max(np.abs(got - want)))
    if max_diff > PARITY_ATOL:
        raise RuntimeError(f"LR parity max|diff|={max_diff:.2e} > {PARITY_ATOL}")

    onnx_path = artifact_dir / "model.onnx"
    onnx.save(model, str(onnx_path))
    onnx_sha = hashlib.sha256(onnx_path.read_bytes()).hexdigest()
    click.echo(
        f"wrote {onnx_path} ({onnx_path.stat().st_size // 1024} KB, sha {onnx_sha[:12]})\n"
        f"  parity vs predict_proba (incl NaN rows): max|diff|={max_diff:.2e}\n"
        f"  schema_hash: {schema_hash}  opset: {TARGET_OPSET}"
    )
    return {"onnx_path": str(onnx_path), "onnx_sha256": onnx_sha, "parity_max_diff": max_diff}


@click.command()
@click.option("--model-name", default="pitch_outcome_lr_baseline", show_default=True)
@click.option("--version", default="v1", show_default=True)
def main(model_name: str, version: str) -> None:
    export(model_name=model_name, version=version)


if __name__ == "__main__":
    main()
