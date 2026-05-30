"""Enriched data loader using V012/V013 expanded Statcast columns.

Adds leakage-safe context features known BEFORE the pitch is thrown:
  - times_through_order (fatigue)
  - score_diff_live (real score, replaces the placeholder)
  - win_expectancy (leverage)
  - times_faced_today (matchup familiarity)
  - at_bat_number_in_game (workload)
  - catcher_id (for catcher embeddings)

Pitcher career biomechanics (leakage-safe averages):
  - pitcher_avg_extension
  - pitcher_avg_arm_angle

Post-pitch columns (effective_speed, release_extension, arm_angle,
pitch_zone) are pulled for sequence-token enrichment only — they are
known for PREVIOUS pitches in a pitcher's history but NOT for the
current pitch being predicted.
"""

from __future__ import annotations

import gc
import io
import subprocess
from typing import Final

import numpy as np
import pandas as pd

from bullpen_training.pitch_comparison.data import (
    OUTCOME_TO_INT,
    PITCH_TYPE_MAP,
    PITCH_TYPE_TO_INT,
    _compute_batter_career_stats,
    _compute_pitcher_career_stats,
)

# Leakage-safe context features (known before the pitch).
CONTEXT_FEATURE_COLS: Final[tuple[str, ...]] = (
    "times_through_order",
    "score_diff_live",
    "win_expectancy",
    "times_faced_today",
    "at_bat_number_in_game",
    "pitcher_avg_extension",
    "pitcher_avg_arm_angle",
)

# Leakage-safe lag/streak features derived from the PREVIOUS pitch only.
#   repeat_pitch_type    — length of the run of identical pitch types
#                          immediately preceding the current pitch (e.g. 3
#                          fastballs in a row -> 3). Higher run -> a pitcher
#                          is more likely to change it up next.
#   prev_pitch_type_int  — the previous pitch's mapped type (-1 if first).
#   prev_pitch_result_int— the previous pitch's outcome (-1 if first).
# All three are computed by shifting strictly backwards within (pitcher,
# game), so the current pitch's own type/outcome never leaks in.
STREAK_FEATURE_COLS: Final[tuple[str, ...]] = (
    "repeat_pitch_type",
    "prev_pitch_type_int",
    "prev_pitch_result_int",
)

_TSV_COLUMNS: Final[list[str]] = [
    "game_id", "at_bat_index", "pitch_number", "season",
    "pitcher_id", "batter_id", "catcher_id",
    "count_balls", "count_strikes", "outs", "inning",
    "base_state", "stand", "p_throws", "park_id",
    "pitch_type", "release_speed_mph", "description",
    "times_through_order", "score_diff_live", "win_expectancy",
    "times_faced_today", "at_bat_number_in_game",
    "effective_speed_mph", "release_extension_ft", "arm_angle_deg",
    "pitch_zone", "ab_total_pitches",
]


def _run_clickhouse(query: str, *, container: str = "bullpen-clickhouse") -> str:
    res = subprocess.run(
        ["docker", "exec", container, "clickhouse-client", "--query", query],
        check=True, capture_output=True, text=True,
    )
    return res.stdout


def _year_query(year: int, limit: int | None = None) -> str:
    limit_clause = f"LIMIT {limit}" if limit else ""
    return f"""
    SELECT
      game_id,
      at_bat_index,
      pitch_number,
      toYear(game_date) AS season,
      pitcher_id,
      batter_id,
      coalesce(catcher_id, 0) AS catcher_id,
      balls AS count_balls,
      strikes AS count_strikes,
      outs,
      inning,
      base_state,
      stand,
      p_throws,
      park_id,
      pitch_type,
      release_speed_mph,
      toString(description) AS description,
      coalesce(times_through_order, 0) AS times_through_order,
      coalesce(score_diff_live, 0) AS score_diff_live,
      coalesce(win_expectancy, 0.5) AS win_expectancy,
      coalesce(times_faced_today, 0) AS times_faced_today,
      coalesce(at_bat_number_in_game, 0) AS at_bat_number_in_game,
      coalesce(effective_speed_mph, 0) AS effective_speed_mph,
      coalesce(release_extension_ft, 0) AS release_extension_ft,
      coalesce(arm_angle_deg, 0) AS arm_angle_deg,
      coalesce(pitch_zone, 0) AS pitch_zone,
      max(pitch_number) OVER (
        PARTITION BY game_id, at_bat_index
      ) AS ab_total_pitches
    FROM pitches FINAL
    WHERE toYear(game_date) = {year}
      AND pitch_type != ''
      AND release_speed_mph IS NOT NULL
      AND toString(description) != 'unknown'
    ORDER BY game_date, game_id, at_bat_index, pitch_number
    {limit_clause}
    FORMAT TabSeparated
    """


