"""Self-contained ONNX-export parity gate for pitch_type_pre (Phase 2a, decision [183]).

Trains a miniature LightGBM y7 booster in-process (NO box artifact), converts it to ONNX,
and asserts:
  * ONNX raw probabilities match booster.predict within a tight tolerance (~1.9e-7 actual),
  * the model exposes exactly 2 outputs so the contract's onnx_output_index=1 IS the Nx7
    probability tensor,
  * the FULL serving path reconstructs the Python bundle: temperature.transform(ONNX raw)
    == bundle.predict_proba, so the Java (ONNX + post-ONNX temperature) path is faithful.

Runs in CI (lightgbm + onnx + onnxruntime are all deps); needs no committed artifact.
"""

from __future__ import annotations

import numpy as np
import pandas as pd

from bullpen_training.pitch_type import PITCH_TYPE_CLASSES, PITCH_TYPE_FEATURE_COLUMNS
from bullpen_training.pitch_type.export_onnx import (
    convert_booster_to_onnx,
    onnx_raw_probabilities,
    parity_max_diff,
)
from bullpen_training.pitch_type.train import ModelBundle, model_factory

_OPSET = 15  # contract onnx_opset
_N_FEATURES = len(PITCH_TYPE_FEATURE_COLUMNS)
# The miniature booster's measured ONNX<->booster parity is ~1.9e-7 (matching report
# section 4's 2e-07). 1e-5 is a TIGHT regression gate here (~50x margin) - deliberately
# tighter than export_onnx.PARITY_ATOL (1e-4), which is the box-side bound that must also
# tolerate the full 2000-tree model's deeper float32 accumulation.
_MINI_ATOL = 1e-5


def _frame(n: int = 1_500, seed: int = 0) -> pd.DataFrame:
    """Random 24-feature frame; label correlates with ars_FF so the booster grows real
    trees (parity is signal-independent, but a non-trivial ensemble is a better target)."""
    rng = np.random.default_rng(seed)
    df = pd.DataFrame({c: rng.random(n).astype("float32") for c in PITCH_TYPE_FEATURE_COLUMNS})
    k = len(PITCH_TYPE_CLASSES)
    df["label"] = (df["ars_FF"] * k).astype("int8").clip(0, k - 1)
    return df


def _mini_bundle() -> ModelBundle:
    return model_factory(
        _frame(seed=1), _frame(n=600, seed=2), num_boost_round=60, early_stopping_rounds=10
    )


def _x(n: int, seed: int) -> np.ndarray:
    return _frame(n=n, seed=seed)[list(PITCH_TYPE_FEATURE_COLUMNS)].to_numpy(dtype=np.float32)


def test_onnx_raw_matches_booster_within_tolerance() -> None:
    bundle = _mini_bundle()
    onnx_model = convert_booster_to_onnx(bundle.booster, n_features=_N_FEATURES, opset=_OPSET)
    diff = parity_max_diff(bundle.booster, onnx_model, _x(256, 3))
    assert diff <= _MINI_ATOL, f"ONNX vs booster parity {diff:.2e} > {_MINI_ATOL}"


def test_onnx_parity_survives_nan_features() -> None:
    """ars_*/seq NaN (a pitcher's first career pitch / outing start) is a DECLARED production
    input state and the historically-fragile part of LightGBM->ONNX. ORT and the booster must
    route NaN to the same default direction; a toolchain regression must fail CI here, not only
    the box-only export()."""
    bundle = _mini_bundle()
    onnx_model = convert_booster_to_onnx(bundle.booster, n_features=_N_FEATURES, opset=_OPSET)
    x = _x(64, 6).copy()  # to_numpy() can be read-only; copy so the NaN injection is writable
    nan_cols = [
        PITCH_TYPE_FEATURE_COLUMNS.index(c)
        for c in (
            "ars_FF",
            "ars_SI",
            "ars_FC",
            "ars_SL",
            "ars_CU",
            "ars_CH",
            "ars_OFF",
            "ars_FF_by_count",
            "prev1_pt_i",
            "prev2_pt_i",
            "pitcher_prior_n",
        )
    ]
    x[:8, nan_cols] = np.nan  # cold-start / outing-start rows
    diff = parity_max_diff(bundle.booster, onnx_model, x)
    assert diff <= _MINI_ATOL, f"NaN-row ONNX vs booster parity {diff:.2e} > {_MINI_ATOL}"


def test_onnx_output_is_nx7_probability_tensor() -> None:
    bundle = _mini_bundle()
    onnx_model = convert_booster_to_onnx(bundle.booster, n_features=_N_FEATURES, opset=_OPSET)
    # Exactly [label, probabilities] -> the contract's onnx_output_index=1 is the prob tensor.
    assert len(onnx_model.graph.output) == 2
    probs = onnx_raw_probabilities(onnx_model, _x(32, 4))
    assert probs.shape == (32, len(PITCH_TYPE_CLASSES))
    assert np.allclose(probs.sum(axis=1), 1.0, atol=1e-5)
    assert (probs >= 0).all() and (probs <= 1).all()


def test_full_serving_path_reconstructs_bundle() -> None:
    """ONNX raw + the Python temperature calibrator == the bundle's predict_proba: the Java
    (ONNX booster + post-ONNX temperature) serving path is faithful to the Python model."""
    bundle = _mini_bundle()
    onnx_model = convert_booster_to_onnx(bundle.booster, n_features=_N_FEATURES, opset=_OPSET)
    test = _frame(n=128, seed=5)
    onnx_raw = onnx_raw_probabilities(onnx_model, test[list(PITCH_TYPE_FEATURE_COLUMNS)].to_numpy())
    reconstructed = bundle.calibrator.transform(onnx_raw)
    expected = bundle.predict_proba(test)
    assert np.allclose(reconstructed, expected, atol=_MINI_ATOL)
