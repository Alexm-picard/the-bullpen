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

from datetime import date, timedelta
from typing import Any, cast

import numpy as np
import pandas as pd
import pytest

from bullpen_training.features import LABEL_CLASSES
from bullpen_training.features.target_encoding import (
    DEFAULT_SMOOTHING_K,
    compute_prior,
)
from tests.leakage.conftest import SyntheticFold, assemble_pitch_features

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


# ---------------------------------------------------------------------------
# Pitch-head calendar-date trace: every Tier 3 rolling feature uses ONLY data
# strictly before the pitch's instant.
# ---------------------------------------------------------------------------
#
# For a sample of test-window pitches, INDEPENDENTLY re-derive each rolling-form
# value from the raw pitches table, restricting to rows strictly earlier than the
# traced pitch (earlier calendar days for the 28d windows; earlier in-game
# position for the in-game count). The independently traced value must equal the
# value the builder assembled. A builder that peeked at the pitch's own day - or
# at the pitch itself - would diverge from this trace.

PITCH_SAMPLE_SIZE = 12
PITCH_SAMPLE_SEED = 90909
_STRIKES = ("called_strike", "swinging_strike", "foul")


def _count(mask: pd.Series) -> int:
    """Number of True entries in a boolean Series (typed for pyright)."""
    return int(cast(int, mask.to_numpy().sum()))


def _trace_rolling_for_pitch(raw: pd.DataFrame, pitch: dict[str, Any]) -> dict[str, float]:
    """First-principles recompute of the rolling-form columns for one pitch,
    using ONLY raw rows strictly before it. Independent of the conftest
    reference - this is the hand-trace the SQL window must honour.

    `pitch` is a plain dict of the traced row's scalars (the caller passes
    `row.to_dict()`), which keeps pyright out of pandas-Series indexing."""
    d = cast(date, pitch["game_date"])
    pid = int(pitch["pitcher_id"])
    bid = int(pitch["batter_id"])
    gid = int(pitch["game_id"])
    key = (int(pitch["at_bat_index"]), int(pitch["pitch_number"]))
    lo = d - timedelta(days=28)

    p_win = raw[(raw["pitcher_id"] == pid) & (raw["game_date"] >= lo) & (raw["game_date"] < d)]
    b_win = raw[(raw["batter_id"] == bid) & (raw["game_date"] >= lo) & (raw["game_date"] < d)]

    p_n = len(p_win)
    b_n = len(b_win)
    in_game = raw[(raw["pitcher_id"] == pid) & (raw["game_id"] == gid)]
    in_game_before = in_game[
        in_game.apply(
            lambda r, _key=key: (int(r["at_bat_index"]), int(r["pitch_number"])) < _key, axis=1
        )
    ]
    prior_dates = sorted(
        set(raw.loc[(raw["pitcher_id"] == pid) & (raw["game_date"] < d), "game_date"])
    )

    def rate(num: int, den: int) -> float:
        return float(num) / den if den else float("nan")

    p_label = cast(pd.Series, p_win["label"])
    b_label = cast(pd.Series, b_win["label"])
    return {
        "pitcher_pitches_last_28d": float(p_n) if p_n else float("nan"),
        "pitcher_pitches_in_game": float(len(in_game_before)),
        "days_since_last_appearance": (
            float("nan") if not prior_dates else float((d - prior_dates[-1]).days)
        ),
        "pitcher_strike_rate_28d": rate(_count(p_label.isin(_STRIKES)), p_n),
        "pitcher_swstrike_rate_28d": rate(_count(p_label == "swinging_strike"), p_n),
        "pitcher_inplay_rate_28d": rate(_count(p_label == "in_play"), p_n),
        "batter_strike_rate_28d": rate(_count(b_label.isin(_STRIKES)), b_n),
        "batter_inplay_rate_28d": rate(_count(b_label == "in_play"), b_n),
        "batter_ball_rate_28d": rate(_count(b_label == "ball"), b_n),
    }


def test_rolling_form_recomputed_from_strictly_earlier_data_matches(
    pitch_pitches: pd.DataFrame, pitch_fold: SyntheticFold
) -> None:
    """Trace a sample of assembled test-window pitches: each rolling-form value
    must equal an independent recompute over strictly-earlier raw rows."""
    assembled = assemble_pitch_features(pitch_pitches, pitch_fold, head="pre")
    rng = np.random.default_rng(PITCH_SAMPLE_SEED)
    idx_pool = rng.choice(
        len(assembled), size=min(PITCH_SAMPLE_SIZE, len(assembled)), replace=False
    )

    for idx in idx_pool:
        row = cast("dict[str, Any]", assembled.iloc[int(idx)].to_dict())
        traced = _trace_rolling_for_pitch(pitch_pitches, row)
        for col, expected in traced.items():
            actual = float(row[col])
            if np.isnan(expected):
                assert np.isnan(actual), (
                    f"{col} for traced pitch (pitcher={int(row['pitcher_id'])}, "
                    f"date={row['game_date']}): builder gave {actual}, trace says NULL"
                )
            else:
                assert actual == pytest.approx(expected, rel=1e-5, abs=1e-6), (
                    f"{col} mismatch for traced pitch (pitcher={int(row['pitcher_id'])}, "
                    f"date={row['game_date']}): builder {actual}, "
                    f"strictly-earlier trace {expected}"
                )


def test_rolling_form_trace_canary_including_current_day_diverges(
    pitch_pitches: pd.DataFrame, pitch_fold: SyntheticFold
) -> None:
    """Canary: a recompute that WIDENS the in-game window to include the pitch
    itself must diverge from the assembled (strict) value for at least one
    sampled pitch. If it never diverged, the trace couldn't detect an off-by-one
    leak (the fixture would have no within-game depth)."""
    assembled = assemble_pitch_features(pitch_pitches, pitch_fold, head="pre")
    diverged = False
    for idx in range(min(40, len(assembled))):
        row = cast("dict[str, Any]", assembled.iloc[idx].to_dict())
        gid = int(row["game_id"])
        pid = int(row["pitcher_id"])
        key = (int(row["at_bat_index"]), int(row["pitch_number"]))
        in_game = pitch_pitches[
            (pitch_pitches["pitcher_id"] == pid) & (pitch_pitches["game_id"] == gid)
        ]
        leaky_mask = in_game.apply(
            # key is default-bound so this loop iteration's key is captured (B023).
            lambda r, _key=key: (int(r["at_bat_index"]), int(r["pitch_number"])) <= _key,
            axis=1,
        )
        leaky_count = float(_count(cast(pd.Series, leaky_mask)))
        if leaky_count != float(row["pitcher_pitches_in_game"]):
            diverged = True
            break
    assert diverged, (
        "calendar-trace canary: including the pitch itself in the in-game count "
        "never changed the value - the fixture lacks within-game depth, so the "
        "strict-vs-leaky trace can't distinguish a CURRENT ROW off-by-one"
    )
