"""Single-pass materialisation of the pitch-type feature store (Phase 2a, decision [183]).

Runs the two window-SQL files (state+SEQ, arsenal) over the full 2015-2025 corpus and
writes one row per labeled pitch into ``pitch_type_features`` (V029), so the trainer
reads cheap ``WHERE game_date BETWEEN ...`` slices instead of re-running the
career-expanding ARS scan on every fold/split load.

WHY fold=0 (single pass), NOT per-fold like ``features``:
The pitch-OUTCOME ``features`` table (``features.tier_1_2.build_fold_features``)
replicates each pitch once per fold because its target-encoding columns DIFFER per
fold (TE fit on the fold's train window). The pitch-TYPE features carry NO target
encoding: every one of the 24 (career-expanding ARS, outing-scoped SEQ, current-pitch
state) is a streaming-temporal-cutoff feature whose value is identical regardless of
fold. Per-fold replication would therefore be pure duplication. We materialise ONCE
with ``fold=0``; the rolling-origin splits are enforced by the trainer's date-window
filters, and V029's ``fold`` column (mirrored from ``features`` by the 1a schema) stays
a harmless constant. The date filters plus the SQL's per-pitch cutoff are what prevent
leakage, proven by the 1b real-ClickHouse gate.

as_of_date = game_date: unlike ``features`` (where ``as_of_date`` is the fold's train
cutoff, strictly < game_date), the pitch-type features use same-outing prior pitches
(SEQ) and same-day arsenal, so the honest day-grain cutoff IS the pitch's own
game_date. No consumer reads this column; it exists only to fill V029's non-null shape.

Memory: inserts year by year (the loader scans arsenal history back to corpus_start for
each year but returns only that year's rows), so the full ~25M-row corpus is never
concatenated in one frame. This is the box/Mac run the trainer's CV reads from.
"""

from __future__ import annotations

import logging
import os
from datetime import date
from typing import Any

import click
from clickhouse_driver import Client

from bullpen_training.eval.leakage_guards import refuse_holdout
from bullpen_training.features.pitch_type_features import (
    CORPUS_START,
    load_pitch_type_features_for_window,
)
from bullpen_training.ingest.clickhouse_client import insert_dataframe, make_client
from bullpen_training.ingest.migrations import apply_migrations
from bullpen_training.logging_config import configure_logging, get_logger

log = get_logger(__name__)

DEFAULT_CORPUS_END_YEAR = 2025  # rule 13: 2026 is holdout-only, never materialised here.

MATERIALIZE_FOLD = 0
"""Single-pass fold sentinel: pitch-type features are fold-independent (module docstring)."""

# V029 insert columns (all but ``ingested_at``, which has a DEFAULT now()). Order is not
# load-bearing (insert_dataframe extracts by name) but this is the canonical list, and it
# matches the loader's returned columns plus the two materialisation-time additions
# (fold, as_of_date).
V029_INSERT_COLUMNS: tuple[str, ...] = (
    "game_id",
    "at_bat_index",
    "pitch_number",
    "game_date",
    "as_of_date",
    "fold",
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
    "ars_FF",
    "ars_SI",
    "ars_FC",
    "ars_SL",
    "ars_CU",
    "ars_CH",
    "ars_OFF",
    "ars_FF_by_count",
    "pitcher_prior_n",
    "prev1_pt_i",
    "prev2_pt_i",
    "prev1_missing",
    "pitches_into_outing",
)


def materialize_year(
    client: Client,
    *,
    year: int,
    corpus_start: date = CORPUS_START,
    fold: int = MATERIALIZE_FOLD,
) -> int:
    """Materialise one calendar year of pitch-type features into V029.

    Loads the year's window (the loader scans arsenal history back to ``corpus_start``),
    tags ``fold`` + ``as_of_date`` (= game_date), inserts. Returns rows written.
    """
    df = load_pitch_type_features_for_window(
        client,
        test_start=date(year, 1, 1),
        test_end=date(year, 12, 31),
        corpus_start=corpus_start,
    )
    if df.empty:
        log.info("no rows for year", year=year)
        return 0
    # No defensive copy: the loader returns a fresh `state.merge(arsenal, ...)` frame
    # (merge never returns a view), so these column assignments are safe and a copy would
    # only transiently double the year's frame - at odds with this module's memory framing.
    df["fold"] = fold
    df["as_of_date"] = df["game_date"]
    written = insert_dataframe(client, "pitch_type_features", df, columns=V029_INSERT_COLUMNS)
    log.info("year materialised", year=year, rows=written)
    return written


def materialize_pitch_type_features(
    client: Client,
    *,
    corpus_start_year: int = CORPUS_START.year,
    corpus_end_year: int = DEFAULT_CORPUS_END_YEAR,
    fold: int = MATERIALIZE_FOLD,
) -> dict[str, Any]:
    """Materialise the full [corpus_start_year, corpus_end_year] corpus, year by year.

    Rule 13: 2026 is holdout-only. ``refuse_holdout`` fences ``corpus_end_year`` (the
    loader also fences per year), so this can never scan the holdout into the training
    store. Per-year insertion bounds memory.
    """
    refuse_holdout(season_from=corpus_start_year, season_to=corpus_end_year)
    corpus_start = date(corpus_start_year, 1, 1)
    per_year: dict[int, int] = {}
    total = 0
    for year in range(corpus_start_year, corpus_end_year + 1):
        n = materialize_year(client, year=year, corpus_start=corpus_start, fold=fold)
        per_year[year] = n
        total += n
    log.info("materialisation complete", total_rows=total, years=len(per_year))
    return {"total_rows": total, "per_year": per_year, "fold": fold}


@click.command()
@click.option("--corpus-start-year", type=int, default=CORPUS_START.year, show_default=True)
@click.option(
    "--corpus-end-year",
    type=int,
    default=DEFAULT_CORPUS_END_YEAR,
    show_default=True,
    help="Inclusive last season to materialise. Rule 13 forbids 2026 (holdout-only).",
)
@click.option(
    "--log-format",
    type=click.Choice(["console", "json"], case_sensitive=False),
    default="console",
)
def main(corpus_start_year: int, corpus_end_year: int, log_format: str) -> None:
    if log_format.lower() == "json":
        os.environ["LOG_FORMAT"] = "json"
    configure_logging(level=logging.INFO)
    client = make_client()
    apply_migrations(client)
    materialize_pitch_type_features(
        client,
        corpus_start_year=corpus_start_year,
        corpus_end_year=corpus_end_year,
    )


if __name__ == "__main__":
    main()
