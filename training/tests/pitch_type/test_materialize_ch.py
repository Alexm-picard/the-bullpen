"""Real-ClickHouse round-trip gate for the pitch-type materialisation (Phase 2a, [183]).

The 1b leakage gate drives the window SQL through the loader but stops at the pandas
frame; it never writes V029. This test closes the write leg: seed a `pitches` fixture,
run `materialize_year` into a real `pitch_type_features` (V029) table created from the
ACTUAL migration DDL, and assert the round-trip is faithful -

  * every loaded row lands (count == the loader's own frame for the same window),
  * fold is the single-pass constant 0,
  * as_of_date == game_date (the honest per-pitch day-grain cutoff, module docstring),
  * label_pitch_type deserialises to a valid y7 Enum,
  * cold-start fidelity survives the insert: ars_* is NULL exactly where
    pitcher_prior_n == 0 (a rookie's first career pitch), finite elsewhere.

CH connection is env-driven; locally/offline the module skips, and the CI
`leakage-sql-gate` job sets BULLPEN_REQUIRE_CH=1 so an unreachable CH is a hard failure.
This test is enumerated by name in that job (training.yml) - a new SQL-path test that is
not added there silently never runs against ClickHouse.
"""

from __future__ import annotations

import os
from collections.abc import Iterator
from dataclasses import replace
from datetime import date, datetime, timedelta
from pathlib import Path
from typing import Any, cast

import pytest
from clickhouse_driver import Client

from bullpen_training.features.pitch_type_features import load_pitch_type_features_for_window
from bullpen_training.ingest.clickhouse_client import ClickHouseSettings, make_client
from bullpen_training.pitch_type import PITCH_TYPE_CLASSES
from bullpen_training.pitch_type.materialize import (
    MATERIALIZE_FOLD,
    materialize_pitch_type_features,
    materialize_year,
)

TEST_DB = "materialize_pitch_type_test"
REQUIRE_CH = os.environ.get("BULLPEN_REQUIRE_CH") == "1"

CORPUS_START = date(2024, 1, 1)  # scan floor for this test (all fixture history is in 2024)
HISTORY_START = date(2024, 3, 1)  # veteran debut (pre-window career history)
ROOKIE_DEBUT = date(2024, 4, 10)  # rookie first career pitch (cold-start row)
SEED_END = date(2024, 4, 30)
MATERIALIZE_YEAR = 2024

REPO_ROOT = Path(__file__).resolve().parents[3]
V029_SQL = REPO_ROOT / "backend/src/main/resources/db/clickhouse/V029__pitch_type_features.sql"

_PITCH_INSERT_COLS = (
    "game_id",
    "game_date",
    "at_bat_index",
    "pitch_number",
    "pitcher_id",
    "batter_id",
    "pitch_type",
    "balls",
    "strikes",
    "outs",
    "inning",
    "base_state",
    "p_throws",
    "stand",
    "park_id",
    "times_through_order",
    "at_bat_number_in_game",
    "times_faced_today",
    "ingested_at",
)

_PREF_CODES = ("FF", "SL", "CH", "SI", "CU", "FC")
_NOISE_CODES = ("FF", "SL", "CH", "SI", "CU", "FC", "KC", "ST", "EP")


@pytest.fixture(scope="module")
def ch() -> Iterator[Client]:
    try:
        admin = make_client()
        admin.execute(f"CREATE DATABASE IF NOT EXISTS {TEST_DB}")
    except Exception as exc:  # any connection failure means "no CH"
        if REQUIRE_CH:
            raise
        pytest.skip(f"ClickHouse unreachable (set BULLPEN_REQUIRE_CH=1 to fail instead): {exc}")
    client = make_client(replace(ClickHouseSettings.from_env(), database=TEST_DB))
    _recreate_pitches(client)
    _recreate_features(client)
    _insert(client, _synthetic_pitch_rows(ingested_at=datetime(2024, 5, 1, 0, 0, 0)))
    yield client
    client.disconnect()


