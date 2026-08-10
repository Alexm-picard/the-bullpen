"""Self-contained ONNX-export gate for the pitch-type LR baseline (Phase 2b, decision [183]).

Trains a miniature LR pipeline in-process (NO box artifact), converts it, and asserts the
graph is faithful. Two things carry real weight here:

  * the imputer + scaler live INSIDE the graph, because the Java feature pipeline for this
    contract is encode-only. The parity check therefore feeds NaN rows deliberately - a
    conversion that silently dropped the Imputer would still pass a dense-only check, and
    cold-start ARS rows are a real production input;
  * the class-order guard must BITE. `train_lr` remaps sklearn's `classes_`-ordered columns
    in Python, that remap cannot cross into ONNX, and the Java consumer reads the tensor
    positionally - so a model fit on a slice missing a class must be REFUSED at export
    rather than silently serve shifted columns.
"""

from __future__ import annotations

import json
from typing import ClassVar

import numpy as np
import pandas as pd
import pytest

from bullpen_training.pitch_type import PITCH_TYPE_CLASSES, PITCH_TYPE_FEATURE_COLUMNS
from bullpen_training.pitch_type.export_lr_onnx import (
    PARITY_ATOL,
    assert_canonical_class_order,
    convert_pipeline_to_onnx,
    nullable_column_indices,
    onnx_vs_sklearn_max_diff,
    parity_rows_with_nans,
)
from bullpen_training.pitch_type.train_lr import model_factory

_ARS = ("ars_FF", "ars_SI", "ars_FC", "ars_SL", "ars_CU", "ars_CH", "ars_OFF", "ars_FF_by_count")
_NULLABLE_S = ("times_through_order", "at_bat_number_in_game", "times_faced_today")
_OPSET = 15
_N = len(PITCH_TYPE_FEATURE_COLUMNS)
_K = len(PITCH_TYPE_CLASSES)


def _frame(n: int = 900, seed: int = 0, max_class: int | None = None) -> pd.DataFrame:
    rng = np.random.default_rng(seed)
    df = pd.DataFrame()
    for c in PITCH_TYPE_FEATURE_COLUMNS:
        df[c] = rng.random(n)
    mask = rng.random(n) < 0.08
    for c in (*_ARS, *_NULLABLE_S):
        df.loc[mask, c] = np.nan
    cap = _K if max_class is None else max_class + 1
    df["label"] = (df["ars_FF"].fillna(0.5) * cap).astype("int64").clip(0, cap - 1)
    return df


def _x(n: int, seed: int) -> np.ndarray:
    return _frame(n=n, seed=seed)[list(PITCH_TYPE_FEATURE_COLUMNS)].to_numpy(dtype=np.float32)


@pytest.fixture(scope="module")
def bundle():  # - LRModelBundle, inferred
    return model_factory(_frame(seed=1), _frame(n=400, seed=2))


def test_nullable_indices_are_derived_from_the_contract_order() -> None:
    """Hardcoding positions would silently point the NaN parity block at the wrong columns
    after any contract reshuffle, leaving the Imputer path untested while still passing."""
    idx = nullable_column_indices()
    assert len(idx) == 11
    for i in idx:
        assert PITCH_TYPE_FEATURE_COLUMNS[i] in (*_ARS, *_NULLABLE_S)
    # The non-null columns must NOT be in the block.
    assert PITCH_TYPE_FEATURE_COLUMNS.index("balls") not in idx
    assert PITCH_TYPE_FEATURE_COLUMNS.index("pitcher_prior_n") not in idx


def test_onnx_matches_sklearn_including_nan_rows(bundle) -> None:
    model = convert_pipeline_to_onnx(bundle.pipeline, n_features=_N, opset=_OPSET)
    x = parity_rows_with_nans(_x(256, 3))
    assert bool(np.isnan(x).any()), "parity block must actually carry NaN"
    diff = onnx_vs_sklearn_max_diff(bundle.pipeline, model, x)
    assert diff <= PARITY_ATOL, f"LR ONNX parity {diff:.2e} > {PARITY_ATOL}"