def load_enriched_data(
    *,
    season_from: int,
    season_to: int,
    limit: int | None = None,
    container: str = "bullpen-clickhouse",
) -> pd.DataFrame:
    """Load pitches with expanded columns, year by year via TSV."""
    chunks: list[pd.DataFrame] = []
    total = 0
    for year in range(season_from, season_to + 1):
        per_year_limit = None
        if limit is not None:
            remaining = limit - total
            if remaining <= 0:
                break
            per_year_limit = remaining

        print(f"  loading {year}...", end="", flush=True)
        tsv = _run_clickhouse(
            _year_query(year, per_year_limit), container=container,
        )
        if not tsv.strip():
            print(" 0 rows")
            continue
        df = pd.read_csv(
            io.StringIO(tsv), sep="\t", header=None,
            names=_TSV_COLUMNS, na_values=["\\N"],
            dtype={
                "game_id": "int64", "at_bat_index": "int16",
                "pitch_number": "int8", "season": "int16",
                "pitcher_id": "int32", "batter_id": "int32",
                "catcher_id": "int32",
                "count_balls": "int8", "count_strikes": "int8",
                "outs": "int8", "inning": "int8", "base_state": "int8",
                "stand": "str", "p_throws": "str", "park_id": "str",
                "pitch_type": "str", "description": "str",
                "times_through_order": "int8",
                "score_diff_live": "int16",
                "times_faced_today": "int8",
                "at_bat_number_in_game": "int16",
                "pitch_zone": "int8",
                "ab_total_pitches": "int8",
            },
        )
        for fcol in [
            "release_speed_mph", "win_expectancy",
            "effective_speed_mph", "release_extension_ft", "arm_angle_deg",
        ]:
            df[fcol] = df[fcol].astype("float32")
        n = len(df)
        total += n
        print(f" {n:,} rows (total {total:,})", flush=True)
        chunks.append(df)
        del tsv
        gc.collect()

    if not chunks:
        return pd.DataFrame()
    result = pd.concat(chunks, ignore_index=True)
    del chunks
    gc.collect()
    return result


def _add_streak_features(df: pd.DataFrame) -> pd.DataFrame:
    """Add leakage-safe lag/streak features (shifted strictly backwards).

    Assumes ``df`` rows are in global chronological order (the year-by-year
    TSV load guarantees this), so a per-(pitcher, game) shift looks at the
    genuinely preceding pitch. The current pitch's own type/outcome is never
    used.
    """
    keys = [df["pitcher_id"], df["game_id"]]
    grp = df.groupby(["pitcher_id", "game_id"], sort=False)

    pt = df["pitch_type_int"]
    prev_type = grp["pitch_type_int"].shift(1)
    prev_outcome = grp["outcome_int"].shift(1)

    # Inclusive run length of identical consecutive pitch types, then shift
    # back one so the current pitch sees the run that ended on the prior pitch.
    is_new_run = (pt != prev_type) | prev_type.isna()
    block_id = is_new_run.groupby(keys).cumsum()
    run_len_incl = df.groupby(
        [df["pitcher_id"], df["game_id"], block_id], sort=False,
    ).cumcount() + 1
    repeat = run_len_incl.groupby(keys).shift(1).fillna(0)

    df["repeat_pitch_type"] = repeat.astype(np.int16)
    df["prev_pitch_type_int"] = prev_type.fillna(-1).astype(np.int8)
    df["prev_pitch_result_int"] = prev_outcome.fillna(-1).astype(np.int8)
    return df


