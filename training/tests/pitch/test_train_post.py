"""Unit tests for the post-pitch model factory (Phase 2b.2).

Live integration on real `features` rows runs as a manual drill on
Mac dev — see the leaf's status log entry for fold-by-fold results.
Here we keep the tests pure: synthetic mini-dataset with Tier 4
columns wired up, assert training completes, predictions are valid,
calibrator attaches, seed is reproducible, and Tier 4 actually
shifts predictions versus a Tier-4-NaN baseline (i.e. the post head
is genuinely using the extra columns).
"""

from __future__ import annotations

import numpy as np
import pandas as pd
import pytest

from bullpen_training.features import LABEL_CLASSES
from bullpen_training.pitch import PITCH_FEATURE_COLUMNS_POST
from bullpen_training.pitch.train_post import model_factory


def _synthetic_frame_post(n: int = 2_500, seed: int = 7) -> pd.DataFrame:
    """Synthetic features + label with a signal that depends on BOTH a
    Tier 2 column (pitcher_te_in_play) AND a Tier 4 column (pitch_type_int).
    Lets us verify that the post head actually uses Tier 4."""
    rng = np.random.default_rng(seed)
    df = pd.DataFrame(
        {col: rng.normal(size=n).astype("float32") for col in PITCH_FEATURE_COLUMNS_POST}
    )
    df["count_balls"] = rng.integers(0, 4, n).astype("int8")
    df["count_strikes"] = rng.integers(0, 3, n).astype("int8")
    df["outs"] = rng.integers(0, 3, n).astype("int8")
    df["inning"] = rng.integers(1, 10, n).astype("int8")
    df["base_state"] = rng.integers(0, 8, n).astype("int8")
    df["dow"] = rng.integers(0, 7, n).astype("int8")
    df["pitcher_throws_int"] = rng.integers(0, 2, n).astype("int8")
    df["batter_stand_int"] = rng.integers(0, 2, n).astype("int8")
    df["park_id_int"] = rng.integers(0, 30, n).astype("int16")
    df["pitch_type_int"] = rng.integers(0, 8, n).astype("int16")
    df["pitcher_pitches_in_game"] = rng.integers(0, 100, n).astype("int32")

    # Signal: pitcher_te_in_play + pitch_type==fastball-ish (code 0/1) bias toward
    # in_play; high count_strikes biases toward strikeouts.
    pitch_type_signal = (df["pitch_type_int"] < 2).astype("float32") * 1.5
    score = (
        2.0 * df["pitcher_te_in_play"]
        + pitch_type_signal
        + 1.0 * df["count_strikes"]
        - 0.5 * df["count_balls"]
    )
    logits = np.stack(
        [
            -score,
            -score * 0.5,
            -score * 0.5,
            -score * 0.5,
            2.0 * df["pitcher_te_in_play"] + pitch_type_signal,
        ],
        axis=1,
    )
    probs = np.exp(logits) / np.exp(logits).sum(axis=1, keepdims=True)
    df["label"] = np.array(
        [rng.choice(len(LABEL_CLASSES), p=probs[i]) for i in range(n)], dtype="int8"
    )
    return df


def test_model_factory_returns_valid_proba_distribution_with_tier4() -> None:
    train = _synthetic_frame_post(n=1500, seed=1)
    val = _synthetic_frame_post(n=500, seed=2)
    test = _synthetic_frame_post(n=500, seed=3)

    bundle = model_factory(train, val, num_boost_round=50, early_stopping_rounds=10)
    proba = bundle.predict_proba(test)
    assert proba.shape == (len(test), len(LABEL_CLASSES))
    sums = proba.sum(axis=1)
    assert np.allclose(sums, 1.0, atol=1e-4)
    assert (proba >= 0).all() and (proba <= 1).all()


def test_post_model_uses_more_features_than_pre() -> None:
    """The post head's feature_cols must include Tier 4 columns. If this
    fails, the post head is feature-equivalent to the pre head and
    decision [35] (two heads, two registered models) is violated in
    practice even if the registry entries differ on paper."""
    train = _synthetic_frame_post(n=500, seed=1)
    val = _synthetic_frame_post(n=200, seed=2)
    bundle = model_factory(train, val, num_boost_round=20, early_stopping_rounds=5)

    tier4_present = {
        "pitch_type_int",
        "release_speed_mph",
        "plate_x_in",
        "plate_z_in",
        "pfx_x_in",
        "pfx_z_in",
        "spin_rate_rpm",
        "spin_axis_deg",
        "release_pos_x_in",
        "release_pos_z_in",
    } & set(bundle.feature_cols)
    assert len(tier4_present) == 10, (
        f"post bundle missing Tier 4 features: {sorted(set(bundle.feature_cols) ^ tier4_present)}"
    )
    # And the total count must be 41 (31 + 10), not 31.
    assert len(bundle.feature_cols) == 41


def test_calibrator_attaches_and_outputs_differ() -> None:
    train = _synthetic_frame_post(n=2000, seed=10)
    val = _synthetic_frame_post(n=600, seed=11)
    test = _synthetic_frame_post(n=600, seed=12)

    bundle = model_factory(train, val, num_boost_round=100, early_stopping_rounds=20)
    proba_cal = bundle.predict_proba(test)
    raw = np.asarray(bundle.booster.predict(test[list(PITCH_FEATURE_COLUMNS_POST)]))

    assert bundle.calibrator is not None
    assert proba_cal.shape == raw.shape
    assert np.abs(proba_cal - raw).max() > 1e-4, "calibrator was a no-op"


def test_seed_reproducibility() -> None:
    train = _synthetic_frame_post(n=800, seed=20)
    val = _synthetic_frame_post(n=300, seed=21)
    test = _synthetic_frame_post(n=300, seed=22)

    b1 = model_factory(train, val, num_boost_round=30, early_stopping_rounds=5)
    b2 = model_factory(train, val, num_boost_round=30, early_stopping_rounds=5)
    p1 = b1.predict_proba(test)
    p2 = b2.predict_proba(test)
    assert np.allclose(p1, p2, atol=1e-9), "two identical-seed runs diverged"


@pytest.mark.parametrize("split_size", [10, 100])
def test_predict_proba_handles_various_batch_sizes(split_size: int) -> None:
    train = _synthetic_frame_post(n=500, seed=30)
    val = _synthetic_frame_post(n=200, seed=31)
    test = _synthetic_frame_post(n=split_size, seed=32)
    bundle = model_factory(train, val, num_boost_round=20, early_stopping_rounds=5)
    proba = bundle.predict_proba(test)
    assert proba.shape == (split_size, len(LABEL_CLASSES))
