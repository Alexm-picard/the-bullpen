"""Unit tests for the rule-9 pitch-type LR baseline (Phase 2b, decision [183]).

Pure synthetic frames, no ClickHouse. Beyond the usual shape/determinism checks these pin
the two things that are genuinely different from the LightGBM head:

  * LR cannot consume NaN, so the median imputer is load-bearing for the NULL-bearing ARS /
    Tier-S columns (LightGBM handles them natively and would not catch a broken imputer);
  * sklearn emits one predict_proba column per class it SAW. If a training slice is missing
    a rare y7 class, the canonical remap must place that class at probability 0 rather than
    shifting every later column left - an off-by-one that would silently corrupt the [183]
    log-loss guardrail rather than crash.
"""

from __future__ import annotations

import numpy as np
import pandas as pd
import pytest

from bullpen_training.pitch_type import PITCH_TYPE_CLASSES, PITCH_TYPE_FEATURE_COLUMNS
from bullpen_training.pitch_type.train_lr import (
    LRModelBundle,
    model_factory,
    subsample_train_rows,
)

_ARS = ("ars_FF", "ars_SI", "ars_FC", "ars_SL", "ars_CU", "ars_CH", "ars_OFF", "ars_FF_by_count")
_NULLABLE_S = ("times_through_order", "at_bat_number_in_game", "times_faced_today")
_K = len(PITCH_TYPE_CLASSES)


def _frame(
    n: int = 900, seed: int = 0, nan_frac: float = 0.08, max_class: int | None = None
) -> pd.DataFrame:
    """Synthetic 24-feature frame + y7 label with a learnable ars_FF signal.

    ``max_class`` caps the labels present, so a caller can build a slice that is missing the
    rare tail classes (the sklearn column-ordering hazard above).
    """
    rng = np.random.default_rng(seed)
    df = pd.DataFrame()
    df["balls"] = rng.integers(0, 4, n)
    df["strikes"] = rng.integers(0, 3, n)
    df["outs"] = rng.integers(0, 3, n)
    df["inning"] = rng.integers(1, 10, n)
    df["base_state"] = rng.integers(0, 8, n)
    df["stand_i"] = rng.integers(0, 2, n)
    df["throws_i"] = rng.integers(0, 2, n)
    df["park_i"] = rng.integers(0, 3, n)
    for c in _NULLABLE_S:
        df[c] = rng.integers(0, 4, n).astype("float64")
    for c in _ARS:
        df[c] = rng.random(n)
    df["pitcher_prior_n"] = rng.integers(0, 500, n)
    df["prev1_pt_i"] = rng.integers(-1, 7, n)
    df["prev2_pt_i"] = rng.integers(-1, 7, n)
    df["prev1_missing"] = (df["prev1_pt_i"] == -1).astype("int64")
    df["pitches_into_outing"] = rng.integers(0, 100, n)

    if nan_frac > 0:  # cold-start / missing-V013 rows; LR must impute these
        mask = rng.random(n) < nan_frac
        for c in (*_ARS, *_NULLABLE_S):
            df.loc[mask, c] = np.nan

    cap = _K if max_class is None else max_class + 1
    df["label"] = (df["ars_FF"].fillna(0.5) * cap).astype("int64").clip(0, cap - 1)
    assert set(PITCH_TYPE_FEATURE_COLUMNS).issubset(df.columns)
    return df


def test_model_factory_returns_valid_proba_distribution() -> None:
    bundle = model_factory(_frame(seed=1), _frame(n=400, seed=2))
    proba = bundle.predict_proba(_frame(n=300, seed=3))
    assert isinstance(bundle, LRModelBundle)
    assert proba.shape == (300, _K)
    assert np.allclose(proba.sum(axis=1), 1.0, atol=1e-6)
    assert (proba >= 0).all() and (proba <= 1).all()
    assert np.isfinite(proba).all()


def test_nan_features_are_imputed_not_fatal() -> None:
    """LightGBM eats NaN natively; LR does not. The median imputer is what makes the
    cold-start (ars_* NULL) rows trainable at all, so a broken pipeline must fail HERE."""
    train = _frame(seed=10, nan_frac=0.25)
    val = _frame(n=400, seed=11, nan_frac=0.25)
    test = _frame(n=200, seed=12, nan_frac=0.25)
    assert bool(test[list(_ARS)].isna().to_numpy().any()), "fixture produced no NaN"
    proba = model_factory(train, val).predict_proba(test)
    assert np.isfinite(proba).all()
    assert np.allclose(proba.sum(axis=1), 1.0, atol=1e-6)


