"""Synthetic-data fixtures shared by the 4 leakage tests (Phase 2a.3).

CI runs against pure-pandas synthetic data — no ClickHouse, no
pybaseball. Live correctness on real CH data is covered by the
end-of-leaf hand-trace + the integration smoke in the build script.

The four tests are the CI gate for CLAUDE.md rule 10. They MUST stay
fast (<5 min total) and deterministic so they can run on every push.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date, timedelta
from typing import Any, cast

import numpy as np
import pandas as pd
import pytest

from bullpen_training.features import LABEL_CLASSES
from bullpen_training.features.target_encoding import (
    apply_te,
    compute_prior,
    compute_te,
)


@dataclass(frozen=True)
class SyntheticFold:
    train_start: date
    train_end: date
    test_start: date
    test_end: date


def _signal_label(pitcher_id: int, rng: np.random.Generator) -> str:
    """Each pitcher has a strong preferred class — gives target encoding real
    signal so the shuffled-target test has something to detect."""
    bias_idx = pitcher_id % len(LABEL_CLASSES)
    weights = np.full(len(LABEL_CLASSES), 0.05)
    weights[bias_idx] = 1.0 - 0.05 * (len(LABEL_CLASSES) - 1)
    return rng.choice(LABEL_CLASSES, p=weights)


def synthetic_pitches(
    *,
    n_pitchers: int = 30,
    n_batters: int = 50,
    n_days: int = 60,
    pitches_per_pitcher_per_day: int = 8,
    seed: int = 1337,
) -> pd.DataFrame:
    """Deterministic synthetic pitches: 60 days by 30 pitchers by 8 pitches/day
    = 14,400 rows. Small enough to keep tests <30s, big enough that the
    Bayesian smoothing (k=20) doesn't drown out the per-pitcher signal."""
    rng = np.random.default_rng(seed)
    base = date(2024, 4, 1)
    rows: list[dict[str, object]] = []
    game_pk_counter = 700_000
    for d in range(n_days):
        gd = base + timedelta(days=d)
        game_pk_counter += 1
        for pitcher_id in range(1, n_pitchers + 1):
            batter_id = int(rng.integers(1, n_batters + 1))
            for pitch_no in range(1, pitches_per_pitcher_per_day + 1):
                rows.append(
                    {
                        "game_id": game_pk_counter,
                        "at_bat_index": pitcher_id,
                        "pitch_number": pitch_no,
                        "game_date": gd,
                        "pitcher_id": pitcher_id,
                        "batter_id": batter_id,
                        "label": _signal_label(pitcher_id, rng),
                    }
                )
    return pd.DataFrame(rows)


def build_fold_inmem(pitches: pd.DataFrame, fold: SyntheticFold) -> pd.DataFrame:
    """Pure-pandas mirror of `tier_1_2.build_fold_features` so leakage tests
    don't need ClickHouse. Returns the encoded test-window rows."""
    train_mask = (pitches["game_date"] >= fold.train_start) & (
        pitches["game_date"] <= fold.train_end
    )
    test_mask = (pitches["game_date"] >= fold.test_start) & (pitches["game_date"] <= fold.test_end)
    train_df = cast(pd.DataFrame, pitches.loc[train_mask].copy())
    test_df = cast(pd.DataFrame, pitches.loc[test_mask].copy())

    pitcher_te = compute_te(train_df, entity_col="pitcher_id", label_col="label")
    batter_te = compute_te(train_df, entity_col="batter_id", label_col="label")
    prior = compute_prior(train_df, "label")

    encoded = apply_te(
        test_df, pitcher_te, entity_col="pitcher_id", column_prefix="pitcher", prior=prior
    )
    encoded = apply_te(
        encoded, batter_te, entity_col="batter_id", column_prefix="batter", prior=prior
    )
    return cast(pd.DataFrame, encoded)


@pytest.fixture(scope="session")
def fold() -> SyntheticFold:
    """30-day train window then 30-day test window — adjacent, non-overlapping."""
    return SyntheticFold(
        train_start=date(2024, 4, 1),
        train_end=date(2024, 4, 30),
        test_start=date(2024, 5, 1),
        test_end=date(2024, 5, 30),
    )


