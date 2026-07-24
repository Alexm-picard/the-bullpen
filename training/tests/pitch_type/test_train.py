"""Unit tests for the pitch-type model factory (Phase 2a, decision [183]).

Pure synthetic mini-dataset (no ClickHouse): assert training completes, predictions
are valid 7-class probability rows, the temperature calibrator attaches, and - the
load-bearing [183] property end-to-end through the bundle - temperature is
ORDER-PRESERVING (calibrated argmax == raw argmax), so calibration never turns the
prior into a re-ranked top-1 predictor. The live full-data CV runs on the box.
"""

from __future__ import annotations

import numpy as np
import pandas as pd
import pytest

from bullpen_training.pitch_type import PITCH_TYPE_CLASSES, PITCH_TYPE_FEATURE_COLUMNS
from bullpen_training.pitch_type.train import ModelBundle, model_factory

_ARS_COLS = (
    "ars_FF",
    "ars_SI",
    "ars_FC",
    "ars_SL",
    "ars_CU",
    "ars_CH",
    "ars_OFF",
    "ars_FF_by_count",
)


def _synthetic_frame(n: int = 2_500, seed: int = 7, nan_frac: float = 0.0) -> pd.DataFrame:
    """Synthetic 24-feature frame + a 7-class label with a learnable signal: high ars_FF
    biases toward FF (class 0), high strikes nudges off-speed. Optionally injects NaN into
    the arsenal columns (cold-start rows) to exercise LightGBM's native NaN handling."""
    rng = np.random.default_rng(seed)
    df = pd.DataFrame()
    df["balls"] = rng.integers(0, 4, n).astype("int8")
    df["strikes"] = rng.integers(0, 3, n).astype("int8")
    df["outs"] = rng.integers(0, 3, n).astype("int8")
    df["inning"] = rng.integers(1, 10, n).astype("int8")
    df["base_state"] = rng.integers(0, 8, n).astype("int8")
    df["stand_i"] = rng.integers(0, 2, n).astype("int8")
    df["throws_i"] = rng.integers(0, 2, n).astype("int8")
    df["park_i"] = rng.integers(0, 30, n).astype("int16")
    df["times_through_order"] = rng.integers(1, 4, n).astype("float32")
    df["at_bat_number_in_game"] = rng.integers(1, 40, n).astype("float32")
    df["times_faced_today"] = rng.integers(0, 4, n).astype("float32")
    for c in _ARS_COLS:
        df[c] = rng.random(n).astype("float32")
    df["pitcher_prior_n"] = rng.integers(0, 500, n).astype("int32")
    df["prev1_pt_i"] = rng.integers(-1, 7, n).astype("int8")
    df["prev2_pt_i"] = rng.integers(-1, 7, n).astype("int8")
    df["prev1_missing"] = (df["prev1_pt_i"] == -1).astype("int8")
    df["pitches_into_outing"] = rng.integers(0, 100, n).astype("int16")

    if nan_frac > 0:
        mask = rng.random(n) < nan_frac
        for c in _ARS_COLS:
            df.loc[mask, c] = np.nan

    # Signal: ars_FF -> FF (0); strikes -> a breaking/off-speed bucket.
    ars_ff = df["ars_FF"].fillna(0.5).to_numpy()
    strikes = df["strikes"].to_numpy()
    base = np.zeros((n, len(PITCH_TYPE_CLASSES)))
    base[:, 0] = 3.0 * ars_ff  # FF
    base[:, 3] = 0.8 * strikes  # SL
    base[:, 5] = 0.5 * strikes  # CH
    probs = np.exp(base) / np.exp(base).sum(axis=1, keepdims=True)
    df["label"] = np.array([rng.choice(len(PITCH_TYPE_CLASSES), p=probs[i]) for i in range(n)])
    df["label"] = df["label"].astype("int8")
    # Every feature column the factory reads must be present.
    assert set(PITCH_TYPE_FEATURE_COLUMNS).issubset(df.columns)
    return df


def test_model_factory_returns_valid_proba_distribution() -> None:
    train = _synthetic_frame(n=2_000, seed=1)
    val = _synthetic_frame(n=600, seed=2)
    test = _synthetic_frame(n=600, seed=3)

    bundle = model_factory(train, val, num_boost_round=60, early_stopping_rounds=10)
    proba = bundle.predict_proba(test)
    assert proba.shape == (len(test), len(PITCH_TYPE_CLASSES))
    assert np.allclose(proba.sum(axis=1), 1.0, atol=1e-6)
    assert (proba >= 0).all() and (proba <= 1).all()


def test_model_factory_temperature_is_order_preserving_end_to_end() -> None:
    """The [183] honest-framing guarantee, verified through the whole bundle: the
    temperature calibrator may not reorder the raw booster's per-row class ranking."""
    train = _synthetic_frame(n=2_000, seed=10)
    val = _synthetic_frame(n=600, seed=11)
    test = _synthetic_frame(n=600, seed=12)

    bundle = model_factory(train, val, num_boost_round=100, early_stopping_rounds=20)
    assert isinstance(bundle, ModelBundle)
    assert bundle.calibrator is not None
    assert bundle.calibrator.temperature > 0.0
    raw = np.asarray(bundle.booster.predict(test[list(PITCH_TYPE_FEATURE_COLUMNS)]))
    calibrated = bundle.predict_proba(test)
    assert calibrated.shape == raw.shape
    assert np.array_equal(raw.argmax(axis=1), calibrated.argmax(axis=1))
    assert np.array_equal(raw.argsort(axis=1), calibrated.argsort(axis=1))


def test_model_factory_seed_reproducibility() -> None:
    train = _synthetic_frame(n=1_000, seed=20)
    val = _synthetic_frame(n=400, seed=21)
    test = _synthetic_frame(n=300, seed=22)

    b1 = model_factory(train, val, num_boost_round=40, early_stopping_rounds=8)
    b2 = model_factory(train, val, num_boost_round=40, early_stopping_rounds=8)
    assert np.allclose(b1.predict_proba(test), b2.predict_proba(test), atol=1e-9)
    assert b1.calibrator.temperature == pytest.approx(b2.calibrator.temperature, abs=1e-9)


def test_model_factory_handles_nan_arsenal() -> None:
    """ars_* NULL at cold start (materialised as NaN) must train + predict finite probs."""
    train = _synthetic_frame(n=2_000, seed=30, nan_frac=0.1)
    val = _synthetic_frame(n=600, seed=31, nan_frac=0.1)
    test = _synthetic_frame(n=300, seed=32, nan_frac=0.1)
    bundle = model_factory(train, val, num_boost_round=50, early_stopping_rounds=10)
    proba = bundle.predict_proba(test)
    assert np.isfinite(proba).all()
    assert np.allclose(proba.sum(axis=1), 1.0, atol=1e-6)


@pytest.mark.parametrize("split_size", [1, 25, 200])
def test_predict_proba_handles_various_batch_sizes(split_size: int) -> None:
    train = _synthetic_frame(n=1_000, seed=40)
    val = _synthetic_frame(n=400, seed=41)
    test = _synthetic_frame(n=split_size, seed=42)
    bundle = model_factory(train, val, num_boost_round=30, early_stopping_rounds=5)
    proba = bundle.predict_proba(test)
    assert proba.shape == (split_size, len(PITCH_TYPE_CLASSES))