def _recreate_pitches(ch: Client) -> None:
    """Minimal `pitches` carrying exactly the columns compute_pitch_type_*.sql read."""
    ch.execute("DROP TABLE IF EXISTS pitches")
    ch.execute(
        """
        CREATE TABLE pitches
        (
            game_id UInt64,
            game_date Date,
            at_bat_index UInt16,
            pitch_number UInt8,
            pitcher_id UInt32,
            batter_id UInt32,
            pitch_type LowCardinality(String),
            balls UInt8,
            strikes UInt8,
            outs UInt8,
            inning UInt8,
            base_state UInt8,
            p_throws FixedString(1),
            stand FixedString(1),
            park_id LowCardinality(String),
            times_through_order Nullable(UInt8),
            at_bat_number_in_game Nullable(UInt16),
            times_faced_today Nullable(UInt8),
            ingested_at DateTime DEFAULT now()
        )
        ENGINE = ReplacingMergeTree(ingested_at)
        PARTITION BY toYYYYMM(game_date)
        ORDER BY (game_date, game_id, at_bat_index, pitch_number)
        """
    )


def _recreate_features(ch: Client) -> None:
    """Create V029 pitch_type_features from the ACTUAL migration DDL (no drift)."""
    ch.execute("DROP TABLE IF EXISTS pitch_type_features")
    ch.execute(V029_SQL.read_text(encoding="utf-8"))


def _synthetic_pitch_rows(*, ingested_at: datetime, seed: int = 4242) -> list[tuple[Any, ...]]:
    """Deterministic pitches over [HISTORY_START, SEED_END]. Each (day, pitcher) is its own
    game_id (unique pitch key). Veterans (pids 1-4) debut at HISTORY_START; rookies (pids
    5-6) debut ROOKIE_DEBUT so their first career pitch (pitcher_prior_n == 0, ars_* NULL)
    lands inside the materialised year - the cold-start round-trip case."""
    import numpy as np

    rng = np.random.default_rng(seed)
    rows: list[tuple[Any, ...]] = []
    n_pitchers = 6
    n_days = (SEED_END - HISTORY_START).days + 1
    game_id = 900_000
    for d in range(n_days):
        gd = HISTORY_START + timedelta(days=d)
        for pid in range(1, n_pitchers + 1):
            debut = HISTORY_START if pid <= 4 else ROOKIE_DEBUT
            if gd < debut:
                continue
            game_id += 1
            batter = int(rng.integers(1, 30))
            pref = _PREF_CODES[(pid - 1) % len(_PREF_CODES)]
            for pn in range(1, 6):
                pt = pref if rng.random() < 0.7 else str(rng.choice(_NOISE_CODES))
                rows.append(
                    (
                        game_id,
                        gd,
                        1,
                        pn,
                        pid,
                        batter,
                        pt,
                        int(rng.integers(0, 4)),
                        int(rng.integers(0, 3)),
                        int(rng.integers(0, 3)),
                        int(rng.integers(1, 10)),
                        int(rng.integers(0, 8)),
                        "R" if pid % 2 else "L",
                        "R" if batter % 2 else "L",
                        f"PARK{pid % 5:02d}",
                        pn,
                        pn,
                        0,
                        ingested_at,
                    )
                )
    return rows


def _insert(ch: Client, rows: list[tuple[Any, ...]]) -> None:
    ch.execute(f"INSERT INTO pitches ({', '.join(_PITCH_INSERT_COLS)}) VALUES", rows)


def _scalar(ch: Client, sql: str) -> Any:
    rows = cast(list[tuple[Any, ...]], ch.execute(sql))
    return rows[0][0]


@pytest.fixture(scope="module")
def materialized(ch: Client) -> int:
    """Materialise the fixture year exactly once; return rows written. Read-tests share
    this so the store is built one time (re-inserting identical keys would only Replacing
    -dedup under FINAL anyway, but a single build keeps bare counts exact)."""
    return materialize_year(ch, year=MATERIALIZE_YEAR, corpus_start=CORPUS_START)


