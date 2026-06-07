"""Tier 3 rolling-form features (Phase 2a.2).

Reuses the V005 `features` table; V006 adds the Tier 3 columns. The
build pulls Tier 3 values from `pitches` via ClickHouse window functions
(strict pre-pitch cutoff baked into the windowing) and merges them into
the existing per-fold features rows.

Mutation vs rebuild: ClickHouse `ALTER TABLE features UPDATE col = expr
FROM staging` requires gymnastics that don't roundtrip cleanly under
ReplacingMergeTree. Cleaner is to drop the fold's partitions and
re-build with all Tier 1+2+3 columns in one INSERT — idempotent, no
mutation pile-up, same path the dev re-run already uses.

The leaf plan's note about Java reproduction simplification (G1) still
holds: Java side (Phase 2a.8) reads the already-computed Tier 3 values
from `features` at serve time. Java doesn't recompute the windows —
the python side owns that.
"""

from __future__ import annotations

import logging
import os
from datetime import date
from pathlib import Path
from typing import Any, cast

import click
import pandas as pd
from clickhouse_driver import Client

from bullpen_training.features import LABEL_CLASSES
from bullpen_training.features.target_encoding import (
    apply_te,
    compute_prior,
    compute_te,
    save_encoding,
)
from bullpen_training.features.tier_1_2 import (
    DEFAULT_ENCODINGS_DIR,
    FoldWindow,
    _bind,
    _default_folds_for,
    _read,
    load_labeled_pitches,
)
from bullpen_training.features.tier_4_postpitch import (
    TIER4_COLUMNS,
    load_tier4_for_window,
    merge_tier4,
)
from bullpen_training.ingest.clickhouse_client import (
    ClickHouseSettings,
    insert_dataframe,
    make_client,
)
from bullpen_training.ingest.migrations import apply_migrations
from bullpen_training.logging_config import configure_logging, get_logger

log = get_logger(__name__)

SQL_DIR = Path(__file__).resolve().parent / "sql"

TIER3_COLUMNS: tuple[str, ...] = (
    "pitcher_pitches_last_28d",
    "pitcher_pitches_in_game",
    "days_since_last_appearance",
    "pitcher_strike_rate_28d",
    "pitcher_swstrike_rate_28d",
    "pitcher_inplay_rate_28d",
    "pitcher_strike_rate_std",
    "batter_strike_rate_28d",
    "batter_inplay_rate_28d",
    "batter_ball_rate_28d",
    "batter_inplay_rate_std",
)

# Full insert column list = Tier 1+2 (from 2a.1) + Tier 3.
FEATURES_COLUMNS_FULL: tuple[str, ...] = (
    "game_id",
    "at_bat_index",
    "pitch_number",
    "game_date",
    "as_of_date",
    "fold",
    "pitcher_id",
    "batter_id",
    "count_balls",
    "count_strikes",
    "outs",
    "inning",
    "base_state",
    "score_diff",
    "pitcher_throws",
    "batter_stand",
    "park_id",
    "dow",
    "pitcher_te_ball",
    "pitcher_te_called_strike",
    "pitcher_te_swinging_strike",
    "pitcher_te_foul",
    "pitcher_te_in_play",
    "batter_te_ball",
    "batter_te_called_strike",
    "batter_te_swinging_strike",
    "batter_te_foul",
    "batter_te_in_play",
    "label",
    *TIER3_COLUMNS,
    *TIER4_COLUMNS,
)

PK_JOIN: tuple[str, ...] = ("game_id", "at_bat_index", "pitch_number")


def load_tier3_for_window(client: Client, *, test_start: date, test_end: date) -> pd.DataFrame:
    """Pull Tier 3 columns for the window via the windowed SQL.

    The single-shot query OOMs ClickHouse (4 GiB container cap) once the
    encode window crosses ~6M rows. Chunk by calendar year — each query
    independently scans (year - 90 days, year-end) and we concat the
    results. Per-year scan stays around ~1M rows, comfortable.
    """
    cols = list(PK_JOIN) + list(TIER3_COLUMNS)
    chunks: list[pd.DataFrame] = []
    for year in range(test_start.year, test_end.year + 1):
        chunk_start = max(date(year, 1, 1), test_start)
        chunk_end = min(date(year, 12, 31), test_end)
        sql = _bind(
            _read(SQL_DIR / "compute_tier3.sql"),
            {"test_start": chunk_start, "test_end": chunk_end},
        )
        rows = cast(list[tuple[Any, ...]], client.execute(sql))
        if rows:
            chunks.append(pd.DataFrame(rows, columns=cols))
    if not chunks:
        return pd.DataFrame(columns=cols)
    return pd.concat(chunks, ignore_index=True)


