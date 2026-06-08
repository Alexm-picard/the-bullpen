"""Unit tests for the multinomial LR baseline (Phase 2a.6)."""

from __future__ import annotations

import numpy as np
import pandas as pd

from bullpen_training.features import LABEL_CLASSES
from bullpen_training.pitch import PITCH_FEATURE_COLUMNS
from bullpen_training.pitch.train_lr_baseline import model_factory, subsample_train_rows


def _synthetic_frame(n: int = 1500, seed: int = 7) -> pd.DataFrame:
    """Same shape as the LightGBM test fixture — keeps the two model
    factories comparable on equivalent data."""
    rng = np.random.default_rng(seed)
    df = pd.DataFrame({col: rng.normal(size=n).astype("float32") for col in PITCH_FEATURE_COLUMNS})
    df["count_balls"] = rng.integers(0, 4, n).astype("int8")
    df["count_strikes"] = rng.integers(0, 3, n).astype("int8")
    df["outs"] = rng.integers(0, 3, n).astype("int8")
    df["inning"] = rng.integers(1, 10, n).astype("int8")
    df["base_state"] = rng.integers(0, 8, n).astype("int8")
    df["dow"] = rng.integers(0, 7, n).astype("int8")
    df["pitcher_throws_int"] = rng.integers(0, 2, n).astype("int8")
    df["batter_stand_int"] = rng.integers(0, 2, n).astype("int8")
    df["park_id_int"] = rng.integers(0, 30, n).astype("int16")
    df["pitcher_pitches_in_game"] = rng.integers(0, 100, n).astype("int32")

    score = 2.0 * df["pitcher_te_in_play"] + 1.0 * df["count_strikes"] - 0.5 * df["count_balls"]
    logits = np.stack(
        [
            -score,
            -score * 0.5,
            -score * 0.5,
            -score * 0.5,
            2.0 * df["pitcher_te_in_play"],
        ],
        axis=1,
    )
    probs = np.exp(logits) / np.exp(logits).sum(axis=1, keepdims=True)
    df["label"] = np.array(
        [rng.choice(len(LABEL_CLASSES), p=probs[i]) for i in range(n)], dtype="int8"
    )
    return df


def test_subsample_caps_rows_and_is_deterministic() -> None:
    """Path 1: the LR production-fit row subsample caps row count, is deterministic for
    a fixed seed, and is a no-op (same object) when the frame is already within the cap.
    """
    df = _synthetic_frame(n=500, seed=1)

    capped = subsample_train_rows(df, max_rows=100, seed=7)
    assert len(capped) == 100
    # Same seed -> same rows (deterministic; the index carries the sampled positions).
    assert capped.index.equals(subsample_train_rows(df, max_rows=100, seed=7).index)
    # Different seed -> (overwhelmingly likely) different rows.
    assert not capped.index.equals(subsample_train_rows(df, max_rows=100, seed=8).index)
    # No-op when already within the cap: returns the SAME object, untouched.
    assert subsample_train_rows(df, max_rows=1000) is df
    # The thin preserves columns (incl. label + as-of context), only fewer rows.
    assert list(capped.columns) == list(df.columns)


def test_lr_returns_valid_proba_distribution() -> None:
    train = _synthetic_frame(n=1000, seed=1)
    val = _synthetic_frame(n=300, seed=2)
    test = _synthetic_frame(n=300, seed=3)

    bundle = model_factory(train, val)
    proba = bundle.predict_proba(test)
    assert proba.shape == (len(test), len(LABEL_CLASSES))
    sums = proba.sum(axis=1)
    assert np.allclose(sums, 1.0, atol=1e-4)
    assert (proba >= 0).all() and (proba <= 1).all()


def test_lr_beats_uniform_baseline_on_separable_data() -> None:
    """A 5-class LR on a fixture with real signal should beat random
    guessing. Use accuracy as the simple sanity metric."""
    train = _synthetic_frame(n=2000, seed=4)
    test = _synthetic_frame(n=500, seed=5)
    bundle = model_factory(train, train)  # tiny val, fine for a sanity test
    proba = bundle.predict_proba(test)
    accuracy = float((proba.argmax(axis=1) == np.asarray(test["label"])).mean())
    assert accuracy > 1.0 / len(LABEL_CLASSES) + 0.05, (
        f"LR accuracy {accuracy:.3f} barely beats random {1 / len(LABEL_CLASSES):.3f}"
    )


def test_lr_calibrator_attaches_and_changes_probabilities() -> None:
    train = _synthetic_frame(n=1500, seed=10)
    val = _synthetic_frame(n=500, seed=11)
    test = _synthetic_frame(n=300, seed=12)

    bundle = model_factory(train, val)
    cal_proba = bundle.predict_proba(test)
    # .to_numpy() matches the nameless-array fit (fit_lr_from_arrays); a named frame
    # here would trip sklearn's feature-name-mismatch warning.
    raw_proba = bundle.pipeline.predict_proba(test[list(PITCH_FEATURE_COLUMNS)].to_numpy())

    # Reorder raw to canonical layout for fair compare
    canonical = np.zeros_like(cal_proba)
    for j, cls_int in enumerate(bundle.fitted_label_classes):
        canonical[:, cls_int] = raw_proba[:, j]

    assert np.abs(cal_proba - canonical).max() > 1e-4, "calibrator was a no-op"


def test_lr_seed_reproducibility() -> None:
    train = _synthetic_frame(n=800, seed=20)
    val = _synthetic_frame(n=300, seed=21)
    test = _synthetic_frame(n=300, seed=22)

    b1 = model_factory(train, val)
    b2 = model_factory(train, val)
    p1 = b1.predict_proba(test)
    p2 = b2.predict_proba(test)
    assert np.allclose(p1, p2, atol=1e-9), "identical-seed runs diverged"
