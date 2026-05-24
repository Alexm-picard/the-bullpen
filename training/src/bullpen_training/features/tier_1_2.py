"""Tier 1 + Tier 2 feature builder (Phase 2a.1).

Builds the `features` table for one fold at a time. The fold's training
window is used to compute the pitcher/batter target encodings; the
encodings are then applied to the same fold's rows, which by construction
have `game_date > train_window_end` (no leakage — proven in 2a.3).

The encoded entity TEs land in `training/artifacts/encodings/` so the
serving side (Phase 2a.8) can pin a particular encoding file by hash.
"""

from __future__ import annotations

import logging
import re
from collections.abc import Iterable
from dataclasses import dataclass
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
from bullpen_training.ingest.clickhouse_client import (
    ClickHouseSettings,
    insert_dataframe,
    make_client,
)
from bullpen_training.ingest.migrations import apply_migrations
from bullpen_training.logging_config import configure_logging, get_logger

log = get_logger(__name__)

SQL_DIR = Path(__file__).resolve().parent / "sql"

REPO_ROOT = Path(__file__).resolve().parents[4]
DEFAULT_ENCODINGS_DIR = REPO_ROOT / "training" / "artifacts" / "encodings"

FEATURES_COLUMNS: tuple[str, ...] = (
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
)


@dataclass(frozen=True)
class FoldWindow:
    """Defines one fold: train on [train_start, train_end], encode-and-insert
    rows in [test_start, test_end]. test_start MUST be > train_end (the
    leakage tests verify this; see 2a.3)."""

    fold_id: int
    train_start: date
    train_end: date
    test_start: date
    test_end: date

    def __post_init__(self) -> None:
        if self.train_end >= self.test_start:
            raise ValueError(
                f"fold {self.fold_id}: train_end {self.train_end} >= test_start "
                f"{self.test_start} — leakage by construction"
            )


def _bind(sql: str, params: dict[str, Any]) -> str:
    """Same bind helper Phase 1.2 uses. All callers pass dates we control."""
    out = sql
    for k, v in params.items():
        value = f"'{v}'" if isinstance(v, date | str) else str(v)
        out = re.sub(rf":{re.escape(k)}\b", value, out)
    return out


def _read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def load_labeled_pitches(client: Client, *, start_date: date, end_date: date) -> pd.DataFrame:
    """Window-scoped pull of labeled pitches with Tier 1 columns + label.

    Chunked by calendar year — a single SELECT with `ORDER BY` over 5M+
    rows OOMs CH (container 4g cap). Per-year scans stay ~700K rows.
    """
    columns = [
        "game_id",
        "at_bat_index",
        "pitch_number",
        "game_date",
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
        "label",
    ]
    chunks: list[pd.DataFrame] = []
    for year in range(start_date.year, end_date.year + 1):
        chunk_start = max(date(year, 1, 1), start_date)
        chunk_end = min(date(year, 12, 31), end_date)
        sql = _bind(
            _read(SQL_DIR / "select_labeled_pitches.sql"),
            {"start_date": chunk_start, "end_date": chunk_end},
        )
        rows = cast(list[tuple[Any, ...]], client.execute(sql))
        if rows:
            chunks.append(pd.DataFrame(rows, columns=columns))
    if not chunks:
        return pd.DataFrame(columns=columns)
    return pd.concat(chunks, ignore_index=True)


