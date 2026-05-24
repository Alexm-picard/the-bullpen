"""Leakage test #4 — ID consistency (Phase 2a.3, CLAUDE.md rule 10).

Two pitches by the same pitcher in the same fold MUST get identical
target-encoded values — the encoding is a property of the (pitcher,
fold), not of the individual pitch. If TE varied within a pitcher within
a fold, that means a per-pitch (per-row) leak: the encoder was peeking
at some pitch-level attribute it shouldn't see (eg, the pitch's own
label).

Same invariant for batters, and same for cross-fold determinism: if
the same pitcher's TE differs across two independent builds of the
SAME fold from the SAME data, the function isn't deterministic.
"""

from __future__ import annotations

from typing import cast

import pandas as pd

from bullpen_training.features import LABEL_CLASSES
from tests.leakage.conftest import SyntheticFold, build_fold_inmem


def test_pitcher_te_constant_within_fold_per_pitcher(encoded: pd.DataFrame) -> None:
    for cls in LABEL_CLASSES:
        col = f"pitcher_te_{cls}"
        per_pitcher_unique = cast(pd.Series, encoded.groupby("pitcher_id")[col].nunique())
        offenders = cast(pd.Series, per_pitcher_unique[per_pitcher_unique > 1])
        assert offenders.empty, (
            f"{col} varied within a pitcher within the fold — "
            f"per-pitch leakage. Offenders (pitcher_id → distinct values): "
            f"{offenders.head(5).to_dict()}"
        )


def test_batter_te_constant_within_fold_per_batter(encoded: pd.DataFrame) -> None:
    for cls in LABEL_CLASSES:
        col = f"batter_te_{cls}"
        per_batter_unique = cast(pd.Series, encoded.groupby("batter_id")[col].nunique())
        offenders = cast(pd.Series, per_batter_unique[per_batter_unique > 1])
        assert offenders.empty, f"{col} varied within a batter within the fold"


def test_rebuild_is_deterministic(pitches: pd.DataFrame, fold: SyntheticFold) -> None:
    """Same input → same output, twice. Catches any hidden randomness in
    compute_te / apply_te / the orchestrator."""
    first = build_fold_inmem(pitches, fold)
    second = build_fold_inmem(pitches, fold)
    te_cols = [c for c in first.columns if "_te_" in c]
    for col in te_cols:
        pd.testing.assert_series_equal(
            first[col].reset_index(drop=True),
            second[col].reset_index(drop=True),
            check_names=False,
            check_dtype=False,
            obj=f"{col} differed across identical rebuilds",
        )


def test_row_order_does_not_affect_te(pitches: pd.DataFrame, fold: SyntheticFold) -> None:
    """If we permute the input frame's rows, compute_te must still
    return the same TE values per entity. Sensitivity to row order
    would be a covert leak channel (e.g., implicit time-of-row peeking)."""
    permuted = pitches.sample(frac=1, random_state=99).reset_index(drop=True)
    a = build_fold_inmem(pitches, fold)
    b = build_fold_inmem(permuted, fold)
    a_sorted = a.sort_values(
        ["pitcher_id", "batter_id", "game_date", "game_id", "pitch_number"]
    ).reset_index(drop=True)
    b_sorted = b.sort_values(
        ["pitcher_id", "batter_id", "game_date", "game_id", "pitch_number"]
    ).reset_index(drop=True)
    te_cols = [c for c in a_sorted.columns if "_te_" in c]
    for col in te_cols:
        pd.testing.assert_series_equal(
            a_sorted[col],
            b_sorted[col],
            check_names=False,
            check_dtype=False,
            obj=f"{col} sensitive to input row order — possible covert leak",
        )
