"""Export the pitch-type LR baseline (model.pkl) to model.onnx for registry serving.

REQUIRED before Phase 3, not optional. The registry serves every model through ONNX
Runtime - ``pitch.register_snapshot`` hardcodes ``ARTIFACT_FILE = "model.onnx"`` - and the
[182]/#334 first-champion gate binds its champion to a REGISTERED version of
``pitch_type_lr_baseline``. A bundle carrying only ``model.pkl`` cannot be registered, so
the box run would stall at registration.

The pipeline is ``Pipeline(SimpleImputer(median), StandardScaler, LogisticRegression)`` fit
on the SAME 24-feature representation the LightGBM head consumes raw. The Java feature
pipeline for this contract is encode-only (passthrough / categorical_map /
categorical_lookup - no imputation, no standardisation), so the imputer AND the scaler must
live INSIDE the ONNX graph. Java sends NaN for the cold-start ARS and missing V013 columns;
the converted Imputer op is what absorbs them, which is why the parity check below feeds
NaN rows deliberately rather than only dense ones.

CLASS ORDER: this export REFUSES a pipeline whose ``classes_`` is not the canonical
0..6. The y7 remap that ``train_lr._to_canonical_columns`` performs in Python has no
representation here, and the Java consumer reads the probability tensor positionally - so a
model fit on a slice missing a rare class must be rejected at export rather than silently
serve shifted columns. Mirrors ``pitch.export_lr_onnx``.

Leaves calibrator.json untouched (the temperature calibrator is applied downstream by the
Java consumer), but DOES re-stamp ``metadata.json``'s ``model_artifact`` to the emitted
model.onnx. persist wrote ``model.pkl`` there, which is not the artifact the registry stores
or serves. The pitch family self-corrects because ``pitch.register_snapshot`` re-stamps at
assembly time; pitch-type has NO assembly step, so nothing else would ever fix it - and this
is the registry row the [182] first-champion gate binds against, so its audit trail should
name the file that is actually registered.

  uv run python -m bullpen_training.pitch_type.export_lr_onnx --version v1
"""

from __future__ import annotations

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

from bullpen_training.pitch_type import PITCH_TYPE_CLASSES, PITCH_TYPE_FEATURE_COLUMNS
from bullpen_training.pitch_type.contract import (
    assert_feature_order_matches,
    load_canonical_pipeline,
)

DEFAULT_ARTIFACTS_DIR = Path(__file__).resolve().parents[3] / "artifacts"
DEFAULT_MODEL_NAME = "pitch_type_lr_baseline"
# A linear model's float32 graph stays well inside this against float64 sklearn (the pitch
# LR baseline uses the same bar). Tighter than the LightGBM tree bar for good reason: there
# is no tree-split accumulation here, so a larger diff means a real conversion defect.
PARITY_ATOL = 1e-5
N_PARITY_ROWS = 256

# Column positions the serving path can send as NaN: the three Nullable Tier-S columns and
# the eight career-expanding ARS columns (NULL at a pitcher's first career pitch). Derived
# from the canonical order so a contract reshuffle cannot silently point this at the wrong
# columns and leave the Imputer path untested.
_NULLABLE_FEATURES = (
    "times_through_order",
    "at_bat_number_in_game",
    "times_faced_today",
    "ars_FF",
    "ars_SI",
    "ars_FC",
    "ars_SL",
    "ars_CU",
    "ars_CH",
    "ars_OFF",
    "ars_FF_by_count",
)


def nullable_column_indices() -> tuple[int, ...]:
    return tuple(PITCH_TYPE_FEATURE_COLUMNS.index(c) for c in _NULLABLE_FEATURES)


def assert_canonical_class_order(pipeline: Any) -> None:
    """Refuse to export a pipeline whose fitted class order is not 0..6.

    ``train_lr`` remaps sklearn's ``classes_``-ordered columns into the canonical y7 layout
    in Python. That remap cannot cross into the ONNX graph, and the Java consumer reads the
    probability tensor POSITIONALLY - so a model fit on a slice missing a rare class would
    serve shifted columns with no error anywhere. Fail here instead.
    """
    classes = [int(c) for c in getattr(pipeline, "classes_", pipeline[-1].classes_)]
    expected = list(range(len(PITCH_TYPE_CLASSES)))
    if classes != expected:
        raise RuntimeError(
            f"fitted class order {classes} is not canonical {expected}; the serving path "
            "reads the probability tensor positionally and the y7 remap cannot be expressed "
            "in the ONNX graph - refuse to export rather than serve shifted columns."
        )


def convert_pipeline_to_onnx(pipeline: Any, *, n_features: int, opset: int) -> onnx.ModelProto:
    """skl2onnx conversion with the imputer + scaler baked into the graph (zipmap=False)."""
    model = cast(
        onnx.ModelProto,
        convert_sklearn(
            pipeline,
            initial_types=[("input", FloatTensorType([None, n_features]))],
            target_opset=opset,
            options={id(pipeline): {"zipmap": False}},
        ),
    )
    onnx.checker.check_model(model)
    return model


