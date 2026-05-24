"""Tier 4 post-pitch features (Phase 2b.1).

Per-pitch attributes that are only available AFTER the pitch is thrown
(or during ball flight): pitch_type, release_speed, plate location,
movement vectors (pfx_x/z), spin (rate + axis), release position.

These columns are read by `pitch_outcome_post` ONLY. The pre-pitch
pipeline deliberately does not list them — using any Tier 4 column in
the pre-pitch feature_pipeline.json is catastrophic leakage and is
guarded by the CI leakage tests + the registry's schema-hash check.

Build strategy: the columns live on the V005 `features` table (V010
adds them). Population is a pure join from `pitches` keyed on
(game_id, at_bat_index, pitch_number) — no windowing, no temporal
cutoff, no train/val/test split logic. The unified
`tier_3_form.build_fold_full` calls `load_tier4_for_window` and
LEFT-joins the result into the per-fold rows it writes.

Data availability: pybaseball's pre-2024 historical pulls do NOT carry
pfx_x/pfx_z/release_spin_rate/spin_axis (V008 added them to the raw
schema; the 2024+ re-pull captures them). Pre-2024 rows therefore land
with NULL for those columns. LightGBM handles missing natively; the
post-head model's effective Tier 4 signal grows monotonically with
year. Documented in 2b.1 status log and in `docs/decisions.md`.
"""

from __future__ import annotations

from datetime import date
from pathlib import Path
from typing import Any, cast

import pandas as pd
from clickhouse_driver import Client

TIER4_COLUMNS: tuple[str, ...] = (
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

PK_JOIN: tuple[str, ...] = ("game_id", "at_bat_index", "pitch_number")

SQL_DIR = Path(__file__).resolve().parent / "sql"


def load_tier4_for_window(
    client: Client, *, test_start: date, test_end: date, chunk_by_year: bool = True
) -> pd.DataFrame:
    """Load Tier 4 columns for every pitch in the window.

    Chunked by year by default for memory parity with the Tier 3 loader
    (the ClickHouse container caps at 8 GiB; full-span SELECTs OOM once
    the window crosses ~6M rows).
    """
    cols = list(PK_JOIN) + list(TIER4_COLUMNS)
    select = ",\n    ".join(["game_id", "at_bat_index", "pitch_number", *TIER4_COLUMNS])

    chunks: list[pd.DataFrame] = []
    iter_years = (
        range(test_start.year, test_end.year + 1)
        if chunk_by_year
        else [test_start.year]  # single chunk
    )
    for year in iter_years:
        chunk_start = max(date(year, 1, 1), test_start) if chunk_by_year else test_start
        chunk_end = min(date(year, 12, 31), test_end) if chunk_by_year else test_end
        sql = f"""
            SELECT
                {select}
            FROM pitches FINAL
            WHERE game_date BETWEEN '{chunk_start}' AND '{chunk_end}'
            ORDER BY game_date, game_id, at_bat_index, pitch_number
        """
        rows = cast(list[tuple[Any, ...]], client.execute(sql))
        if rows:
            chunks.append(pd.DataFrame(rows, columns=cols))
    if not chunks:
        return pd.DataFrame(columns=cols)
    return pd.concat(chunks, ignore_index=True)


def merge_tier4(features_df: pd.DataFrame, tier4_df: pd.DataFrame) -> pd.DataFrame:
    """LEFT-join Tier 4 onto a features DataFrame keyed by PK_JOIN.

    Rows without a Tier 4 match (shouldn't happen — every features row
    originated from a `pitches` row) keep NULL/NaN. The merge preserves
    features_df's row order and column order, with Tier 4 columns appended.
    """
    merged = features_df.merge(tier4_df, on=list(PK_JOIN), how="left")
    # `pitch_type` is LowCardinality(String) on the wire; pandas object NaN
    # crashes clickhouse-driver's serializer. Fill empty string for missing.
    merged["pitch_type"] = merged["pitch_type"].fillna("").astype(str)
    return cast(pd.DataFrame, merged)


__all__ = ("PK_JOIN", "TIER4_COLUMNS", "load_tier4_for_window", "merge_tier4")