def build_fold_full(
    client: Client,
    fold: FoldWindow,
    *,
    encodings_dir: Path | None = None,
) -> dict[str, Any]:
    """Rebuild one fold's features rows with all Tier 1+2+3 columns."""
    enc_dir = encodings_dir or DEFAULT_ENCODINGS_DIR

    log.info(
        "loading train window for TE",
        fold=fold.fold_id,
        train_start=str(fold.train_start),
        train_end=str(fold.train_end),
    )
    train_df = load_labeled_pitches(client, start_date=fold.train_start, end_date=fold.train_end)
    pitcher_te = compute_te(
        train_df,
        entity_col="pitcher_id",
        label_col="label",
        label_classes=LABEL_CLASSES,
    )
    batter_te = compute_te(
        train_df,
        entity_col="batter_id",
        label_col="label",
        label_classes=LABEL_CLASSES,
    )
    save_encoding(pitcher_te, enc_dir / f"pitcher_fold{fold.fold_id}.json", entity_col="pitcher_id")
    save_encoding(batter_te, enc_dir / f"batter_fold{fold.fold_id}.json", entity_col="batter_id")
    train_prior = compute_prior(train_df, "label")
    log.info(
        "encodings persisted",
        fold=fold.fold_id,
        pitcher_entities=len(pitcher_te),
        batter_entities=len(batter_te),
    )

    train_rows = len(train_df)
    # Free the large train frame now - only the (small) TE dicts + train_prior are
    # needed below, so the per-year encode chunk stays the only big allocation.
    del train_df

    # Encode the FULL fold span [train_start, test_end] so the harness can pull
    # train/val/test from `features` without recomputing TE on the fly. The TE was
    # built from train_start..train_end only (above), so encoding the train years
    # with that TE is a benign self-reference - rows in the train years see the same
    # fold-wide TE every other row uses.
    #
    # DEV-1 (fold-4 OOM): chunk the encode -> merge -> insert by CALENDAR YEAR so the
    # pandas peak is ~one season, not the whole fold span (fold 4's ~7.4M-row window
    # stacked encoded + tier3 + tier4 + merged and exceeded the live-box headroom).
    #
    # Correctness invariant (rule 10) - DO NOT move these inside the loop: the TE fit
    # (compute_te on train_df), train_prior, the saved encodings, and as_of_date are
    # FOLD-INVARIANT, computed ONCE above and reused for every chunk. Fitting TE per
    # year would leak the test span into its own encoding. Only the
    # load -> apply_te -> tier3/4 -> merge -> insert body is chunked. The
    # load_tier3_for_window / load_tier4_for_window loaders are themselves year-chunked
    # and each carries a 90-day lookback in its SQL, so a per-year call returns data
    # IDENTICAL to the full-window call - the rolling-form window is never truncated at
    # a year edge, and apply_te is a pure per-row lookup (no temporal component). The
    # resulting `features` table is therefore identical to the single-shot build.
    encode_start = fold.train_start
    encode_end = fold.test_end
    log.info(
        "encoding fold span by year (Tier 1+2+3+4)",
        fold=fold.fold_id,
        encode_start=str(encode_start),
        encode_end=str(encode_end),
    )

    test_rows = 0
    tier3_rows = 0
    rows_written = 0
    for year in range(encode_start.year, encode_end.year + 1):
        chunk_start = max(date(year, 1, 1), encode_start)
        chunk_end = min(date(year, 12, 31), encode_end)
        year_df = load_labeled_pitches(client, start_date=chunk_start, end_date=chunk_end)
        if year_df.empty:
            continue
        test_rows += len(year_df)
        year_df["as_of_date"] = fold.train_end
        year_df["fold"] = fold.fold_id
        encoded = apply_te(
            year_df, pitcher_te, entity_col="pitcher_id", column_prefix="pitcher", prior=train_prior
        )
        encoded = apply_te(
            encoded, batter_te, entity_col="batter_id", column_prefix="batter", prior=train_prior
        )

        tier3 = load_tier3_for_window(client, test_start=chunk_start, test_end=chunk_end)
        tier4 = load_tier4_for_window(client, test_start=chunk_start, test_end=chunk_end)
        tier3_rows += len(tier3)

        merged = encoded.merge(tier3, on=list(PK_JOIN), how="left")
        # T3 has more rows than T1+2 (HBP is in T3 but excluded from the 5-class
        # T1+2 filter). LEFT JOIN drops the extras correctly; we only warn on the
        # OPPOSITE (a T1+2 row with no T3 match - the windowed SQL skipped a pitch).
        unmatched = int(cast(Any, merged["pitcher_pitches_in_game"].isna().sum()))
        if unmatched:
            log.warning(
                "Tier 1+2 rows without Tier 3 match",
                fold=fold.fold_id,
                year=year,
                unmatched=unmatched,
                total=len(merged),
            )

        # ClickHouse Nullable(UInt*) columns reject numpy.float64 (what a pandas LEFT
        # JOIN yields on missing matches). Coerce to pandas nullable Int / explicit
        # fillna for the DEFAULT-0 column before the driver's type check trips.
        for col in ("pitcher_pitches_last_28d", "days_since_last_appearance"):
            merged[col] = merged[col].astype("Int64")
        merged["pitcher_pitches_in_game"] = (
            merged["pitcher_pitches_in_game"].fillna(0).astype("uint32")
        )

        # Tier 4 join - merge_tier4 fills NaN pitch_type with "" for LowCardinality;
        # float Tier 4 columns stay Nullable (clickhouse-driver encodes NaN -> NULL).
        merged = merge_tier4(merged, tier4)

        final = cast(pd.DataFrame, merged[list(FEATURES_COLUMNS_FULL)])
        rows_written += insert_dataframe(client, "features", final, columns=FEATURES_COLUMNS_FULL)
        log.info(
            "features chunk inserted (Tier 1+2+3+4)",
            fold=fold.fold_id,
            year=year,
            rows=len(final),
        )
        # Free the per-year frames before the next iteration so peak stays ~one season.
        del year_df, encoded, tier3, tier4, merged, final

    log.info(
        "features inserted (Tier 1+2+3+4)",
        fold=fold.fold_id,
        rows=rows_written,
    )
    return {
        "fold": fold.fold_id,
        "train_rows": train_rows,
        "test_rows": test_rows,
        "tier3_rows": tier3_rows,
        "rows_written": rows_written,
    }


