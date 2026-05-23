"""Statcast historical pull → ClickHouse `raw_statcast`.

Phase 1.1 — see docs/plans/phase-1-vertical-slice/1.1-statcast-historical-pull-2024.md.

Pulls one regular-season month at a time from pybaseball to keep peak memory
bounded (Risk Register G12). Idempotent: each month's partition is dropped
before being re-loaded, so re-running the same `--season N` leaves the row
count unchanged.

Data source: MLB Statcast via pybaseball. Non-commercial research use only.
See Risk Register I7.

Usage:
    uv run python -m bullpen_training.ingest.statcast_pull --season 2024
    uv run python -m bullpen_training.ingest.statcast_pull --season 2024 \\
        --start-month 4 --end-month 9
"""

from __future__ import annotations

import logging
import time
from calendar import monthrange
from datetime import date
from typing import Any, cast

import click
import pandas as pd
import pybaseball

from bullpen_training.ingest.assertions import (
    AssertionFailure,
    assert_no_null_pks,
    assert_row_count_in_range,
)
from bullpen_training.ingest.clickhouse_client import (
    ClickHouseSettings,
    insert_dataframe,
    make_client,
)
from bullpen_training.ingest.migrations import apply_migrations
from bullpen_training.logging_config import configure_logging, get_logger

log = get_logger(__name__)

# Order matches the V002 migration. Used both for the INSERT column list
# and for `normalize_columns` to enforce the contract.
RAW_STATCAST_COLUMNS: tuple[str, ...] = (
    "game_pk",
    "game_date",
    "game_type",
    "home_team",
    "away_team",
    "at_bat_index",
    "pitch_number",
    "pitcher",
    "batter",
    "stand",
    "p_throws",
    "balls",
    "strikes",
    "inning",
    "inning_topbot",
    "outs_when_up",
    "on_1b",
    "on_2b",
    "on_3b",
    "pitch_type",
    "release_speed",
    "release_pos_x",
    "release_pos_z",
    "plate_x",
    "plate_z",
    "sz_top",
    "sz_bot",
    "description",
    "events",
    "type",
    "bb_type",
    "launch_speed",
    "launch_angle",
    "hit_distance_sc",
    "hc_x",
    "hc_y",
)

PK_COLUMNS: tuple[str, ...] = ("game_pk", "at_bat_index", "pitch_number")

_LOW_CARDINALITY_STR_COLUMNS: tuple[str, ...] = (
    "game_type",
    "home_team",
    "away_team",
    "stand",
    "p_throws",
    "inning_topbot",
    "pitch_type",
    "description",
    "events",
    "type",
    "bb_type",
)

_INTEGER_NULLABLE_COLUMNS: tuple[str, ...] = (
    "balls",
    "strikes",
    "inning",
    "outs_when_up",
    "on_1b",
    "on_2b",
    "on_3b",
)

_FLOAT_NULLABLE_COLUMNS: tuple[str, ...] = (
    "release_speed",
    "release_pos_x",
    "release_pos_z",
    "plate_x",
    "plate_z",
    "sz_top",
    "sz_bot",
    "launch_speed",
    "launch_angle",
    "hit_distance_sc",
    "hc_x",
    "hc_y",
)

# Pybaseball's column names differ from the V002 schema in a few places.
# Keep the schema names canonical (matches downstream feature pipeline) and
# rename source columns at ingest time.
SOURCE_ALIASES: dict[str, str] = {
    "at_bat_index": "at_bat_number",
}

# Regular-season month bounds. Postseason ignored in v1 (filter by game_type='R'
# in cleaning step 1.2; the raw layer keeps every row pybaseball returns).
DEFAULT_START_MONTH = 3
DEFAULT_END_MONTH = 11

# Expected regular-season row count per published Statcast totals
# (~700K rows / 2,430 games / 162 games-per-team * 30 teams). Used as the
# end-of-run sanity assertion (±5%).
EXPECTED_REGULAR_SEASON_ROWS = 700_000


