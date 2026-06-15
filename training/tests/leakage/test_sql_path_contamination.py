"""Leakage test #5 - SQL-path future contamination (DEF-H2, CLAUDE.md rule 10).

The other four leakage tests exercise `build_fold_inmem`, a pure-pandas MIRROR of
the production builder (see conftest). They cannot catch a leak that lives in the
*real* SQL path:

    tier_1_2.build_fold_features
      -> load_labeled_pitches
        -> features/sql/select_labeled_pitches.sql

If that SQL ever widened its read past `train_end` (an off-by-one window, a wrong
bind, a stray cross-window JOIN, a dropped FINAL that double-counts a re-ingested
pitch), the pandas mirror would stay green while production silently trained on the
future. This test closes that gap: it drives the REAL builder against a real
ClickHouse instance and applies the same future-contamination discipline as
test #1 - mutating pitches strictly after `train_end` MUST NOT change any
target-encoded feature value written to `features` for that fold.

It also pins the FINAL discipline on the real path (DEF-H3): a re-ingested,
label-corrected duplicate of train-window pitches (same key, later `ingested_at`)
must be deduped by `FROM pitches FINAL`, so the target encoding reflects only the
latest version - not a double-counted phantom that a bare `FROM pitches` would see.

CH connection is env-driven (CLICKHOUSE_HOST/PORT/USER/PASSWORD). When no
ClickHouse is reachable - the default fast CI leakage job and offline dev - the
whole module skips cleanly. The dedicated `leakage-sql-gate` CI job stands up a
ClickHouse service and sets BULLPEN_REQUIRE_CH=1, which turns an unreachable CH
into a hard failure so the gate can never pass by silently skipping.
"""

from __future__ import annotations

import os
from collections.abc import Iterator
from dataclasses import replace
from datetime import date, datetime, timedelta
from typing import Any, cast

import pytest
from clickhouse_driver import Client

from bullpen_training.features.tier_1_2 import (
    FEATURES_COLUMNS,
    FoldWindow,
    build_fold_features,
    load_labeled_pitches,
)
from bullpen_training.ingest.clickhouse_client import ClickHouseSettings, make_client
from tests.leakage.conftest import _signal_label  # per-pitcher signal so TE is non-trivial

TEST_DB = "leakage_sql_test"
REQUIRE_CH = os.environ.get("BULLPEN_REQUIRE_CH") == "1"

TE_COLUMNS = tuple(c for c in FEATURES_COLUMNS if "_te_" in c)

# Adjacent, non-overlapping windows. test_start > train_end (FoldWindow enforces it).
TRAIN_START = date(2024, 4, 1)
TRAIN_END = date(2024, 4, 20)
TEST_START = date(2024, 4, 21)
TEST_END = date(2024, 5, 10)

_PITCH_INSERT_COLS = (
    "game_id",
    "game_date",
    "at_bat_index",
    "pitch_number",
    "pitcher_id",
    "batter_id",
    "description",
    "balls",
    "strikes",
    "outs",
    "inning",
    "base_state",
    "p_throws",
    "stand",
    "park_id",
    "ingested_at",
)


# --- ClickHouse fixture (skips, or fails loud under BULLPEN_REQUIRE_CH) --------


@pytest.fixture(scope="module")
def ch() -> Iterator[Client]:
    """A client bound to an isolated `leakage_sql_test` database.

    Connects with the env-driven default settings; if CH is unreachable we skip
    (or fail, when BULLPEN_REQUIRE_CH=1 - the CI gate's contract).
    """
    try:
        admin = make_client()
        admin.execute(f"CREATE DATABASE IF NOT EXISTS {TEST_DB}")
    except Exception as exc:  # - any connection/handshake failure means "no CH"
        if REQUIRE_CH:
            raise
        pytest.skip(f"ClickHouse unreachable (set BULLPEN_REQUIRE_CH=1 to fail instead): {exc}")
    client = make_client(replace(ClickHouseSettings.from_env(), database=TEST_DB))
    yield client
    client.disconnect()


# --- isolated-schema helpers --------------------------------------------------


