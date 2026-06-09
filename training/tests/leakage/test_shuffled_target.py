"""Leakage test #2 — shuffled target (Phase 2a.3, CLAUDE.md rule 10).

If we shuffle the label column, every entity's class distribution should
collapse to the global prior (Bayesian smoothing then nudges any
remaining noise back). The model would have nothing to learn.

If, instead, the TE retains signal after shuffling, something is leaking
the original target through an unintended channel.
"""

from __future__ import annotations

from datetime import date
from typing import cast

import numpy as np
import pandas as pd
import pytest

from bullpen_training.eval.metrics import multiclass_brier
from bullpen_training.features import LABEL_CLASSES
from bullpen_training.features.target_encoding import compute_prior, compute_te
from bullpen_training.pitch.train_pre import model_factory
from tests.leakage.conftest import (
    SyntheticFold,
    assemble_pitch_features,
    synthetic_pitches,
    to_model_frame,
)

SHUFFLE_SEED = 7919


def _mean_abs_dev_from_prior(te_df: pd.DataFrame, prior: dict[str, float]) -> float:
    """Average across entities of mean |te_c - prior_c| over all classes."""
    devs: list[float] = []
    for cls in LABEL_CLASSES:
        devs.append(float((te_df[f"te_{cls}"] - prior[cls]).abs().mean()))
    return float(np.mean(devs))


def test_signal_present_before_shuffle(pitches: pd.DataFrame) -> None:
    """Sanity that the fixture has real per-pitcher signal — without this
    the shuffled-target test would pass trivially."""
    te = compute_te(pitches, entity_col="pitcher_id", label_col="label")
    prior = compute_prior(pitches, "label")
    dev = _mean_abs_dev_from_prior(te, prior)
    assert dev > 0.10, (
        f"fixture has no per-pitcher signal (mean |te - prior| = {dev:.4f}); "
        "the shuffled-target test would be vacuous against this data"
    )


def test_shuffled_labels_collapse_te_to_prior(pitches: pd.DataFrame) -> None:
    shuffled = pitches.copy()
    rng = np.random.default_rng(SHUFFLE_SEED)
    shuffled["label"] = rng.permutation(np.asarray(shuffled["label"]))

    te_shuffled = compute_te(shuffled, entity_col="pitcher_id", label_col="label")
    prior = compute_prior(shuffled, "label")

    dev = _mean_abs_dev_from_prior(te_shuffled, prior)
    # With ~480 pitches per pitcher and k=20, sampling noise is bounded.
    # 0.02 is comfortably above noise but well below the 0.10+ signal we saw
    # in test_signal_present_before_shuffle.
    assert dev < 0.02, (
        f"after target shuffle, mean |te - prior| = {dev:.4f} — TE still "
        "carries entity-specific signal. Either the shuffle is broken or "
        "compute_te is reading the unshuffled label through a side channel."
    )


def test_shuffle_does_not_change_prior(pitches: pd.DataFrame) -> None:
    """Sanity: permuting a column preserves the marginal distribution."""
    rng = np.random.default_rng(SHUFFLE_SEED)
    shuffled = pitches.copy()
    shuffled["label"] = rng.permutation(np.asarray(shuffled["label"]))
    p_before = compute_prior(pitches, "label")
    p_after = compute_prior(shuffled, "label")
    for cls in LABEL_CLASSES:
        assert p_before[cls] == pytest.approx(p_after[cls], abs=1e-9)


# ---------------------------------------------------------------------------
# Shuffled-target END TO END through the real LightGBM pitch head
# ---------------------------------------------------------------------------
#
# The tests above probe the TE in isolation. The strongest form of the
# shuffled-target check trains the ACTUAL model (train_pre.model_factory, the
# same LightGBM multinomial + isotonic the production pre head uses) on a
# fixture whose labels were shuffled BEFORE feature assembly - so every channel
# (TE, rolling form, the label itself) is decorrelated from the entity. A
# leakage-free pipeline can then learn nothing: held-out Brier must collapse to
# the prior-prediction floor. With REAL labels the same model beats the floor by
# a wide margin (the per-pitcher class bias is genuinely learnable). If the
# shuffled model still beat the floor, some channel would be leaking the
# original target.

_FLOOR_TOLERANCE = 0.04  # shuffled Brier must be within 4% of the floor
_SIGNAL_MARGIN = 0.75  # real-label Brier must be <= 75% of the floor