def test_onnx_output_is_a_valid_nx7_distribution(bundle) -> None:
    import onnxruntime as ort

    model = convert_pipeline_to_onnx(bundle.pipeline, n_features=_N, opset=_OPSET)
    sess = ort.InferenceSession(model.SerializeToString())
    # Python reads the LAST output; Java reads INDEX 1 (PitchOnnxModel). Those coincide only
    # at exactly 2 outputs, and nothing else asserts the arity - so assert it here.
    assert len(sess.get_outputs()) == 2
    probs = np.asarray(sess.run(None, {sess.get_inputs()[0].name: _x(32, 4)})[-1])
    assert probs.shape == (32, _K)
    assert np.allclose(probs.sum(axis=1), 1.0, atol=1e-5)
    assert (probs >= 0).all() and (probs <= 1).all()


def test_full_serving_path_reconstructs_the_bundle(bundle) -> None:
    """ONNX raw + the persisted temperature calibrator == the Python bundle's predict_proba,
    which is exactly what the Java consumer will do (graph, then post-ONNX temperature)."""
    model = convert_pipeline_to_onnx(bundle.pipeline, n_features=_N, opset=_OPSET)
    import onnxruntime as ort

    test = _frame(n=128, seed=5)
    x = test[list(PITCH_TYPE_FEATURE_COLUMNS)].to_numpy(dtype=np.float32)
    sess = ort.InferenceSession(model.SerializeToString())
    onnx_raw = np.asarray(sess.run(None, {sess.get_inputs()[0].name: x})[-1], dtype=np.float64)
    # Valid only because this fixture fits all 7 classes - which the export guard enforces.
    assert bundle.fitted_label_classes == tuple(range(_K))
    reconstructed = bundle.calibrator.transform(onnx_raw)
    assert np.allclose(reconstructed, bundle.predict_proba(test), atol=PARITY_ATOL)


def test_canonical_class_order_guard_accepts_a_full_vocabulary(bundle) -> None:
    assert_canonical_class_order(bundle.pipeline)  # must not raise


def test_canonical_class_order_guard_BITES_on_a_missing_class() -> None:
    """The gate that stops a shifted-column model from ever reaching the registry. Without
    it the export would succeed and Java would read the wrong class positions silently."""
    partial = model_factory(_frame(seed=30, max_class=3), _frame(n=400, seed=31, max_class=3))
    assert partial.fitted_label_classes == (0, 1, 2, 3)
    with pytest.raises(RuntimeError, match="not canonical"):
        assert_canonical_class_order(partial.pipeline)


def test_export_end_to_end_from_a_persisted_bundle(tmp_path: pytest.TempPathFactory) -> None:
    """The actual box command: persist a bundle, then run the file-based export against it.

    This is the step whose absence would have stalled registration (the registry serves every
    model through ONNX Runtime), so prove the whole chain - persist writes model.pkl +
    training_data.parquet, export reads them and emits a parity-checked model.onnx.
    """
    from pathlib import Path

    from bullpen_training.pitch_type.export_lr_onnx import export
    from bullpen_training.pitch_type.production import train_and_persist_lr

    out_root = Path(str(tmp_path))

    class _Loader:
        park_id_mapping: ClassVar[dict[str, int]] = {"PARK00": 0}

        def __call__(self, start_year: int, end_year: int, fold_id: int) -> pd.DataFrame:
            return _frame(n=900, seed=start_year)

    bundle_dir = train_and_persist_lr(_Loader(), version="v1", artifacts_dir=out_root, skip_cv=True)
    assert (bundle_dir / "model.pkl").exists()
    assert not (bundle_dir / "model.onnx").exists(), "persist must not write ONNX itself"

    result = export(version="v1", artifacts_dir=out_root)

    assert (bundle_dir / "model.onnx").exists(), "registration needs this file to exist"
    assert result["parity_max_diff"] <= PARITY_ATOL
    assert result["onnx_sha256"]

    # metadata must name the artifact the REGISTRY stores, not the training pickle. persist
    # writes model.pkl there and pitch-type has no assembly step to correct it.
    meta = json.loads((bundle_dir / "metadata.json").read_text())
    assert meta["model_artifact"]["path"] == "model.onnx"
    assert meta["model_artifact"]["sha256"] == result["onnx_sha256"]
    assert meta["sklearn_artifact"]["path"] == "model.pkl"