def _recreate_pitches(ch: Client) -> None:
    """Faithful minimal `pitches`: exactly the columns select_labeled_pitches.sql
    reads, the same ReplacingMergeTree(ingested_at) engine + sort key as prod, so
    FINAL behaves identically."""
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
            description Enum8('ball'=0,'called_strike'=1,'swinging_strike'=2,
                             'foul'=3,'in_play'=4,'hit_by_pitch'=5,'unknown'=99),
            balls UInt8,
            strikes UInt8,
            outs UInt8,
            inning UInt8,
            base_state UInt8,
            p_throws FixedString(1),
            stand FixedString(1),
            park_id LowCardinality(String),
            score_diff_live Nullable(Int16),
            ingested_at DateTime DEFAULT now()
        )
        ENGINE = ReplacingMergeTree(ingested_at)
        PARTITION BY toYYYYMM(game_date)
        ORDER BY (game_date, game_id, at_bat_index, pitch_number)
        """
    )


def _recreate_features(ch: Client) -> None:
    """Minimal `features` accepting exactly the FEATURES_COLUMNS insert. Plain
    MergeTree (the test never inserts duplicate feature rows) keyed by fold so two
    builds can coexist and be read back independently."""
    ch.execute("DROP TABLE IF EXISTS features")
    ch.execute(
        """
        CREATE TABLE features
        (
            game_id UInt64,
            at_bat_index UInt16,
            pitch_number UInt8,
            game_date Date,
            as_of_date Date,
            fold UInt8,
            pitcher_id UInt32,
            batter_id UInt32,
            count_balls UInt8,
            count_strikes UInt8,
            outs UInt8,
            inning UInt8,
            base_state UInt8,
            score_diff Int16,
            pitcher_throws FixedString(1),
            batter_stand FixedString(1),
            park_id LowCardinality(String),
            dow UInt8,
            pitcher_te_ball Float32,
            pitcher_te_called_strike Float32,
            pitcher_te_swinging_strike Float32,
            pitcher_te_foul Float32,
            pitcher_te_in_play Float32,
            batter_te_ball Float32,
            batter_te_called_strike Float32,
            batter_te_swinging_strike Float32,
            batter_te_foul Float32,
            batter_te_in_play Float32,
            label Enum8('ball'=0,'called_strike'=1,'swinging_strike'=2,'foul'=3,'in_play'=4)
        )
        ENGINE = MergeTree
        PARTITION BY (toYYYYMM(game_date), fold)
        ORDER BY (fold, game_date, game_id, at_bat_index, pitch_number)
        """
    )


def _synthetic_pitch_rows(
    *,
    n_pitchers: int = 12,
    n_batters: int = 20,
    pitches_per_day: int = 6,
    ingested_at: datetime,
    seed: int = 1337,
) -> list[tuple[Any, ...]]:
    """Deterministic pitches over [TRAIN_START, TEST_END]. Each pitcher has a
    strong preferred class (via conftest._signal_label) so target encoding carries
    real per-entity signal - the canary below depends on that signal existing.
    `description` IS the label (the SQL projects `toString(description) AS label`)."""
    import numpy as np

    rng = np.random.default_rng(seed)
    rows: list[tuple[Any, ...]] = []
    n_days = (TEST_END - TRAIN_START).days + 1
    game_id = 700_000
    for d in range(n_days):
        gd = TRAIN_START + timedelta(days=d)
        game_id += 1
        for pitcher_id in range(1, n_pitchers + 1):
            batter_id = int(rng.integers(1, n_batters + 1))
            for pitch_no in range(1, pitches_per_day + 1):
                label = _signal_label(pitcher_id, rng)
                rows.append(
                    (
                        game_id,
                        gd,
                        pitcher_id,  # at_bat_index (one AB per pitcher per game is enough)
                        pitch_no,
                        pitcher_id,
                        batter_id,
                        label,
                        int(rng.integers(0, 4)),  # balls
                        int(rng.integers(0, 3)),  # strikes
                        int(rng.integers(0, 3)),  # outs
                        int(rng.integers(1, 10)),  # inning
                        int(rng.integers(0, 8)),  # base_state
                        "R" if pitcher_id % 2 else "L",
                        "R" if batter_id % 2 else "L",
                        f"PARK{pitcher_id % 5:02d}",
                        ingested_at,
                    )
                )
    return rows


def _insert_pitches(ch: Client, rows: list[tuple[Any, ...]]) -> None:
    ch.execute(f"INSERT INTO pitches ({', '.join(_PITCH_INSERT_COLS)}) VALUES", rows)


def _build(ch: Client, fold_id: int, encodings_dir: Any) -> None:
    fold = FoldWindow(
        fold_id=fold_id,
        train_start=TRAIN_START,
        train_end=TRAIN_END,
        test_start=TEST_START,
        test_end=TEST_END,
    )
    build_fold_features(ch, fold, encodings_dir=encodings_dir)


def _read_te(ch: Client, fold_id: int) -> list[tuple[float, ...]]:
    cols = ", ".join(TE_COLUMNS)
    # clickhouse_driver's execute() is typed as a broad union; this SELECT returns row tuples.
    return cast(
        "list[tuple[float, ...]]",
        ch.execute(
            f"SELECT {cols} FROM features WHERE fold = {fold_id} "
            f"ORDER BY game_date, game_id, at_bat_index, pitch_number"
        ),
    )


def _mutate_future_labels(rows: list[tuple[Any, ...]]) -> list[tuple[Any, ...]]:
    """Set every label strictly after train_end to 'ball'. game_date is index 1,
    description index 6."""
    out: list[tuple[Any, ...]] = []
    for r in rows:
        if r[1] > TRAIN_END:
            out.append((*r[:6], "ball", *r[7:]))
        else:
            out.append(r)
    return out


def _mutate_train_labels(rows: list[tuple[Any, ...]]) -> list[tuple[Any, ...]]:
    """Set every label inside the train window to 'ball' - the canary mutation."""
    out: list[tuple[Any, ...]] = []
    for r in rows:
        if TRAIN_START <= r[1] <= TRAIN_END:
            out.append((*r[:6], "ball", *r[7:]))
        else:
            out.append(r)
    return out


# --- the leakage assertions ---------------------------------------------------


def test_sql_future_label_mutation_does_not_change_features_te(ch: Client, tmp_path: Any) -> None:
    """The real builder run twice: once on clean pitches, once with every
    post-train-end label corrupted. The TE columns written to `features` must be
    byte-identical. A widened read past train_end (the leak this guards) would
    make them diverge."""
    base_rows = _synthetic_pitch_rows(ingested_at=datetime(2024, 6, 1, 0, 0, 0))

    _recreate_features(ch)
    _recreate_pitches(ch)
    _insert_pitches(ch, base_rows)
    _build(ch, fold_id=1, encodings_dir=tmp_path)

    _recreate_pitches(ch)
    _insert_pitches(ch, _mutate_future_labels(base_rows))
    _build(ch, fold_id=2, encodings_dir=tmp_path)

    baseline = _read_te(ch, 1)
    corrupted = _read_te(ch, 2)

    assert baseline, "no feature rows written - builder or fixture is broken"
    assert len(baseline) == len(corrupted), "row count changed under future mutation"
    assert baseline == corrupted, (
        "TE columns changed after mutating ONLY post-train-end labels - the SQL "
        "builder is reading the future (DEF-H2)"
    )


def test_sql_train_window_mutation_does_change_features_te(ch: Client, tmp_path: Any) -> None:
    """Mutation-test the test itself: corrupting the TRAIN window MUST move the TE.
    If it doesn't, the future-contamination assertion above is vacuous (the builder
    isn't actually reading the train window through this SQL path)."""
    base_rows = _synthetic_pitch_rows(ingested_at=datetime(2024, 6, 1, 0, 0, 0))

    _recreate_features(ch)
    _recreate_pitches(ch)
    _insert_pitches(ch, base_rows)
    _build(ch, fold_id=1, encodings_dir=tmp_path)

    _recreate_pitches(ch)
    _insert_pitches(ch, _mutate_train_labels(base_rows))
    _build(ch, fold_id=2, encodings_dir=tmp_path)

    baseline = _read_te(ch, 1)
    corrupted = _read_te(ch, 2)

    assert baseline and len(baseline) == len(corrupted)
    assert baseline != corrupted, (
        "canary failed: mutating the TRAIN window did not change any TE column - "
        "the SQL path isn't reading the train window, so the leakage test is vacuous"
    )


