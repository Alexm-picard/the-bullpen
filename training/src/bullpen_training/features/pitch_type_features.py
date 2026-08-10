"""Pitch-TYPE feature loader (Phase 1a).

Reads the two window-SQL files (state+SEQ, arsenal) for one fold's test window and
joins them on the natural pitch key. Mirrors `tier_3_form.load_tier3_for_window`:
per-year chunking to keep the ClickHouse scan bounded, `_bind` for the date params,
`_read` for the SQL text.

Phase 1a scope: this loader exists so the Phase 1b leakage tests can run the REAL
streaming-cutoff SQL against a ClickHouse fixture (future-contamination on ars_*,
boundary/ID-consistency on prev1_pt_i, shuffled-target, calendar-date trace). The
fold-materialization + INSERT orchestration into `pitch_type_features` (V029) and the
trainer are Phase 2 - deliberately NOT built here.
"""

from __future__ import annotations

from datetime import date
from pathlib import Path
from typing import Any, cast

import pandas as pd
from clickhouse_driver import Client

from bullpen_training.eval.leakage_guards import refuse_holdout
from bullpen_training.features.tier_1_2 import _bind, _read

SQL_DIR = Path(__file__).resolve().parent / "sql"

# Career-expanding arsenal floor (report section 2): reach back to the pitcher's first
# pitch, bounded at the 2015 training-corpus start. Rule 13: never 2026 - the caller
# passes 2015-2025 fold windows, and this floor is a fixed 2015-01-01.
CORPUS_START: date = date(2015, 1, 1)

PK_JOIN: tuple[str, ...] = ("game_id", "at_bat_index", "pitch_number")

# Positional column names returned by compute_pitch_type_state.sql (key first). MUST
# match that file's final SELECT order exactly (clickhouse-driver returns tuples).
STATE_COLUMNS: tuple[str, ...] = (
    *PK_JOIN,
    "game_date",
    "pitcher_id",
    "batter_id",
    "label_pitch_type",
    "balls",
    "strikes",
    "outs",
    "inning",
    "base_state",
    "stand",
    "p_throws",
    "park_id",
    "times_through_order",
    "at_bat_number_in_game",
    "times_faced_today",
    "prev1_pt_i",
    "prev2_pt_i",
    "prev1_missing",
    "pitches_into_outing",
)

# Positional column names returned by compute_pitch_type_arsenal.sql (key first).
ARSENAL_COLUMNS: tuple[str, ...] = (
    *PK_JOIN,
    "ars_FF",
    "ars_SI",
    "ars_FC",
    "ars_SL",
    "ars_CU",
    "ars_CH",
    "ars_OFF",
    "ars_FF_by_count",
    "pitcher_prior_n",
)


def _run_year_chunked(
    client: Client,
    sql_path: Path,
    columns: tuple[str, ...],
    *,
    window_start: date,
    window_end: date,
    extra_params: dict[str, Any] | None = None,
) -> pd.DataFrame:
    """Run one window-SQL file per calendar year over [window_start, window_end].

    Per-year chunking keeps the CH scan bounded (the arsenal file's career scan back
    to CORPUS_START would otherwise stack the whole corpus in one query). Chunking is
    EXACT for both files: the state/SEQ file is window-local, and the arsenal frame for
    any row depends only on earlier rows, all inside that chunk's [corpus_start, chunk_end]
    scan (see the SQL header comments).
    """
    cols = list(columns)
    chunks: list[pd.DataFrame] = []
    for year in range(window_start.year, window_end.year + 1):
        chunk_start = max(date(year, 1, 1), window_start)
        chunk_end = min(date(year, 12, 31), window_end)
        params: dict[str, Any] = {"test_start": chunk_start, "test_end": chunk_end}
        if extra_params:
            params.update(extra_params)
        sql = _bind(_read(sql_path), params)
        rows = cast(list[tuple[Any, ...]], client.execute(sql))
        if rows:
            chunks.append(pd.DataFrame(rows, columns=cols))
    if not chunks:
        return pd.DataFrame(columns=cols)
    return pd.concat(chunks, ignore_index=True)


def load_pitch_type_features_for_window(
    client: Client,
    *,
    test_start: date,
    test_end: date,
    corpus_start: date = CORPUS_START,
) -> pd.DataFrame:
    """Pull the pitch-type feature rows for [test_start, test_end].

    Returns one row per labeled pitch: the natural key + game_date/pitcher_id/batter_id
    + the y7 label + all 24 model features (Tier S raw + ARS + SEQ). The categorical
    encodings (stand_i/throws_i/park_i) are NOT applied here - that is a contract-driven
    transform the Phase 2 trainer/serving path performs; this loader returns the raw
    stand/p_throws/park_id columns, mirroring load_tier3_for_window.
    """
    # Rule 13 (defense-in-depth): 2026 is holdout-only. The ARS base CTE reaches back to
    # corpus_start but FORWARD to test_end, so an unclamped >=2026 test_end would scan the
    # holdout. The fold driver also fences this, but a loader that can touch the box must
    # refuse it itself (there are no callers yet - this guards Phase 2 from the start).
    refuse_holdout(season_from=corpus_start.year, season_to=test_end.year)
    state = _run_year_chunked(
        client,
        SQL_DIR / "compute_pitch_type_state.sql",
        STATE_COLUMNS,
        window_start=test_start,
        window_end=test_end,
    )
    arsenal = _run_year_chunked(
        client,
        SQL_DIR / "compute_pitch_type_arsenal.sql",
        ARSENAL_COLUMNS,
        window_start=test_start,
        window_end=test_end,
        extra_params={"corpus_start": corpus_start},
    )
    return state.merge(arsenal, on=list(PK_JOIN), how="left")


__all__ = (
    "ARSENAL_COLUMNS",
    "CORPUS_START",
    "PK_JOIN",
    "STATE_COLUMNS",
    "load_pitch_type_features_for_window",
)
