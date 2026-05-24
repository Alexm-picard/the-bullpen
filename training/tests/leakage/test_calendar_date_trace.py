"""Leakage test #3 — calendar-date trace (Phase 2a.3, CLAUDE.md rule 10).

For a sample of rows in the encoded test-window frame, hand-recompute
the target-encoded value from FIRST PRINCIPLES, using ONLY rows whose
`game_date <= train_end`. If the orchestrator ever silently widens its
read, the recomputed value diverges from the stored one and this test
fails loudly.

The strict-less-than discipline is decision [39] (Tier 2 TE) and [40]
(Tier 3 windows). This test guards the Tier 2 path; the Tier 3 SQL is
sanity-checked by the hand-trace committed in 2a.2's leaf plan.
"""

from __future__ import annotations

import numpy as np
import pandas as pd
import pytest

from bullpen_training.features import LABEL_CLASSES
from bullpen_training.features.target_encoding import (
    DEFAULT_SMOOTHING_K,
    compute_prior,
)
from tests.leakage.conftest import SyntheticFold

SAMPLE_SIZE = 10
SAMPLE_SEED = 4242


def _expected_te(
    train_df: pd.DataFrame, entity_id: int, entity_col: str, cls: str, prior: dict[str, float]
) -> float:
    rows = train_df[train_df[entity_col] == entity_id]
    n_total = len(rows)
    n_cls = int((rows["label"] == cls).sum())
    k = DEFAULT_SMOOTHING_K
    return float((n_cls + k * prior[cls]) / (n_total + k))


def test_te_recomputed_from_pre_train_end_data_matches_stored(
    pitches: pd.DataFrame, fold: SyntheticFold, encoded: pd.DataFrame
) -> None:
    rng = np.random.default_rng(SAMPLE_SEED)
    idx_pool = rng.choice(len(encoded), size=min(SAMPLE_SIZE, len(encoded)), replace=False)

    train_df = pitches.loc[
        (pitches["game_date"] >= fold.train_start) & (pitches["game_date"] <= fold.train_end)
    ]
    prior = compute_prior(train_df, "label")

    for idx in idx_pool:
        row = encoded.iloc[int(idx)]
        for cls in LABEL_CLASSES:
            expected = _expected_te(train_df, int(row["pitcher_id"]), "pitcher_id", cls, prior)
            actual = float(row[f"pitcher_te_{cls}"])
            assert actual == pytest.approx(expected, rel=1e-4), (
                f"pitcher_te_{cls} mismatch for row {idx} (pitcher_id="
                f"{row['pitcher_id']}, pitch_date={row['game_date']}): "
                f"stored {actual}, recomputed-from-train-window {expected}"
            )


def test_recompute_widening_window_changes_value(
    pitches: pd.DataFrame, fold: SyntheticFold, encoded: pd.DataFrame
) -> None:
    """Canary: if we WIDEN the recomputation window past train_end and
    the result still matches `encoded`, the original encoding was
    suspect (it would mean the fold's train end was meaningless)."""
    sample_row = encoded.iloc[0]
    pitcher_id = int(sample_row["pitcher_id"])
    # Recompute over a wider window that includes test data
    leaked_train = pitches.loc[pitches["game_date"] <= fold.test_end]
    prior_leaked = compute_prior(leaked_train, "label")
    leaked_te_ball = _expected_te(leaked_train, pitcher_id, "pitcher_id", "ball", prior_leaked)
    stored_te_ball = float(sample_row["pitcher_te_ball"])
    # The wider window includes more rows; the smoothed estimate moves.
    # If it didn't move, our test wouldn't catch leaks (signal-free fixture).
    assert leaked_te_ball != pytest.approx(stored_te_ball, abs=1e-6), (
        "calendar_date_trace canary: widening the recompute window past "
        "train_end produced the SAME te_ball value. Either the fixture has "
        "no test-window signal or the recomputation logic is broken."
    )


def test_every_pitch_in_test_window_has_game_date_after_train_end(
    fold: SyntheticFold, encoded: pd.DataFrame
) -> None:
    """The build_fold_inmem helper must not leak train-window rows into
    the encoded test frame. Strict less-than on as_of_date vs game_date."""
    too_early = encoded.loc[encoded["game_date"] <= fold.train_end]
    assert too_early.empty, (
        f"encoded frame contains {len(too_early)} rows with "
        f"game_date <= train_end ({fold.train_end}) — leakage from train window"
    )