def build_fold_features(
    client: Client,
    fold: FoldWindow,
    *,
    encodings_dir: Path | None = None,
    label_classes: Iterable[str] = LABEL_CLASSES,
) -> dict[str, Any]:
    """End-to-end build for one fold:

    1. Pull pitches in train window → compute pitcher/batter TE.
    2. Persist TE files (deterministic JSON) under encodings_dir.
    3. Pull pitches in test window → apply TE → write to `features`.
    """
    enc_dir = encodings_dir or DEFAULT_ENCODINGS_DIR

    log.info(
        "loading train window",
        fold=fold.fold_id,
        train_start=str(fold.train_start),
        train_end=str(fold.train_end),
    )
    train_df = load_labeled_pitches(client, start_date=fold.train_start, end_date=fold.train_end)
    log.info("computing target encodings", fold=fold.fold_id, rows=len(train_df))
    pitcher_te = compute_te(
        train_df, entity_col="pitcher_id", label_col="label", label_classes=label_classes
    )
    batter_te = compute_te(
        train_df, entity_col="batter_id", label_col="label", label_classes=label_classes
    )
    pitcher_path = enc_dir / f"pitcher_fold{fold.fold_id}.json"
    batter_path = enc_dir / f"batter_fold{fold.fold_id}.json"
    save_encoding(pitcher_te, pitcher_path, entity_col="pitcher_id")
    save_encoding(batter_te, batter_path, entity_col="batter_id")
    log.info(
        "encodings persisted",
        fold=fold.fold_id,
        pitcher_path=str(pitcher_path),
        batter_path=str(batter_path),
        pitcher_entities=len(pitcher_te),
        batter_entities=len(batter_te),
    )

    log.info(
        "loading test window",
        fold=fold.fold_id,
        test_start=str(fold.test_start),
        test_end=str(fold.test_end),
    )
    test_df = load_labeled_pitches(client, start_date=fold.test_start, end_date=fold.test_end)
    test_df["as_of_date"] = fold.train_end
    test_df["fold"] = fold.fold_id

    train_prior = compute_prior(train_df, "label")
    encoded = apply_te(
        test_df, pitcher_te, entity_col="pitcher_id", column_prefix="pitcher", prior=train_prior
    )
    encoded = apply_te(
        encoded, batter_te, entity_col="batter_id", column_prefix="batter", prior=train_prior
    )
    encoded = cast(pd.DataFrame, encoded[list(FEATURES_COLUMNS)])

    rows_written = insert_dataframe(client, "features", encoded, columns=FEATURES_COLUMNS)
    log.info(
        "features inserted",
        fold=fold.fold_id,
        rows=rows_written,
        prior=train_prior,
    )
    return {
        "fold": fold.fold_id,
        "train_rows": len(train_df),
        "test_rows": len(test_df),
        "rows_written": rows_written,
        "pitcher_encoding_path": str(pitcher_path),
        "batter_encoding_path": str(batter_path),
        "prior": train_prior,
    }


def _default_folds_for(min_year: int, max_year: int) -> list[FoldWindow]:
    """Generate 4 rolling-origin folds across [min_year, max_year].

    Decision [56] locks: train through year N, val N+1, test N+2.
    Tier 2 target encoding is built from the TRAIN window only — the val
    year is held out as a hyperparameter / early-stopping signal in
    2a.5, so it must NOT contaminate the TE.

    To accommodate the harness's iteration, each fold's `test_window`
    here spans both the val and test years (val_year .. test_year), and
    the harness in 2a.4 partitions them by calendar year. This keeps the
    encoded `features` table compact (one set of TE columns per fold,
    valid for both val and test rows).
    """
    span = max_year - min_year + 1
    if span < 6:
        raise ValueError(
            f"need at least 6 seasons for 4 folds with val held out; "
            f"have {span} ({min_year}-{max_year})"
        )
    # Fold k tests year (max_year - (4 - k)); val = test - 1; train end = test - 2.
    test_years = [max_year - 3, max_year - 2, max_year - 1, max_year]
    folds: list[FoldWindow] = []
    for i, ty in enumerate(test_years):
        folds.append(
            FoldWindow(
                fold_id=i + 1,
                train_start=date(min_year, 1, 1),
                train_end=date(ty - 2, 12, 31),  # val year reserved as holdout
                test_start=date(ty - 1, 1, 1),  # encode rows from val + test years
                test_end=date(ty, 12, 31),
            )
        )
    return folds


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
        summary = build_fold_features(client, fold, encodings_dir=encodings_dir)
        summaries.append(summary)
    return summaries


@click.command()
@click.option("--min-year", type=int, default=2015, show_default=True)
@click.option("--max-year", type=int, default=2024, show_default=True)
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
    import os

    if log_format.lower() == "json":
        os.environ["LOG_FORMAT"] = "json"
    configure_logging(level=logging.INFO)
    run(min_year=min_year, max_year=max_year, encodings_dir=encodings_dir)


if __name__ == "__main__":
    main()
