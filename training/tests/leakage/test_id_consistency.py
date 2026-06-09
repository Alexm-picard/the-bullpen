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

from typing import Any, cast

import numpy as np
import pandas as pd

from bullpen_training.features import LABEL_CLASSES
from tests.leakage.conftest import (
    ROLLING_FORM_COLUMNS,
    SyntheticFold,
    assemble_pitch_features,
    build_fold_inmem,
)


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


# ---------------------------------------------------------------------------
# Pitch-head ID consistency
# ---------------------------------------------------------------------------
#
# Two invariants the pitch builder must satisfy:
#   1. Same pitcher_id within the fold -> identical TE (it's a (pitcher, fold)
#      property, not a per-pitch one). A per-pitch-varying TE means the encoder
#      peeked at a pitch-level attribute (eg, the pitch's own label).
#   2. The full pitch-head feature set is a deterministic function of identity +
#      time + in-game position only. Permuting the RAW input row order must not
#      change any feature value for a given pitch (keyed by its PK). Sensitivity
#      to input order would be a covert leak channel.

_PK = ["game_id", "at_bat_index", "pitch_number"]


def test_pitch_te_constant_within_pitcher_in_assembled_frame(
    pitch_pitches: pd.DataFrame, pitch_fold: SyntheticFold
) -> None:
    """TE columns in the assembled pitch-head frame are constant per pitcher."""
    assembled = assemble_pitch_features(pitch_pitches, pitch_fold, head="pre")
    for cls in LABEL_CLASSES:
        col = f"pitcher_te_{cls}"
        per_pitcher_unique = cast(pd.Series, assembled.groupby("pitcher_id")[col].nunique())
        offenders = cast(pd.Series, per_pitcher_unique[per_pitcher_unique > 1])
        assert offenders.empty, (
            f"{col} varied within a pitcher in the assembled frame - a per-pitch "
            f"leak. Offenders: {offenders.head(5).to_dict()}"
        )


def test_pitch_features_invariant_to_raw_row_order(
    pitch_pitches: pd.DataFrame, pitch_fold: SyntheticFold
) -> None:
    """Permute the RAW pitches frame, rebuild the full pitch-head feature set,
    and assert every feature for every pitch (keyed by PK) is unchanged. Covers
    TE (Tier 2) AND rolling form (Tier 3): a row-order dependence anywhere would
    mean the builder is reading position instead of identity + time."""
    rng = np.random.default_rng(2024)
    # A row permutation of the INPUT, not a data split - rolling-origin N/A.
    permuted = cast(
        pd.DataFrame,
        pitch_pitches.iloc[rng.permutation(len(pitch_pitches))].reset_index(drop=True),
    )

    base = assemble_pitch_features(pitch_pitches, pitch_fold, head="post")
    perm = assemble_pitch_features(permuted, pitch_fold, head="post")

    base_s = base.sort_values(_PK).reset_index(drop=True)
    perm_s = perm.sort_values(_PK).reset_index(drop=True)
    assert (base_s[_PK].to_numpy() == perm_s[_PK].to_numpy()).all(), "PK set diverged"

    feature_cols = [c for c in base_s.columns if "_te_" in c] + [
        c for c in ROLLING_FORM_COLUMNS if c in base_s.columns
    ]
    assert feature_cols, "no feature columns to compare"
    for col in feature_cols:
        np.testing.assert_array_equal(
            base_s[col].to_numpy(),
            perm_s[col].to_numpy(),
            err_msg=f"{col} changed under raw row-order permutation - covert leak",
        )


def test_same_pitcher_history_gives_same_te_regardless_of_position(
    pitch_pitches: pd.DataFrame, pitch_fold: SyntheticFold
) -> None:
    """A pitcher who appears at many row positions across the test window gets
    ONE TE vector (the (pitcher, fold) encoding). Pick the busiest pitcher and
    assert its TE is identical at its first and last row position - proof the
    encoding is keyed on identity, not on where the row landed."""
    assembled = assemble_pitch_features(pitch_pitches, pitch_fold, head="pre")
    counts = cast(pd.Series, assembled["pitcher_id"].value_counts())
    busiest = int(cast(int, counts.index[0]))
    rows = assembled.loc[assembled["pitcher_id"] == busiest].sort_values(_PK)
    assert len(rows) >= 2, "need a pitcher with >=2 test-window pitches"
    first = cast("dict[str, Any]", rows.iloc[0].to_dict())
    last = cast("dict[str, Any]", rows.iloc[-1].to_dict())
    for cls in LABEL_CLASSES:
        col = f"pitcher_te_{cls}"
        assert float(first[col]) == float(last[col]), (
            f"{col} for pitcher {busiest} differs between its first and last "
            f"row position ({first[col]} vs {last[col]}) - position-dependent TE"
        )
