"""Shared data loader for pitch model comparison.

Pulls directly from the pitches table, computes features and all 4
prediction targets:
  1. pitch_type (8-class: FF, SI, SL, CH, CU, FC, ST, other)
  2. release_speed_mph (regression)
  3. pitch_outcome (6-class: ball, called_strike, swinging_strike, foul, in_play, hbp)
  4. ab_total_pitches (regression — total pitches in the at-bat)

Memory-safe: loads one year at a time via TSV, never holds the full
raw text in memory.
"""

from __future__ import annotations

import gc
import io
import subprocess
from typing import Final

import numpy as np
import pandas as pd

PITCH_TYPE_CLASSES: Final[tuple[str, ...]] = (
    "FF", "SI", "SL", "CH", "CU", "FC", "ST", "OTHER",
)
PITCH_TYPE_MAP: Final[dict[str, str]] = {
    "FF": "FF", "SI": "SI", "SL": "SL", "CH": "CH",
    "CU": "CU", "CB": "CU", "KC": "CU",
    "FC": "FC", "ST": "ST", "SV": "ST",
    "FS": "CH", "KN": "OTHER", "EP": "OTHER",
    "FA": "FF", "FO": "OTHER", "CS": "CU",
    "SC": "OTHER", "IN": "OTHER", "PO": "OTHER",
    "UN": "OTHER", "AB": "OTHER",
}
PITCH_TYPE_TO_INT: Final[dict[str, int]] = {
    c: i for i, c in enumerate(PITCH_TYPE_CLASSES)
}

OUTCOME_CLASSES: Final[tuple[str, ...]] = (
    "ball", "called_strike", "swinging_strike", "foul", "in_play", "hit_by_pitch",
)
OUTCOME_TO_INT: Final[dict[str, int]] = {
    c: i for i, c in enumerate(OUTCOME_CLASSES)
}

FEATURE_COLS: Final[tuple[str, ...]] = (
    "count_balls", "count_strikes", "outs", "inning",
    "base_state", "pitch_number_in_ab",
    "pitcher_throws_int", "batter_stand_int", "park_id_int",
    "pitcher_avg_velo", "pitcher_ff_pct", "pitcher_sl_pct",
    "pitcher_ch_pct", "pitcher_cu_pct",
    "batter_ball_rate", "batter_inplay_rate", "batter_strikeout_rate",
)

_TSV_COLUMNS: Final[list[str]] = [
    "game_id", "at_bat_index", "pitch_number", "season",
    "pitcher_id", "batter_id", "count_balls", "count_strikes",
    "outs", "inning", "base_state", "stand", "p_throws",
    "park_id", "pitch_type", "release_speed_mph", "description",
    "ab_total_pitches",
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


def load_pitch_data(
    *,
    season_from: int,
    season_to: int,
    limit: int | None = None,
    container: str = "bullpen-clickhouse",
) -> pd.DataFrame:
    """Load pitches year by year via TSV to stay memory-safe."""
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
                "count_balls": "int8", "count_strikes": "int8",
                "outs": "int8", "inning": "int8", "base_state": "int8",
                "stand": "str", "p_throws": "str", "park_id": "str",
                "pitch_type": "str", "description": "str",
                "ab_total_pitches": "int8",
            },
        )
        df["release_speed_mph"] = df["release_speed_mph"].astype("float32")
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


def _compute_pitcher_career_stats(
    df: pd.DataFrame, train_mask: np.ndarray,
) -> pd.DataFrame:
    train = df.loc[train_mask]

    pitcher_stats = train.groupby("pitcher_id").agg(
        pitcher_avg_velo=("release_speed_mph", "mean"),
        total=("pitcher_id", "size"),
    ).reset_index()

    type_counts = (
        train.groupby(["pitcher_id", "pitch_type_mapped"])
        .size()
        .unstack(fill_value=0)
    )
    for pt, col_name in [
        ("FF", "pitcher_ff_pct"), ("SL", "pitcher_sl_pct"),
        ("CH", "pitcher_ch_pct"), ("CU", "pitcher_cu_pct"),
    ]:
        if pt in type_counts.columns:
            pitcher_stats[col_name] = (
                type_counts[pt].values / pitcher_stats["total"].values
            ).astype("float32")
        else:
            pitcher_stats[col_name] = np.float32(0.0)

    pitcher_stats["pitcher_avg_velo"] = pitcher_stats[
        "pitcher_avg_velo"
    ].astype("float32")
    return pitcher_stats[[
        "pitcher_id", "pitcher_avg_velo",
        "pitcher_ff_pct", "pitcher_sl_pct",
        "pitcher_ch_pct", "pitcher_cu_pct",
    ]]