@pytest.fixture(scope="session")
def pitches() -> pd.DataFrame:
    return synthetic_pitches()


@pytest.fixture(scope="session")
def encoded(pitches: pd.DataFrame, fold: SyntheticFold) -> pd.DataFrame:
    return build_fold_inmem(pitches, fold)


# ---------------------------------------------------------------------------
# Pitch-head (pre / post) feature assembly - real builder logic, no ClickHouse
# ---------------------------------------------------------------------------
#
# The four leakage categories above exercise the Tier 2 target-encoding path
# (the SQL-coupled fold builder is mirrored by build_fold_inmem). They do NOT
# exercise the *pitch-head distinction*:
#
#   * pitch_outcome_pre  = Tier 1 + Tier 2 (TE) + Tier 3 (rolling form). 31 cols.
#   * pitch_outcome_post = the same 31 + Tier 4 (post-pitch release/flight). 41 cols.
#
# The Tier 3 rolling form is computed in ClickHouse window functions
# (features/sql/compute_tier3.sql). Its temporal-cutoff correctness on the real
# CH path stays covered by test_sql_path_contamination (CH-gated). What was
# missing from the fast CI gate is a pure-Python *reference* for the streaming
# cutoff that the four categories can drive without a database - so the
# rolling-form features get the same future-contamination / calendar-trace /
# id-consistency / shuffled-target scrutiny the TE features already get.
#
# `rolling_form_reference` below is that reference. It mirrors the SQL windows
# EXACTLY (decision [40]): every count uses rows STRICTLY before the pitch
# (`... AND 1 PRECEDING` for the date ranges, `... AND 1 PRECEDING` ROWS for the
# in-game count). The deliberately-leaky variant used by the detector
# self-tests flips a single window edge to CURRENT ROW - the canonical
# off-by-one leak this whole suite exists to catch.

# Tier 3 columns (subset mirror of tier_3_form.TIER3_COLUMNS - the std-dev
# columns need a multi-season window the tiny fixtures don't exercise, so the
# reference covers the count + rate + recency columns that carry the cutoff).
ROLLING_FORM_COLUMNS: tuple[str, ...] = (
    "pitcher_pitches_last_28d",
    "pitcher_pitches_in_game",
    "days_since_last_appearance",
    "pitcher_strike_rate_28d",
    "pitcher_swstrike_rate_28d",
    "pitcher_inplay_rate_28d",
    "batter_strike_rate_28d",
    "batter_inplay_rate_28d",
    "batter_ball_rate_28d",
)

# Tier 4 post-pitch columns (mirror of tier_4_postpitch.TIER4_COLUMNS).
TIER4_REFERENCE_COLUMNS: tuple[str, ...] = (
    "pitch_type",
    "release_speed_mph",
    "plate_x_in",
    "plate_z_in",
    "pfx_x_in",
    "pfx_z_in",
    "spin_rate_rpm",
    "spin_axis_deg",
    "release_pos_x_in",
    "release_pos_z_in",
)

_STRIKE_DESCRIPTIONS = ("called_strike", "swinging_strike", "foul")

# In-game pitch ordering key: same as the SQL window's ORDER BY.
_INGAME_ORDER = ("game_id", "at_bat_index", "pitch_number")


def _rate(numer: float, denom: float) -> float:
    """SQL `if(denom = 0, NULL, numer/denom)` mirror; NaN for the NULL case."""
    return float(numer) / float(denom) if denom else float("nan")