def _model_train_window(seed: int) -> tuple[pd.DataFrame, SyntheticFold]:
    # 70 days, 10 pitchers, 5 pitches/day = 3,500 pitches; the assembled test
    # window (~21 days) yields ~1,050 rows, plenty for a small booster.
    pitches = synthetic_pitches(
        n_pitchers=10, n_batters=18, n_days=70, pitches_per_pitcher_per_day=5, seed=seed
    )
    fold = SyntheticFold(
        train_start=date(2024, 4, 1),
        train_end=date(2024, 5, 15),
        test_start=date(2024, 5, 16),
        test_end=date(2024, 6, 5),
    )
    return pitches, fold


def _temporal_split(mf: pd.DataFrame) -> tuple[pd.DataFrame, pd.DataFrame, pd.DataFrame]:
    """60/20/20 split BY DATE (no random_state, no game/pitch-level shuffle).

    The encoded test window's rows are ordered by game_date; slicing the sorted
    frame is a pure temporal cut - train precedes val precedes test in time,
    consistent with rolling-origin discipline (CLAUDE.md rule, decision [56])."""
    ordered = mf.sort_values(["game_date", "game_id", "at_bat_index", "pitch_number"]).reset_index(
        drop=True
    )
    n = len(ordered)
    tr = cast(pd.DataFrame, ordered.iloc[: int(n * 0.6)])
    va = cast(pd.DataFrame, ordered.iloc[int(n * 0.6) : int(n * 0.8)])
    te = cast(pd.DataFrame, ordered.iloc[int(n * 0.8) :])
    return tr, va, te


def _prior_floor_brier(train: pd.DataFrame, test: pd.DataFrame) -> float:
    """Brier of always predicting the TRAIN-window class prior on the test set.

    This is the realized 'random-guess floor': a model with no learnable signal
    cannot do better than emitting the marginal class distribution it saw."""
    prior = np.bincount(train["label"].to_numpy(dtype="int64"), minlength=len(LABEL_CLASSES)) / len(
        train
    )
    floor_proba = np.tile(prior, (len(test), 1))
    return multiclass_brier(test["label"].to_numpy(dtype="int64"), floor_proba)


def _fit_and_brier(tr: pd.DataFrame, va: pd.DataFrame, te: pd.DataFrame) -> float:
    bundle = model_factory(
        tr, va, num_boost_round=150, early_stopping_rounds=25, log_evaluation_period=10_000
    )
    proba = bundle.predict_proba(te)
    return multiclass_brier(te["label"].to_numpy(dtype="int64"), proba)


@pytest.mark.parametrize("head", ["pre", "post"])
def test_real_label_model_beats_prior_floor(head: str) -> None:
    """Teeth check: with REAL labels the model learns the per-entity signal, so
    held-out Brier is well below the prior floor. If it weren't, the fixture
    would carry no signal and the shuffled test below would be vacuous."""
    pitches, fold = _model_train_window(seed=21)
    assembled = assemble_pitch_features(pitches, fold, head=head)
    mf = to_model_frame(assembled)
    tr, va, te = _temporal_split(mf)
    floor = _prior_floor_brier(tr, te)
    brier = _fit_and_brier(tr, va, te)
    assert brier <= _SIGNAL_MARGIN * floor, (
        f"{head} head on REAL labels did not beat the prior floor "
        f"(brier {brier:.4f} vs {_SIGNAL_MARGIN}x floor {floor:.4f}) - the fixture "
        "lost its learnable signal, so the shuffled-target test would be vacuous"
    )


@pytest.mark.parametrize("head", ["pre", "post"])
def test_shuffled_label_model_collapses_to_prior_floor(head: str) -> None:
    """The core leakage assertion: shuffle the labels on the RAW pitches BEFORE
    feature assembly, so TE + rolling form + the target are all decorrelated
    from the entity. A leakage-free model then has nothing to learn - held-out
    Brier must sit at the prior floor. A model that still beats the floor is
    reading the original target through some unintended channel."""
    pitches, fold = _model_train_window(seed=21)
    shuffled = pitches.copy()
    rng = np.random.default_rng(SHUFFLE_SEED)
    # Permutation of a value column, NOT a data split - no rolling-origin concern.
    shuffled["label"] = rng.permutation(shuffled["label"].to_numpy())

    assembled = assemble_pitch_features(shuffled, fold, head=head)
    mf = to_model_frame(assembled)
    tr, va, te = _temporal_split(mf)
    floor = _prior_floor_brier(tr, te)
    brier = _fit_and_brier(tr, va, te)
    assert brier <= floor + _FLOOR_TOLERANCE, (
        f"{head} head on SHUFFLED labels scored Brier {brier:.4f}, more than "
        f"{_FLOOR_TOLERANCE} above the prior floor {floor:.4f} - the model is "
        "extracting target signal that should have been destroyed by the shuffle "
        "(a leak through TE, rolling form, or the label channel)"
    )