def test_materialize_round_trips_every_loaded_row(ch: Client, materialized: int) -> None:
    assert materialized > 0
    # The loader's own frame for the same full-year window is the source of truth.
    frame = load_pitch_type_features_for_window(
        ch,
        test_start=date(MATERIALIZE_YEAR, 1, 1),
        test_end=date(MATERIALIZE_YEAR, 12, 31),
        corpus_start=CORPUS_START,
    )
    assert materialized == len(frame), f"materialised {materialized} rows, loader gave {len(frame)}"
    stored = _scalar(ch, "SELECT count() FROM pitch_type_features FINAL")
    assert stored == len(frame)


def test_materialize_sets_fold_and_as_of_date(ch: Client, materialized: int) -> None:
    folds = ch.execute("SELECT DISTINCT fold FROM pitch_type_features")
    assert folds == [(MATERIALIZE_FOLD,)], f"expected single fold {MATERIALIZE_FOLD}, got {folds}"
    mismatched = _scalar(
        ch, "SELECT count() FROM pitch_type_features WHERE as_of_date != game_date"
    )
    assert mismatched == 0, "as_of_date must equal game_date for the single-pass materialisation"


def test_materialize_labels_are_valid_y7(ch: Client, materialized: int) -> None:
    rows = cast(
        list[tuple[Any, ...]],
        ch.execute("SELECT DISTINCT label_pitch_type FROM pitch_type_features"),
    )
    labels = {row[0] for row in rows}
    assert labels, "no labels materialised"
    assert labels <= set(PITCH_TYPE_CLASSES), (
        f"unexpected label(s): {labels - set(PITCH_TYPE_CLASSES)}"
    )


def test_materialize_preserves_cold_start_nulls(ch: Client, materialized: int) -> None:
    """ars_* must be NULL exactly where pitcher_prior_n == 0 (a rookie's first pitch) and
    finite everywhere else - the streaming-cutoff cold-start signal must survive the insert."""
    cold_with_value = _scalar(
        ch,
        "SELECT count() FROM pitch_type_features WHERE pitcher_prior_n = 0 AND ars_FF IS NOT NULL",
    )
    assert cold_with_value == 0, "ars_FF must be NULL at the cold-start (prior_n == 0) rows"
    warm_with_null = _scalar(
        ch,
        "SELECT count() FROM pitch_type_features WHERE pitcher_prior_n > 0 AND ars_FF IS NULL",
    )
    assert warm_with_null == 0, "ars_FF must be finite once the pitcher has prior history"
    # Sanity: the fixture actually produced at least one cold-start row to exercise the case.
    cold_rows = _scalar(ch, "SELECT count() FROM pitch_type_features WHERE pitcher_prior_n = 0")
    assert cold_rows > 0, "fixture produced no cold-start rows"


def test_materialize_corpus_driver_aggregates_per_year(ch: Client) -> None:
    """The full-corpus driver's loop + {total_rows, per_year, fold} aggregation (a single
    year here). Re-inserting the fixture year is a ReplacingMergeTree no-op under FINAL, but
    insert_dataframe still returns the rows written, so the returned aggregation is exact."""
    result = materialize_pitch_type_features(
        ch, corpus_start_year=MATERIALIZE_YEAR, corpus_end_year=MATERIALIZE_YEAR
    )
    assert result["fold"] == MATERIALIZE_FOLD
    assert set(result["per_year"]) == {MATERIALIZE_YEAR}
    assert result["total_rows"] == result["per_year"][MATERIALIZE_YEAR]
    assert result["total_rows"] > 0


def test_materialize_year_with_no_rows_returns_zero(ch: Client) -> None:
    """A year with no labeled pitches (the fixture is 2024-only) hits the empty-frame path:
    zero written, no insert attempted."""
    assert materialize_year(ch, year=2023, corpus_start=date(2023, 1, 1)) == 0


def test_materialize_corpus_refuses_holdout(ch: Client) -> None:
    """Rule 13: the corpus driver must refuse a 2026 end year before touching ClickHouse."""
    with pytest.raises(Exception, match="holdout"):
        materialize_pitch_type_features(ch, corpus_start_year=2015, corpus_end_year=2026)
