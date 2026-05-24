"""Leakage test #2 — shuffled target (Phase 2a.3, CLAUDE.md rule 10).

If we shuffle the label column, every entity's class distribution should
collapse to the global prior (Bayesian smoothing then nudges any
remaining noise back). The model would have nothing to learn.

If, instead, the TE retains signal after shuffling, something is leaking
the original target through an unintended channel.
"""

from __future__ import annotations

import numpy as np
import pandas as pd
import pytest

from bullpen_training.features import LABEL_CLASSES
from bullpen_training.features.target_encoding import compute_prior, compute_te

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