def test_sql_final_dedups_reingested_train_pitch(ch: Client, tmp_path: Any) -> None:
    """FINAL discipline on the real path (DEF-H3). Re-ingest every train-window
    pitch with a corrected label and a LATER ingested_at. `FROM pitches FINAL`
    must keep only the corrected version, so the target encoding matches a
    single-version corrected build. A bare `FROM pitches` would count both the
    original and the correction, blending the TE toward a phantom distribution."""
    t0 = datetime(2024, 6, 1, 0, 0, 0)
    t1 = datetime(2024, 6, 2, 0, 0, 0)  # later -> wins under ReplacingMergeTree(ingested_at)
    base_rows = _synthetic_pitch_rows(ingested_at=t0)

    # Corrected reference: train-window labels are ALL 'in_play' (single version).
    corrected_rows = [
        (*r[:6], "in_play", *r[7:]) if TRAIN_START <= r[1] <= TRAIN_END else r for r in base_rows
    ]

    # Reference build: only the corrected rows exist.
    _recreate_features(ch)
    _recreate_pitches(ch)
    _insert_pitches(ch, corrected_rows)
    _build(ch, fold_id=1, encodings_dir=tmp_path)

    # Re-ingest build: original rows (t0) THEN the corrected train rows (t1).
    # FINAL must collapse each train key to its t1 (in_play) version.
    _recreate_pitches(ch)
    _insert_pitches(ch, base_rows)
    train_correction = [
        (*r[:6], "in_play", r[7], r[8], r[9], r[10], r[11], r[12], r[13], r[14], t1)
        for r in base_rows
        if TRAIN_START <= r[1] <= TRAIN_END
    ]
    _insert_pitches(ch, train_correction)
    _build(ch, fold_id=2, encodings_dir=tmp_path)

    reference = _read_te(ch, 1)
    deduped = _read_te(ch, 2)

    assert reference and len(reference) == len(deduped)
    assert reference == deduped, (
        "TE from the re-ingested build differs from the single-version corrected "
        "build - the pitch read is double-counting the pre-correction rows, i.e. "
        "`FROM pitches` is missing FINAL (DEF-H3)"
    )