def _compute_pitcher_biomech(
    df: pd.DataFrame, train_mask: np.ndarray,
) -> pd.DataFrame:
    """Per-pitcher career biomechanics averages (leakage-safe)."""
    train = df.loc[train_mask]
    # Only use rows where biomech is actually measured (>0).
    stats = train.groupby("pitcher_id").agg(
        pitcher_avg_extension=("release_extension_ft", "mean"),
        pitcher_avg_arm_angle=("arm_angle_deg", "mean"),
    ).reset_index()
    stats["pitcher_avg_extension"] = stats[
        "pitcher_avg_extension"
    ].astype("float32")
    stats["pitcher_avg_arm_angle"] = stats[
        "pitcher_avg_arm_angle"
    ].astype("float32")
    return stats


def prepare_enriched_datasets(
    df: pd.DataFrame,
    *,
    train_years: tuple[int, ...],
    val_years: tuple[int, ...],
    test_years: tuple[int, ...],
    add_streak: bool = False,
) -> tuple[pd.DataFrame, pd.DataFrame, pd.DataFrame]:
    """Prepare splits with enriched context features. Modifies df in place.

    ``add_streak`` controls the lag/streak features (`repeat_pitch_type`,
    `prev_pitch_type_int`, `prev_pitch_result_int`). Off by default so the
    best-architecture runner (catcher-aware transformer + context) does not
    compute them; the streak experiment opts in with ``add_streak=True``.
    """
    df["pitcher_throws_int"] = (df["p_throws"] == "R").astype(np.int8)
    df["batter_stand_int"] = (df["stand"] == "R").astype(np.int8)
    park_ids = sorted(df["park_id"].unique())
    park_map = {p: i for i, p in enumerate(park_ids)}
    df["park_id_int"] = (
        df["park_id"].map(park_map).fillna(-1).astype(np.int16)
    )
    df["pitch_number_in_ab"] = df["pitch_number"].astype(np.int8)

    df["pitch_type_mapped"] = (
        df["pitch_type"].map(PITCH_TYPE_MAP).fillna("OTHER")
    )
    df["pitch_type_int"] = (
        df["pitch_type_mapped"].map(PITCH_TYPE_TO_INT).astype(np.int8)
    )

    # Outcome int for sequence-token construction.
    df["outcome_int"] = df["description"].map(OUTCOME_TO_INT)
    df = df.dropna(subset=["outcome_int"])
    df["outcome_int"] = df["outcome_int"].astype(np.int8)

    # Lag/streak features (must run while df is still chronologically
    # ordered and before any reindexing). Opt-in: only the streak experiment
    # sets add_streak=True; the best-architecture runner skips them.
    if add_streak:
        df = _add_streak_features(df)

    df["ab_total_pitches"] = pd.to_numeric(
        df["ab_total_pitches"], errors="coerce",
    )

    season = df["season"].values
    train_mask = np.isin(season, train_years)

    # Standard career pitch-mix + batter stats (so output is a superset
    # of the base FEATURE_COLS used by the hybrid model).
    pitcher_stats = _compute_pitcher_career_stats(df, train_mask)
    batter_stats = _compute_batter_career_stats(df, train_mask)
    df = df.merge(pitcher_stats, on="pitcher_id", how="left")
    df = df.merge(batter_stats, on="batter_id", how="left")

    # Pitcher career biomechanics from training data only.
    biomech = _compute_pitcher_biomech(df, train_mask)
    df = df.merge(biomech, on="pitcher_id", how="left")

    fill_cols = [
        "pitcher_avg_velo", "pitcher_ff_pct", "pitcher_sl_pct",
        "pitcher_ch_pct", "pitcher_cu_pct",
        "batter_ball_rate", "batter_inplay_rate", "batter_strikeout_rate",
        "pitcher_avg_extension", "pitcher_avg_arm_angle",
    ]
    for col in fill_cols:
        fill = float(df.loc[train_mask, col].mean())
        df[col] = df[col].fillna(fill)

    season = df["season"].values
    train_mask = np.isin(season, train_years)
    val_mask = np.isin(season, val_years)
    test_mask = np.isin(season, test_years)

    return (
        df.loc[train_mask], df.loc[val_mask], df.loc[test_mask],
    )


__all__ = (
    "CONTEXT_FEATURE_COLS",
    "STREAK_FEATURE_COLS",
    "load_enriched_data",
    "prepare_enriched_datasets",
)