def month_window(season: int, month: int) -> tuple[date, date]:
    """Return (first_day, last_day) inclusive for the given calendar month."""
    if not 1 <= month <= 12:
        raise ValueError(f"month out of range: {month}")
    _, last = monthrange(season, month)
    return date(season, month, 1), date(season, month, last)


def normalize_columns(df: pd.DataFrame) -> pd.DataFrame:
    """Project pybaseball output onto the V002 schema and coerce dtypes.

    Missing source columns emit a warning and are filled with NaN — that way
    a pybaseball rename doesn't KeyError mid-pull. Extra source columns are
    silently dropped (we'll add them to V00N later if we ever need them).

    `SOURCE_ALIASES` maps a V002 column to the pybaseball name when they
    differ (pybaseball uses `at_bat_number`; the schema and downstream
    cleaning use `at_bat_index`).
    """
    out = pd.DataFrame()
    for col in RAW_STATCAST_COLUMNS:
        source_col = SOURCE_ALIASES.get(col, col)
        if source_col in df.columns:
            out[col] = df[source_col]
        else:
            log.warning("missing column in source", target=col, source=source_col)
            out[col] = pd.NA

    # game_date arrives as object/string in some pybaseball versions; coerce.
    out["game_date"] = pd.to_datetime(out["game_date"], errors="coerce").dt.date  # type: ignore[union-attr]

    # ID columns must be plain Python ints (clickhouse-driver rejects pandas
    # nullable ints when writing UInt*). Coerce via float→int through pd.NA.
    for col in ("game_pk", "pitcher", "batter"):
        out[col] = _coerce_uint(cast(pd.Series, out[col]))

    out["at_bat_index"] = _coerce_uint(cast(pd.Series, out["at_bat_index"]))
    out["pitch_number"] = _coerce_uint(cast(pd.Series, out["pitch_number"]))

    for col in _INTEGER_NULLABLE_COLUMNS:
        out[col] = _coerce_uint(cast(pd.Series, out[col]), allow_null=True)

    for col in _FLOAT_NULLABLE_COLUMNS:
        out[col] = pd.to_numeric(out[col], errors="coerce").astype("float32")  # type: ignore[union-attr]

    for col in _LOW_CARDINALITY_STR_COLUMNS:
        out[col] = out[col].fillna("").astype(str)

    return cast(pd.DataFrame, out[list(RAW_STATCAST_COLUMNS)])


def _coerce_uint(series: pd.Series, *, allow_null: bool = False) -> pd.Series:
    """Coerce a numeric series to ints / None, tolerating NaN.

    Returns an object-dtype Series so None values survive subsequent
    DataFrame assignment (a float-dtype column would re-coerce None → NaN
    and break the clickhouse-driver Nullable contract).
    """
    numeric: pd.Series = pd.to_numeric(series, errors="coerce")  # type: ignore[assignment]

    def _one(v: Any) -> Any:
        if pd.isna(v):
            return None if allow_null else 0
        return int(v)  # type: ignore[arg-type]

    return pd.Series([_one(v) for v in numeric], dtype=object, index=numeric.index)


def _drop_partition(client: Any, season: int, month: int) -> None:
    partition = f"{season}{month:02d}"
    client.execute(f"ALTER TABLE raw_statcast DROP PARTITION '{partition}'")


def _pull_month_with_retry(start: date, end: date, *, attempts: int = 3) -> pd.DataFrame:
    """Wrap pybaseball.statcast in retry-with-backoff (G12 / known flakiness)."""
    backoff_s = 30
    last_err: Exception | None = None
    for attempt in range(1, attempts + 1):
        try:
            return pybaseball.statcast(start_dt=str(start), end_dt=str(end))
        except Exception as exc:  # — pybaseball raises wide
            last_err = exc
            log.warning(
                "statcast pull failed; retrying",
                attempt=attempt,
                attempts=attempts,
                backoff_s=backoff_s,
                error=str(exc),
            )
            if attempt < attempts:
                time.sleep(backoff_s)
                backoff_s *= 2
    assert last_err is not None
    raise last_err