def rolling_form_reference(
    pitches: pd.DataFrame, *, leak_current_row: bool = False
) -> pd.DataFrame:
    """Pure-Python streaming-cutoff mirror of compute_tier3.sql.

    For each pitch row, every rolling-form value is computed from ONLY the
    pitches that occurred STRICTLY before it:

      * 28d windows   -> pitches with game_date in [d - 28, d - 1] (same entity)
      * in-game count -> earlier pitches in the same (pitcher_id, game_id),
                         ordered by (at_bat_index, pitch_number)
      * days_since_last_appearance -> NULL on first appearance else gap in days

    Returns a frame keyed by PK (game_id, at_bat_index, pitch_number) plus the
    rolling-form columns. NaN encodes the SQL NULL.

    `leak_current_row=True` is the deliberately-LEAKY variant: the 28d windows
    include the pitch's OWN day (`<= d` instead of `< d`) and the in-game count
    includes the pitch itself. This is the canonical off-by-one temporal leak;
    the detector self-tests assert the leakage checks FIRE on it.
    """
    df = pitches.sort_values(list(_INGAME_ORDER)).reset_index(drop=True)
    games = cast("list[dict[str, Any]]", df.to_dict("records"))

    out_rows: list[dict[str, object]] = []
    for row in games:
        d = cast(date, row["game_date"])
        pid = int(row["pitcher_id"])
        bid = int(row["batter_id"])
        gid = int(row["game_id"])
        abi = int(row["at_bat_index"])
        pn = int(row["pitch_number"])

        lo = d - timedelta(days=28)

        def _in_window(gd: date, _d: date = d, _lo: date = lo) -> bool:
            # Strict cutoff: earlier days only. The leaky variant lets _d through.
            # _d/_lo are default-bound so the closure captures THIS pitch's window
            # (B023: never reads the loop variable late).
            return _lo <= gd <= _d if leak_current_row else _lo <= gd < _d

        p_hist = [
            r
            for r in games
            if int(r["pitcher_id"]) == pid and _in_window(cast(date, r["game_date"]))
        ]
        b_hist = [
            r
            for r in games
            if int(r["batter_id"]) == bid and _in_window(cast(date, r["game_date"]))
        ]

        p_n = len(p_hist)
        p_strikes = sum(1 for r in p_hist if r["label"] in _STRIKE_DESCRIPTIONS)
        p_sw = sum(1 for r in p_hist if r["label"] == "swinging_strike")
        p_inplay = sum(1 for r in p_hist if r["label"] == "in_play")
        b_n = len(b_hist)
        b_strikes = sum(1 for r in b_hist if r["label"] in _STRIKE_DESCRIPTIONS)
        b_inplay = sum(1 for r in b_hist if r["label"] == "in_play")
        b_balls = sum(1 for r in b_hist if r["label"] == "ball")

        # In-game count: strictly-earlier pitches in this (pitcher, game).
        this_key = (abi, pn)
        in_game_peers = [
            r
            for r in games
            if int(r["pitcher_id"]) == pid
            and int(r["game_id"]) == gid
            and (
                (int(r["at_bat_index"]), int(r["pitch_number"])) <= this_key
                if leak_current_row
                else (int(r["at_bat_index"]), int(r["pitch_number"])) < this_key
            )
        ]

        # days_since_last_appearance: distinct earlier dates for this pitcher.
        prior_dates = sorted(
            {
                cast(date, r["game_date"])
                for r in games
                if int(r["pitcher_id"]) == pid and cast(date, r["game_date"]) < d
            }
        )
        days_since = float("nan") if not prior_dates else float((d - prior_dates[-1]).days)

        out_rows.append(
            {
                "game_id": gid,
                "at_bat_index": abi,
                "pitch_number": pn,
                "pitcher_pitches_last_28d": float(p_n) if p_n else float("nan"),
                "pitcher_pitches_in_game": float(len(in_game_peers)),
                "days_since_last_appearance": days_since,
                "pitcher_strike_rate_28d": _rate(p_strikes, p_n),
                "pitcher_swstrike_rate_28d": _rate(p_sw, p_n),
                "pitcher_inplay_rate_28d": _rate(p_inplay, p_n),
                "batter_strike_rate_28d": _rate(b_strikes, b_n),
                "batter_inplay_rate_28d": _rate(b_inplay, b_n),
                "batter_ball_rate_28d": _rate(b_balls, b_n),
            }
        )
    return pd.DataFrame(
        out_rows,
        columns=["game_id", "at_bat_index", "pitch_number", *ROLLING_FORM_COLUMNS],
    )