def parity_rows_with_nans(x: np.ndarray) -> np.ndarray:
    """Append a NaN-bearing block so the converted Imputer path is proven, not just the
    dense path. Cold-start rows are a real production input, not a hypothetical."""
    if len(x) == 0:
        return x
    nan_block = x[: min(32, len(x))].copy()
    nan_block[:, list(nullable_column_indices())] = np.nan
    return np.vstack([x, nan_block])


def onnx_vs_sklearn_max_diff(pipeline: Any, model: onnx.ModelProto, x: np.ndarray) -> float:
    """max|ORT probs - pipeline.predict_proba|. ORT's probability tensor is read as the LAST
    output, matching how the Java consumer reads it positionally."""
    import onnxruntime as ort

    x32 = np.asarray(x, dtype=np.float32)
    sess = ort.InferenceSession(model.SerializeToString())
    got = np.asarray(sess.run(None, {sess.get_inputs()[0].name: x32})[-1], dtype=np.float64)
    want = np.asarray(pipeline.predict_proba(x32.astype(np.float64)), dtype=np.float64)
    return float(np.max(np.abs(got - want)))


def _restamp_metadata_artifact(artifact_dir: Path, onnx_path: Path, onnx_sha: str) -> None:
    """Point metadata.json's ``model_artifact`` at the ONNX the registry actually stores.

    persist stamps ``model.pkl`` there, which is the training artifact, not the registered
    one. Keeps the sklearn artifact recorded separately rather than dropping it - both are
    real, and the bundle's provenance should say which is which.
    """
    meta_path = artifact_dir / "metadata.json"
    if not meta_path.exists():
        return
    meta = cast(dict[str, Any], json.loads(meta_path.read_text()))
    previous = meta.get("model_artifact")
    if previous and previous.get("path") != onnx_path.name:
        meta["sklearn_artifact"] = previous
    meta["model_artifact"] = {"path": onnx_path.name, "sha256": onnx_sha}
    meta_path.write_text(json.dumps(meta, indent=2, sort_keys=False) + "\n")


def export(
    *,
    model_name: str = DEFAULT_MODEL_NAME,
    version: str = "v1",
    artifacts_dir: Path | None = None,
) -> dict[str, Any]:
    base = artifacts_dir or DEFAULT_ARTIFACTS_DIR
    artifact_dir = base / model_name / version
    pipeline = joblib.load(artifact_dir / "model.pkl")
    assert_canonical_class_order(pipeline)

    spec = load_canonical_pipeline()  # rule 7: recomputed, fail-loud on drift
    # Both pitch-type models share ONE contract, so this check must be symmetric with the
    # LightGBM export: without it a drift would fail loud on pitch_type_pre while the
    # baseline silently served permuted features, corrupting the [183] guardrail number.
    assert_feature_order_matches(spec)
    opset = int(spec["onnx_opset"])
    feature_cols = list(PITCH_TYPE_FEATURE_COLUMNS)

    model = convert_pipeline_to_onnx(pipeline, n_features=len(feature_cols), opset=opset)

    df = cast("pd.DataFrame", pd.read_parquet(artifact_dir / "training_data.parquet"))
    x = parity_rows_with_nans(df[feature_cols].head(N_PARITY_ROWS).to_numpy(dtype=np.float32))
    max_diff = onnx_vs_sklearn_max_diff(pipeline, model, x)
    if max_diff > PARITY_ATOL:
        raise RuntimeError(f"LR parity max|diff|={max_diff:.2e} > {PARITY_ATOL}")

    onnx_path = artifact_dir / "model.onnx"
    onnx.save(model, str(onnx_path))
    onnx_sha = hashlib.sha256(onnx_path.read_bytes()).hexdigest()
    _restamp_metadata_artifact(artifact_dir, onnx_path, onnx_sha)
    click.echo(
        f"wrote {onnx_path} ({onnx_path.stat().st_size // 1024} KB, sha {onnx_sha[:12]})\n"
        f"  parity vs predict_proba (incl NaN rows): max|diff|={max_diff:.2e}\n"
        f"  schema_hash: {spec['schema_hash']}  opset: {opset}"
    )
    return {
        "onnx_path": str(onnx_path),
        "onnx_sha256": onnx_sha,
        "parity_max_diff": max_diff,
        "schema_hash": spec["schema_hash"],
    }


@click.command()
@click.option("--model-name", default=DEFAULT_MODEL_NAME, show_default=True)
@click.option("--version", default="v1", show_default=True)
@click.option(
    "--artifacts-dir",
    type=click.Path(file_okay=False, dir_okay=True, path_type=Path),
    default=None,
)
def main(model_name: str, version: str, artifacts_dir: Path | None) -> None:
    export(model_name=model_name, version=version, artifacts_dir=artifacts_dir)


if __name__ == "__main__":
    main()