def run_pull(
    season: int,
    *,
    start_month: int = DEFAULT_START_MONTH,
    end_month: int = DEFAULT_END_MONTH,
    settings: ClickHouseSettings | None = None,
    chunk_size: int = 50_000,
) -> dict[str, Any]:
    """Programmatic entrypoint. Returns a summary dict."""
    client = make_client(settings)
    apply_migrations(client)

    total_rows = 0
    chunks: list[dict[str, Any]] = []
    started = time.time()

    for month in range(start_month, end_month + 1):
        start, end = month_window(season, month)
        log.info(
            "pulling chunk",
            season=season,
            month=month,
            start=str(start),
            end=str(end),
        )
        chunk_started = time.time()
        try:
            df = _pull_month_with_retry(start, end)
        except Exception as exc:
            log.error("chunk failed after retries", month=month, error=str(exc))
            chunks.append({"month": month, "rows": 0, "status": "failed"})
            continue

        if df.empty:
            log.warning("empty chunk", month=month, reason="pybaseball returned 0 rows")
            chunks.append({"month": month, "rows": 0, "status": "empty"})
            continue

        normalized = normalize_columns(df)
        _drop_partition(client, season, month)
        rows = insert_dataframe(
            client,
            "raw_statcast",
            normalized,
            columns=RAW_STATCAST_COLUMNS,
            chunk_size=chunk_size,
        )
        elapsed = time.time() - chunk_started
        total_rows += rows
        chunks.append(
            {
                "month": month,
                "rows": rows,
                "status": "ok",
                "elapsed_s": round(elapsed, 2),
            }
        )
        log.info("inserted chunk", month=month, rows=rows, elapsed_s=round(elapsed, 2))

    # Raw layer keeps spring training (game_type='S') and postseason (P/D/L/W),
    # but the ~700K acceptance count only covers the 162-game regular season.
    # Cleaning into `pitches` (Phase 1.2) filters to game_type='R' permanently.
    season_where_all = f"toYear(game_date) = {season}"
    season_where_regular = f"{season_where_all} AND game_type = 'R'"
    try:
        assert_row_count_in_range(
            client,
            table="raw_statcast",
            where=season_where_regular,
            expected=EXPECTED_REGULAR_SEASON_ROWS,
            tol_pct=5.0,
        )
        assert_no_null_pks(
            client,
            table="raw_statcast",
            pk_columns=list(PK_COLUMNS),
            where=season_where_all,
        )
        assertion_status = "passed"
    except AssertionFailure as exc:
        log.error("post-pull assertion failed", error=str(exc))
        assertion_status = "failed"

    total_elapsed = round(time.time() - started, 2)
    summary = {
        "season": season,
        "total_rows": total_rows,
        "total_elapsed_s": total_elapsed,
        "chunks": chunks,
        "assertions": assertion_status,
    }
    log.info("pull complete", **summary)
    return summary


@click.command()
@click.option("--season", type=int, required=True, help="Season year (e.g. 2024).")
@click.option(
    "--start-month",
    type=int,
    default=DEFAULT_START_MONTH,
    show_default=True,
    help="First calendar month to pull (1-12).",
)
@click.option(
    "--end-month",
    type=int,
    default=DEFAULT_END_MONTH,
    show_default=True,
    help="Last calendar month to pull (1-12), inclusive.",
)
@click.option(
    "--chunk-size",
    type=int,
    default=50_000,
    show_default=True,
    help="Rows per INSERT block sent to ClickHouse.",
)
@click.option(
    "--log-format",
    type=click.Choice(["console", "json"], case_sensitive=False),
    default="console",
    show_default=True,
)
def main(season: int, start_month: int, end_month: int, chunk_size: int, log_format: str) -> None:
    import os

    if log_format.lower() == "json":
        os.environ["LOG_FORMAT"] = "json"
    configure_logging(level=logging.INFO)
    run_pull(
        season,
        start_month=start_month,
        end_month=end_month,
        chunk_size=chunk_size,
    )


if __name__ == "__main__":
    main()