def add_tier4_columns(pitches: pd.DataFrame, *, seed: int = 909) -> pd.DataFrame:
    """Attach deterministic synthetic Tier 4 (post-pitch) attributes.

    These are pure per-pitch measurements (release speed, plate location,
    movement, spin, pitch type) - no temporal component. They exist so the
    post-head feature assembly is exercised end-to-end and the pre/post
    boundary tests have a real Tier 4 payload to assert is ABSENT from the pre
    feature set and PRESENT in the post set.
    """
    rng = np.random.default_rng(seed)
    out = pitches.copy().reset_index(drop=True)
    n = len(out)
    pitch_types = np.array(["FF", "SL", "CH", "CU", "SI"])
    out["pitch_type"] = pitch_types[rng.integers(0, len(pitch_types), size=n)]
    out["release_speed_mph"] = rng.normal(90.0, 4.0, size=n).astype("float32")
    out["plate_x_in"] = rng.normal(0.0, 6.0, size=n).astype("float32")
    out["plate_z_in"] = rng.normal(24.0, 8.0, size=n).astype("float32")
    out["pfx_x_in"] = rng.normal(0.0, 5.0, size=n).astype("float32")
    out["pfx_z_in"] = rng.normal(8.0, 4.0, size=n).astype("float32")
    out["spin_rate_rpm"] = rng.normal(2200.0, 250.0, size=n).astype("float32")
    out["spin_axis_deg"] = rng.uniform(0.0, 360.0, size=n).astype("float32")
    out["release_pos_x_in"] = rng.normal(-12.0, 12.0, size=n).astype("float32")
    out["release_pos_z_in"] = rng.normal(70.0, 4.0, size=n).astype("float32")
    return out


def assemble_pitch_features(
    pitches: pd.DataFrame,
    fold: SyntheticFold,
    *,
    head: str = "pre",
    leak_current_row: bool = False,
    leak_te_full_window: bool = False,
    seed: int = 909,
) -> pd.DataFrame:
    """End-to-end pitch-head feature assembly through the REAL builder logic.

    Produces, for the fold's test-window pitches, the Tier 1 context, the Tier 2
    target encodings (via the real compute_te / apply_te), and the Tier 3
    rolling-form reference. For `head="post"` it also attaches Tier 4.

    Knobs (used by the detector self-tests, default OFF = the clean pipeline):
      * leak_current_row     -> Tier 3 window includes the pitch itself.
      * leak_te_full_window  -> Tier 2 TE is fit over train+test (the canonical
                                target leak), not the train window only.
    """
    if head not in ("pre", "post"):
        raise ValueError(f"head must be 'pre' or 'post', got {head!r}")

    train_mask = (pitches["game_date"] >= fold.train_start) & (
        pitches["game_date"] <= fold.train_end
    )
    test_mask = (pitches["game_date"] >= fold.test_start) & (pitches["game_date"] <= fold.test_end)
    train_df = cast(pd.DataFrame, pitches.loc[train_mask].copy())
    test_df = cast(pd.DataFrame, pitches.loc[test_mask].copy())

    te_source = pitches if leak_te_full_window else train_df
    pitcher_te = compute_te(te_source, entity_col="pitcher_id", label_col="label")
    batter_te = compute_te(te_source, entity_col="batter_id", label_col="label")
    prior = compute_prior(te_source, "label")

    encoded = apply_te(
        test_df, pitcher_te, entity_col="pitcher_id", column_prefix="pitcher", prior=prior
    )
    encoded = apply_te(
        encoded, batter_te, entity_col="batter_id", column_prefix="batter", prior=prior
    )

    # Tier 3 rolling form is computed over the FULL history (train + test) so a
    # test-window pitch can see its own pre-history; the per-row streaming cutoff
    # inside rolling_form_reference is what keeps it leakage-safe.
    rolling = rolling_form_reference(pitches, leak_current_row=leak_current_row)
    encoded = encoded.merge(rolling, on=list(_INGAME_ORDER), how="left")

    if head == "post":
        tier4 = add_tier4_columns(pitches, seed=seed)
        keep = [*_INGAME_ORDER, *TIER4_REFERENCE_COLUMNS]
        encoded = encoded.merge(cast(pd.DataFrame, tier4[keep]), on=list(_INGAME_ORDER), how="left")

    return cast(pd.DataFrame, encoded.reset_index(drop=True))