def run(
    *,
    min_year: int,
    max_year: int,
    settings: ClickHouseSettings | None = None,
    encodings_dir: Path | None = None,
    folds: list[FoldWindow] | None = None,
) -> list[dict[str, Any]]:
    client = make_client(settings)
    apply_migrations(client)
    use_folds = folds or _default_folds_for(min_year, max_year)
    summaries: list[dict[str, Any]] = []
    for fold in use_folds:
        summary = build_fold_full(client, fold, encodings_dir=encodings_dir)
        summaries.append(summary)
    return summaries


# Re-export for convenience
__all__ = (
    "FEATURES_COLUMNS_FULL",
    "TIER3_COLUMNS",
    "build_fold_full",
    "load_tier3_for_window",
    "run",
)


@click.command()
@click.option("--min-year", type=int, default=2015, show_default=True)
@click.option("--max-year", type=int, default=2025, show_default=True)
@click.option(
    "--encodings-dir",
    type=click.Path(file_okay=False, dir_okay=True, path_type=Path),
    default=None,
)
@click.option(
    "--log-format",
    type=click.Choice(["console", "json"], case_sensitive=False),
    default="console",
)
def main(min_year: int, max_year: int, encodings_dir: Path | None, log_format: str) -> None:
    if log_format.lower() == "json":
        os.environ["LOG_FORMAT"] = "json"
    configure_logging(level=logging.INFO)
    run(min_year=min_year, max_year=max_year, encodings_dir=encodings_dir)


if __name__ == "__main__":
    main()
