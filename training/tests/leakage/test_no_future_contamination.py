"""Leakage test #1 — future contamination (Phase 2a.3, CLAUDE.md rule 10).

Mutating pitches outside the fold's train window MUST NOT change any
target-encoded feature value for that fold. If the orchestrator ever
silently widens its read past `train_end`, this test fails.

Mutation discipline: corrupt labels at dates > train_end, then re-build
the fold. The TE columns should be byte-identical. (Labels in the test
window itself ARE allowed to change since they're the prediction target,
just not the FEATURES used to predict them.)
"""

from __future__ import annotations

import numpy as np
import pandas as pd
import pytest

from tests.leakage.conftest import (
    ROLLING_FORM_COLUMNS,
    SyntheticFold,
    assemble_pitch_features,
    build_fold_inmem,
)


def _te_columns(df: pd.DataFrame) -> list[str]:
    return [c for c in df.columns if "_te_" in c]


# Tier 2 (TE) + Tier 3 (rolling form) are the columns a future-window mutation
# must not move. Tier 4 (post head) is a pure per-pitch measurement with no
# temporal component, so it is exercised by the pre/post boundary tests instead.
def _pre_pitch_feature_columns(df: pd.DataFrame) -> list[str]:
    return _te_columns(df) + [c for c in ROLLING_FORM_COLUMNS if c in df.columns]


def test_future_label_mutation_does_not_affect_te(
    pitches: pd.DataFrame, fold: SyntheticFold
) -> None:
    baseline = build_fold_inmem(pitches, fold)

    mutated = pitches.copy()
    future = mutated["game_date"] > fold.train_end
    assert future.any(), "fixture broken — no rows after train_end"
    mutated.loc[future, "label"] = "ball"  # nuke every future label

    corrupted = build_fold_inmem(mutated, fold)

    te_cols = _te_columns(baseline)
    assert te_cols, "no TE columns to test against — encoded frame is empty?"

    for col in te_cols:
        pd.testing.assert_series_equal(
            baseline[col].reset_index(drop=True),
            corrupted[col].reset_index(drop=True),
            check_names=False,
            check_dtype=False,
            obj=f"{col} drift after post-train-end label mutation",
        )


def test_past_train_window_does_affect_te(pitches: pd.DataFrame, fold: SyntheticFold) -> None:
    """Mutation-test the test itself: changing train-window data DOES
    change TE. If this assertion ever fails the leakage test above is
    vacuous (the function isn't reading anything)."""
    baseline = build_fold_inmem(pitches, fold)

    mutated = pitches.copy()
    in_train = (mutated["game_date"] >= fold.train_start) & (mutated["game_date"] <= fold.train_end)
    mutated.loc[in_train, "label"] = "ball"

    corrupted = build_fold_inmem(mutated, fold)

    te_cols = _te_columns(baseline)
    any_drift = any(
        not baseline[c].reset_index(drop=True).equals(corrupted[c].reset_index(drop=True))
        for c in te_cols
    )
    assert any_drift, (
        "no_future_contamination canary: mutating the TRAIN window did NOT change "
        "any TE column. Either compute_te is broken or the fixture lost its signal."
    )


def test_pitcher_appearing_only_after_train_end_gets_prior(
    pitches: pd.DataFrame, fold: SyntheticFold
) -> None:
    """An entity that only appears in the test window must encode to the
    train-window prior (cold-start). If we ever silently use test-window
    data to encode test-window pitches, this would shift away from prior."""
    pitches_plus = pd.concat(
        [
            pitches,
            pd.DataFrame(
                [
                    {
                        "game_id": 999_999,
                        "at_bat_index": 1,
                        "pitch_number": 1,
                        "game_date": fold.test_start,
                        "pitcher_id": 9999,
                        "batter_id": 1,
                        "label": "in_play",
                    }
                ]
            ),
        ],
        ignore_index=True,
    )

    encoded = build_fold_inmem(pitches_plus, fold)
    new_pitcher_row = encoded.loc[encoded["pitcher_id"] == 9999]
    assert len(new_pitcher_row) == 1, "synthetic new pitcher row missing"

    # Train-window prior across all classes
    from bullpen_training.features import LABEL_CLASSES
    from bullpen_training.features.target_encoding import compute_prior

    train_df = pitches.loc[
        (pitches["game_date"] >= fold.train_start) & (pitches["game_date"] <= fold.train_end)
    ]
    prior = compute_prior(train_df, "label")

    for cls in LABEL_CLASSES:
        actual = float(new_pitcher_row[f"pitcher_te_{cls}"].iloc[0])
        assert actual == pytest.approx(prior[cls], rel=1e-5), (
            f"cold-start pitcher's pitcher_te_{cls} drifted from prior "
            f"(got {actual}, prior {prior[cls]}) — possible leak from test window"
        )