def _compute_batter_career_stats(
    df: pd.DataFrame, train_mask: np.ndarray,
) -> pd.DataFrame:
    train = df.loc[train_mask]
    batter_stats = train.groupby("batter_id").agg(
        total=("batter_id", "size"),
    ).reset_index()

    outcome_counts = (
        train.groupby(["batter_id", "description"])
        .size()
        .unstack(fill_value=0)
    )
    for outcome, col_name in [
        ("ball", "batter_ball_rate"),
        ("in_play", "batter_inplay_rate"),
    ]:
        if outcome in outcome_counts.columns:
            batter_stats[col_name] = (
                outcome_counts[outcome].values
                / batter_stats["total"].values
            ).astype("float32")
        else:
            batter_stats[col_name] = np.float32(0.0)

    strike_cols = [
        c for c in ["called_strike", "swinging_strike"]
        if c in outcome_counts.columns
    ]
    if strike_cols:
        batter_stats["batter_strikeout_rate"] = (
            outcome_counts[strike_cols].sum(axis=1).values
            / batter_stats["total"].values
        ).astype("float32")
    else:
        batter_stats["batter_strikeout_rate"] = np.float32(0.0)

    return batter_stats[[
        "batter_id", "batter_ball_rate",
        "batter_inplay_rate", "batter_strikeout_rate",
    ]]


def prepare_datasets(
    df: pd.DataFrame,
    *,
    train_years: tuple[int, ...],
    val_years: tuple[int, ...],
    test_years: tuple[int, ...],
) -> tuple[pd.DataFrame, pd.DataFrame, pd.DataFrame]:
    """Prepare train/val/test DataFrames with features and targets.

    Modifies df in place to save memory — caller should not reuse it.
    """
    # Encode categoricals in place.
    df["pitcher_throws_int"] = (df["p_throws"] == "R").astype(np.int8)
    df["batter_stand_int"] = (df["stand"] == "R").astype(np.int8)
    park_ids = sorted(df["park_id"].unique())
    park_map = {p: i for i, p in enumerate(park_ids)}
    df["park_id_int"] = (
        df["park_id"].map(park_map).fillna(-1).astype(np.int16)
    )
    df["pitch_number_in_ab"] = df["pitch_number"].astype(np.int8)

    # Map pitch types to 8 classes.
    df["pitch_type_mapped"] = (
        df["pitch_type"].map(PITCH_TYPE_MAP).fillna("OTHER")
    )
    df["pitch_type_int"] = (
        df["pitch_type_mapped"].map(PITCH_TYPE_TO_INT).astype(np.int8)
    )

    # Map outcomes.
    df["outcome_int"] = df["description"].map(OUTCOME_TO_INT)
    df.dropna(subset=["outcome_int"], inplace=True)
    df["outcome_int"] = df["outcome_int"].astype(np.int8)

    # Drop raw string columns to save memory.
    df.drop(
        columns=["stand", "p_throws", "park_id", "pitch_type"],
        inplace=True, errors="ignore",
    )
    gc.collect()

    # Temporal split masks.
    season = df["season"].values
    train_mask = np.isin(season, train_years)
    val_mask = np.isin(season, val_years)
    test_mask = np.isin(season, test_years)

    # Compute career stats from training data only.
    print("  computing pitcher career stats...", flush=True)
    pitcher_stats = _compute_pitcher_career_stats(df, train_mask)
    print("  computing batter career stats...", flush=True)
    batter_stats = _compute_batter_career_stats(df, train_mask)

    df = df.merge(pitcher_stats, on="pitcher_id", how="left")
    del pitcher_stats
    df = df.merge(batter_stats, on="batter_id", how="left")
    del batter_stats
    gc.collect()

    # Fill NaN for unseen pitchers/batters with global means.
    # Recompute mask after merge (index unchanged but be safe).
    season = df["season"].values
    train_mask = np.isin(season, train_years)

    for col in [
        "pitcher_avg_velo", "pitcher_ff_pct", "pitcher_sl_pct",
        "pitcher_ch_pct", "pitcher_cu_pct",
    ]:
        fill = float(df.loc[train_mask, col].mean())
        df[col] = df[col].fillna(fill)
    for col in [
        "batter_ball_rate", "batter_inplay_rate",
        "batter_strikeout_rate",
    ]:
        fill = float(df.loc[train_mask, col].mean())
        df[col] = df[col].fillna(fill)

    val_mask = np.isin(season, val_years)
    test_mask = np.isin(season, test_years)

    train_df = df.loc[train_mask]
    val_df = df.loc[val_mask]
    test_df = df.loc[test_mask]
    return train_df, val_df, test_df


__all__ = (
    "FEATURE_COLS",
    "OUTCOME_CLASSES",
    "OUTCOME_TO_INT",
    "PITCH_TYPE_CLASSES",
    "PITCH_TYPE_TO_INT",
    "load_pitch_data",
    "prepare_datasets",
)
