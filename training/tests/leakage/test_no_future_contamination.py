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

import pandas as pd
import pytest

from tests.leakage.conftest import SyntheticFold, build_fold_inmem


def _te_columns(df: pd.DataFrame) -> list[str]:
    return [c for c in df.columns if "_te_" in c]


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