# ---------------------------------------------------------------------------
# Pitch-head (pre / post) future-contamination coverage
# ---------------------------------------------------------------------------
#
# The pitch builder adds Tier 3 rolling form on top of the Tier 2 TE. Corrupting
# data STRICTLY AFTER each pitch's instant - both the future labels (which feed
# TE) and the future rolling-form source rows - must leave every pre-pitch
# feature of an EARLIER pitch byte-identical. A streaming temporal cutoff has
# this property by construction; a widened read does not.


def _corrupt_future_of_test_window(pitches: pd.DataFrame, fold: SyntheticFold) -> pd.DataFrame:
    """Nuke every label after the test window's first day.

    Anything after `test_start` is "the future" relative to the earliest test
    pitch. A correct pipeline encodes the earliest test pitches from train-window
    + strictly-earlier test rows only, so mutating later test rows must not move
    those earlier pitches' features. We compare on the first test day's rows.
    """
    mutated = pitches.copy()
    after_first_test_day = mutated["game_date"] > fold.test_start
    mutated.loc[after_first_test_day, "label"] = "ball"
    return mutated


@pytest.mark.parametrize("head", ["pre", "post"])
def test_pitch_head_future_mutation_does_not_move_first_day_features(
    pitch_pitches: pd.DataFrame, pitch_fold: SyntheticFold, head: str
) -> None:
    """Build the pitch-head features, then rebuild after corrupting every label
    strictly after the test window's first day. The pre-pitch features (TE +
    rolling form) of the FIRST-DAY pitches must be byte-identical: they can only
    legitimately depend on data at or before the first test day."""
    baseline = assemble_pitch_features(pitch_pitches, pitch_fold, head=head)
    corrupted = assemble_pitch_features(
        _corrupt_future_of_test_window(pitch_pitches, pitch_fold), pitch_fold, head=head
    )

    first_day = pitch_fold.test_start
    base_fd = baseline.loc[baseline["game_date"] == first_day].sort_values(
        ["game_id", "at_bat_index", "pitch_number"]
    )
    corr_fd = corrupted.loc[corrupted["game_date"] == first_day].sort_values(
        ["game_id", "at_bat_index", "pitch_number"]
    )
    assert not base_fd.empty, "no first-test-day rows - fixture/window broken"
    assert len(base_fd) == len(corr_fd), "first-day row count changed under mutation"

    feat_cols = _pre_pitch_feature_columns(baseline)
    assert any("_te_" in c for c in feat_cols), "no TE columns"
    assert any(c in feat_cols for c in ROLLING_FORM_COLUMNS), "no rolling-form columns"

    for col in feat_cols:
        np.testing.assert_array_equal(
            base_fd[col].to_numpy(),
            corr_fd[col].to_numpy(),
            err_msg=(
                f"{head} head: feature {col!r} on the first test day changed when "
                "only strictly-later data was mutated - future contamination"
            ),
        )


def test_pitch_head_canary_mutating_prior_data_moves_features(
    pitch_pitches: pd.DataFrame, pitch_fold: SyntheticFold
) -> None:
    """Mutation-test the test itself: corrupting data AT OR BEFORE the first
    test day MUST move the first-day features. If it doesn't, the assertion
    above is vacuous (the builder isn't reading the history it claims to)."""
    baseline = assemble_pitch_features(pitch_pitches, pitch_fold, head="pre")

    mutated = pitch_pitches.copy()
    at_or_before_first = mutated["game_date"] <= pitch_fold.test_start
    mutated.loc[at_or_before_first, "label"] = "ball"
    corrupted = assemble_pitch_features(mutated, pitch_fold, head="pre")

    first_day = pitch_fold.test_start
    base_fd = baseline.loc[baseline["game_date"] == first_day].sort_values(
        ["game_id", "at_bat_index", "pitch_number"]
    )
    corr_fd = corrupted.loc[corrupted["game_date"] == first_day].sort_values(
        ["game_id", "at_bat_index", "pitch_number"]
    )

    feat_cols = _pre_pitch_feature_columns(baseline)
    any_move = any(
        not np.array_equal(base_fd[c].to_numpy(), corr_fd[c].to_numpy(), equal_nan=True)
        for c in feat_cols
    )
    assert any_move, (
        "pitch-head canary: mutating data at/before the first test day did NOT "
        "move any first-day feature - the builder or fixture lost its signal, so "
        "the future-contamination assertion is vacuous"
    )