# --- score_diff correctness (no longer a hardcoded 0) -------------------------


def test_score_diff_passes_through_score_diff_live(ch: Client) -> None:
    """score_diff is no longer a hardcoded 0: select_labeled_pitches.sql now passes
    pitches.score_diff_live through (the batting-team lead that transform_raw_to_pitches
    propagates from raw_statcast.bat_score_diff), coalescing a NULL to 0. Drive the real
    production loader and assert the value lands on the right pitch."""
    _recreate_pitches(ch)
    cols = (*_PITCH_INSERT_COLS, "score_diff_live")
    gd = TRAIN_START + timedelta(days=2)
    t = datetime(2024, 6, 1, 0, 0, 0)
    rows = [
        # (..., ingested_at, score_diff_live): batter's side +3, -2, and NULL (-> 0).
        (700_900, gd, 1, 1, 11, 21, "ball", 0, 0, 0, 1, 0, "R", "R", "PARK01", t, 3),
        (700_900, gd, 2, 1, 12, 22, "called_strike", 0, 0, 0, 1, 0, "L", "L", "PARK02", t, -2),
        (700_900, gd, 3, 1, 13, 23, "foul", 0, 0, 0, 1, 0, "R", "L", "PARK03", t, None),
    ]
    ch.execute(f"INSERT INTO pitches ({', '.join(cols)}) VALUES", rows)

    df = load_labeled_pitches(ch, start_date=TRAIN_START, end_date=TRAIN_END)
    got = {int(ab): int(sd) for ab, sd in zip(df["at_bat_index"], df["score_diff"], strict=True)}

    assert got == {
        1: 3,
        2: -2,
        3: 0,
    }, f"score_diff did not pass through score_diff_live (NULL must coalesce to 0): {got}"