def test_calibrator_is_temperature_and_order_preserving() -> None:
    """[183] guardrail integrity: the baseline calibrates with TEMPERATURE, matching the
    primary head, and temperature never reorders classes."""
    bundle = model_factory(_frame(seed=20), _frame(n=400, seed=21))
    test = _frame(n=300, seed=22)
    assert bundle.calibrator.temperature > 0
    assert list(bundle.calibrator.class_labels) == list(PITCH_TYPE_CLASSES)

    raw_sklearn = bundle.pipeline.predict_proba(test[list(PITCH_TYPE_FEATURE_COLUMNS)].to_numpy())
    canonical = np.zeros((len(test), _K))
    for j, c in enumerate(bundle.fitted_label_classes):
        canonical[:, c] = raw_sklearn[:, j]
    calibrated = bundle.predict_proba(test)
    assert np.array_equal(canonical.argmax(axis=1), calibrated.argmax(axis=1))


def test_missing_class_maps_to_zero_column_not_a_shifted_one() -> None:
    """A slice missing the rare tail classes must still yield 7 columns, with the absent
    classes at 0 - NOT every later class shifted left. A shift would not crash; it would
    quietly mis-attribute probability mass and corrupt the guardrail comparison."""
    train = _frame(seed=30, max_class=3)  # only classes 0..3 present
    val = _frame(n=400, seed=31, max_class=3)
    bundle = model_factory(train, val)
    assert bundle.fitted_label_classes == (0, 1, 2, 3)

    proba = bundle.predict_proba(_frame(n=120, seed=32))
    assert proba.shape == (120, _K), "canonical width must stay 7 even when classes are absent"
    # Temperature renormalises over all 7 columns, so an unseen class cannot be exactly 0
    # after calibration - assert it is negligible, and that the seen classes carry the mass.
    unseen_mass = float(proba[:, 4:].sum(axis=1).max())
    assert unseen_mass < 1e-6, f"absent classes should hold ~no mass, got {unseen_mass}"
    assert np.allclose(proba.sum(axis=1), 1.0, atol=1e-6)


def test_missing_MIDDLE_class_does_not_shift_later_columns() -> None:
    """The stronger form of the test above. A missing TAIL class cannot distinguish a correct
    remap from a naive passthrough (both leave the tail near zero); a missing MIDDLE class
    can, because a left-shift would move class 5/6 mass into columns 4/5. Constructed by
    hand rather than by training, so the vocabulary gap is exact."""
    from bullpen_training.pitch_type.train_lr import _to_canonical_columns

    raw = np.array([[0.1, 0.2, 0.3, 0.4]])
    out = _to_canonical_columns(raw, (0, 2, 5, 6))
    assert out.tolist() == [[0.1, 0.0, 0.2, 0.0, 0.0, 0.3, 0.4]]
    assert out.sum() == pytest.approx(1.0)


def test_canonical_remap_rejects_an_out_of_vocab_class() -> None:
    """numpy would WRAP a negative index (class -1 -> column 6) and mis-attribute that mass
    silently. Unreachable today, but it must fail loud rather than corrupt the guardrail."""
    from bullpen_training.pitch_type.train_lr import _to_canonical_columns

    with pytest.raises(ValueError, match="outside the y7 vocabulary"):
        _to_canonical_columns(np.array([[0.5, 0.5]]), (-1, 0))


def test_seed_reproducibility() -> None:
    train, val, test = _frame(seed=40), _frame(n=400, seed=41), _frame(n=200, seed=42)
    b1, b2 = model_factory(train, val), model_factory(train, val)
    assert np.allclose(b1.predict_proba(test), b2.predict_proba(test), atol=1e-9)
    assert b1.calibrator.temperature == pytest.approx(b2.calibrator.temperature, abs=1e-9)


def test_subsample_is_a_noop_below_the_cap() -> None:
    """The CV folds must NOT be subsampled - only the oversized production train window is."""
    df = _frame(n=500, seed=50)
    assert subsample_train_rows(df) is df


def test_subsample_caps_and_stays_within_the_window() -> None:
    df = _frame(n=1_000, seed=51)
    out = subsample_train_rows(df, max_rows=100)
    assert len(out) == 100
    # A row thin, not a re-split: every retained row came from the original frame.
    assert set(out.index).issubset(set(df.index))
