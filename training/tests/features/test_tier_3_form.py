"""Unit tests for the Tier 3 rolling-form pipeline (Phase 2a.2).

ClickHouse-side correctness (the windowed SQL) is covered by the live
drill at the end of the leaf. Here we lock the pure-Python pieces:
the column tuples and the merge invariants on small synthetic frames.

Hand-traced semantics (the spec the windowed SQL has to honour):
  - pitcher_pitches_last_28d uses ONLY pitches with game_date in
    [game_date - 28, game_date - 1]
  - pitcher_pitches_in_game counts strictly-before the current pitch
    in the same (pitcher_id, game_id)
  - days_since_last_appearance is NULL on the pitcher's first
    appearance, else (game_date - prev_appearance_date) in days
"""

from __future__ import annotations

import pandas as pd

from bullpen_training.features.tier_3_form import (
    FEATURES_COLUMNS_FULL,
    PK_JOIN,
    TIER3_COLUMNS,
)


def test_tier3_columns_listed_in_full_features() -> None:
    """If somebody adds a Tier 3 column to V006 they must extend both
    TIER3_COLUMNS and FEATURES_COLUMNS_FULL; this guards the contract."""
    full = set(FEATURES_COLUMNS_FULL)
    assert set(TIER3_COLUMNS).issubset(full), (
        f"Tier 3 columns missing from FEATURES_COLUMNS_FULL: {set(TIER3_COLUMNS) - full}"
    )


def test_pk_join_columns_present_in_full_features() -> None:
    full = set(FEATURES_COLUMNS_FULL)
    assert set(PK_JOIN).issubset(full)


def test_tier3_columns_count_matches_v006() -> None:
    # V006 adds 11 columns (8 nullable + 1 non-null with DEFAULT + 2 std).
    # Keep this lock-step with backend/.../db/clickhouse/V006__features_tier3.sql.
    assert len(TIER3_COLUMNS) == 11


def test_features_columns_full_starts_with_pk_then_metadata() -> None:
    # First three are the PK; downstream JOINs depend on this ordering.
    assert FEATURES_COLUMNS_FULL[:3] == PK_JOIN


def test_merge_invariant_left_join_preserves_t12_rows() -> None:
    """Simulate what build_fold_full does: T1+2 rows LEFT JOIN T3 rows on
    PK. Any T1+2 row without a T3 match must keep its other columns and
    end up with NaN Tier 3 values (warm-up start of fold, edge case)."""
    t12 = pd.DataFrame(
        {
            "game_id": [1, 2, 3],
            "at_bat_index": [1, 1, 2],
            "pitch_number": [1, 1, 1],
            "label": ["ball", "called_strike", "in_play"],
        }
    )
    t3 = pd.DataFrame(
        {
            "game_id": [2, 3],
            "at_bat_index": [1, 2],
            "pitch_number": [1, 1],
            "pitcher_pitches_last_28d": [42, 7],
        }
    )
    merged = t12.merge(t3, on=list(PK_JOIN), how="left")
    assert len(merged) == 3
    # Row with no T3 match keeps label and gets NaN for the T3 col
    row1 = merged.loc[merged["game_id"] == 1].iloc[0]
    assert row1["label"] == "ball"
    assert pd.isna(row1["pitcher_pitches_last_28d"])
    # Matched rows carry the T3 value through
    row2 = merged.loc[merged["game_id"] == 2].iloc[0]
    assert row2["pitcher_pitches_last_28d"] == 42