@pytest.fixture(scope="session")
def pitch_fold() -> SyntheticFold:
    """A pitch-head fold: 30-day train then a SHORT (12-day) test window.

    The test window is short on purpose - the pure-Python rolling-form
    reference is O(n^2) in pitches, so keeping the encoded test frame small
    keeps the four pitch-head categories well under the rule-10 <5min budget
    while the 30-day train window still gives the 28d rolling window warm-up.
    """
    return SyntheticFold(
        train_start=date(2024, 4, 1),
        train_end=date(2024, 4, 30),
        test_start=date(2024, 5, 1),
        test_end=date(2024, 5, 12),
    )


@pytest.fixture(scope="session")
def pitch_pitches() -> pd.DataFrame:
    """Smaller pitch fleet than `pitches` so the O(n^2) rolling reference is
    fast. 8 pitchers x 14 batters x 45 days x 4 pitches/day = 1,440 rows."""
    return synthetic_pitches(
        n_pitchers=8, n_batters=14, n_days=45, pitches_per_pitcher_per_day=4, seed=11
    )


# Label -> int map, identical to train_pre._LABEL_TO_INT (kept local to avoid
# importing the heavy lightgbm-carrying training module at collection time).
LABEL_TO_INT: dict[str, int] = {cls: i for i, cls in enumerate(LABEL_CLASSES)}


def to_model_frame(assembled: pd.DataFrame) -> pd.DataFrame:
    """Project an `assemble_pitch_features` frame onto the columns the real
    `train_pre.model_factory` consumes (PITCH_FEATURE_COLUMNS + integer label).

    The TE (Tier 2) and rolling-form (Tier 3) columns come straight from the real
    builder logic and carry the entity signal the shuffled-target test probes.
    The Tier 1 context + categorical-int columns and the two season-long std
    columns the tiny fixture cannot warm up are filled deterministically from the
    raw pitch fields (NaN for the std columns - LightGBM handles missing
    natively, exactly as the production loader leaves them for cold starts).
    This keeps the model input REAL on the columns under test while satisfying
    the factory's 31-column contract.
    """
    df = assembled.copy().reset_index(drop=True)
    # Tier 1 cheap context derived deterministically from the synthetic ids.
    df["count_balls"] = (df["pitch_number"] % 4).astype("int8")
    df["count_strikes"] = (df["pitch_number"] % 3).astype("int8")
    df["outs"] = (df["at_bat_index"] % 3).astype("int8")
    df["inning"] = ((df["at_bat_index"] % 9) + 1).astype("int8")
    df["base_state"] = (df["game_id"] % 8).astype("int8")
    df["score_diff"] = ((df["game_id"] % 7) - 3).astype("int16")
    df["dow"] = ((df["game_id"] % 7) + 1).astype("int8")
    df["pitcher_throws_int"] = (df["pitcher_id"] % 2).astype("int8")
    df["batter_stand_int"] = (df["batter_id"] % 2).astype("int8")
    df["park_id_int"] = (df["pitcher_id"] % 5).astype("int16")
    # Season-long std columns: NaN in a sub-2-month fixture (cold start). The
    # production loader leaves them Nullable; LightGBM routes NaN natively.
    df["pitcher_strike_rate_std"] = np.float32("nan")
    df["batter_inplay_rate_std"] = np.float32("nan")
    # Integer label.
    label_series = cast(pd.Series, df["label"])
    df["label"] = label_series.map(lambda c: LABEL_TO_INT[c]).astype("int64")
    return cast(pd.DataFrame, df)
