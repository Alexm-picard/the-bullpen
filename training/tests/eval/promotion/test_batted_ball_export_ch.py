"""Real-ClickHouse coverage for the batted-ball full-data export query (ml-leakage-auditor NOTE 2
on #112). The unit tests in ``test_export_batted_ball_full`` string-match ``build_year_query`` and
inject a fake runner; they cannot catch a bug that only shows against a real schema - a renamed
column, a wrong join key, a dropped ``FINAL``, a type mismatch. This drives the REAL query against a
real ClickHouse with the production-shaped ``pitches`` + ``bbip_retrodicted_labels`` tables, so such
a bug fails loudly here instead of slipping into the box's multi-hour CV run.

It also pins the WHERE contract end-to-end: only in-play BIPs with non-null physics, a home-park
retro row, and a non-null ``observed_outcome`` survive.

CH connection is env-driven (CLICKHOUSE_HOST/PORT/PASSWORD). With no CH reachable - offline dev and
the fast leakage job - the module skips cleanly; the ``leakage-sql-gate`` CI job sets
BULLPEN_REQUIRE_CH=1 so an unreachable CH is a hard failure there.
"""

from __future__ import annotations

import os
from collections.abc import Iterator
from dataclasses import replace
from datetime import date
from pathlib import Path
from typing import Any, cast

import pytest
from bullpen_training.eval.promotion.export_batted_ball_full import export_batted_ball_full
from bullpen_training.eval.promotion.sample_loader import (
    BATTED_BALL_FEATURES,
    RETRO_COLS,
    ParquetSampleLoader,
)
from bullpen_training.ingest.clickhouse_client import ClickHouseSettings, make_client
from clickhouse_driver import Client

TEST_DB = "batted_export_test"
REQUIRE_CH = os.environ.get("BULLPEN_REQUIRE_CH") == "1"


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
    yield client
    client.disconnect()


def _recreate(ch: Client) -> None:
    """Minimal production-shaped pitches + bbip_retrodicted_labels: exactly the columns + types the
    export query reads, same ReplacingMergeTree(ingested_at) + sort keys so FINAL matches prod."""
    ch.execute("DROP TABLE IF EXISTS pitches")
    ch.execute(
        """
        CREATE TABLE pitches
        (
            game_id UInt64,
            game_date Date,
            at_bat_index UInt16,
            pitch_number UInt8,
            description Enum8('ball'=0,'called_strike'=1,'swinging_strike'=2,
                             'foul'=3,'in_play'=4,'hit_by_pitch'=5,'unknown'=99),
            launch_speed_mph Nullable(Float32),
            launch_angle_deg Nullable(Float32),
            hc_x Nullable(Float32),
            hc_y Nullable(Float32),
            hit_distance_ft Nullable(Float32),
            stand FixedString(1),
            outs UInt8,
            base_state UInt8,
            park_id LowCardinality(String),
            ingested_at DateTime DEFAULT now()
        )
        ENGINE = ReplacingMergeTree(ingested_at)
        PARTITION BY toYYYYMM(game_date)
        ORDER BY (game_date, game_id, at_bat_index, pitch_number)
        """
    )
    ch.execute("DROP TABLE IF EXISTS bbip_retrodicted_labels")
    ch.execute(
        """
        CREATE TABLE bbip_retrodicted_labels
        (
            game_date Date,
            game_id UInt64,
            at_bat_index UInt16,
            pitch_number UInt8,
            park_id LowCardinality(String),
            prob_out Float32,
            prob_1b Float32,
            prob_2b Float32,
            prob_3b Float32,
            prob_hr Float32,
            carry_ft Nullable(Float32),
            observed_outcome Nullable(Enum8('out'=0,'1b'=1,'2b'=2,'3b'=3,'hr'=4)),
            ingested_at DateTime DEFAULT now()
        )
        ENGINE = ReplacingMergeTree(ingested_at)
        PARTITION BY toYYYYMM(game_date)
        ORDER BY (park_id, game_date, game_id, at_bat_index, pitch_number)
        """
    )


# Pitch row fields: game_id, gd, ab, pn, description, ls, la, hcx, hcy, dist, stand, outs, bs, park
# game_date is a real date object: clickhouse_driver's Date column needs date, not an ISO string.
_GD = date(2024, 5, 1)
_PITCHES = [
    # A: valid in-play HR at BOS -> survives, label 4.
    (900001, _GD, 1, 1, "in_play", 104.2, 27.5, 100.0, 80.0, 418.0, "R", 1, 0, "BOS"),
    # B: valid in-play out at NYY -> survives, label 0.
    (900002, _GD, 1, 1, "in_play", 88.1, -5.0, 150.0, 150.0, 40.0, "L", 2, 3, "NYY"),
    # C: not in_play -> filtered by description.
    (900003, _GD, 1, 1, "ball", 0.0, 0.0, 120.0, 120.0, 0.0, "R", 0, 0, "BOS"),
    # D: in_play but NULL launch_speed -> filtered by the physics non-null gate.
    (900004, _GD, 1, 1, "in_play", None, 12.0, 90.0, 170.0, 180.0, "R", 0, 0, "LAD"),
    # E: in_play valid, but its retro row has NULL observed_outcome -> filtered by that gate.
    (900005, _GD, 1, 1, "in_play", 95.0, 12.0, 90.0, 170.0, 180.0, "R", 0, 7, "LAD"),
]
# Retro rows keyed to each pitch's home park. E carries NULL observed_outcome.
_RETRO = [
    (_GD, 900001, 1, 1, "BOS", 0.02, 0.03, 0.05, 0.05, 0.85, "hr", 405.0),
    (_GD, 900002, 1, 1, "NYY", 0.93, 0.04, 0.02, 0.01, 0.00, "out", 120.0),
    (_GD, 900003, 1, 1, "BOS", 0.90, 0.05, 0.03, 0.01, 0.01, "out", 60.0),
    (_GD, 900004, 1, 1, "LAD", 0.50, 0.30, 0.10, 0.05, 0.05, "1b", 150.0),
    (_GD, 900005, 1, 1, "LAD", 0.40, 0.45, 0.10, 0.04, 0.01, None, 180.0),
]

_PITCH_COLS = (
    "game_id, game_date, at_bat_index, pitch_number, description, launch_speed_mph, "
    "launch_angle_deg, hc_x, hc_y, hit_distance_ft, stand, outs, base_state, park_id"
)
_RETRO_COLS_SQL = (
    "game_date, game_id, at_bat_index, pitch_number, park_id, "
    "prob_out, prob_1b, prob_2b, prob_3b, prob_hr, observed_outcome, carry_ft"
)


def _ch_runner(ch: Client) -> Any:
    """Adapt the export's TSV-runner contract to clickhouse_driver: strip the docker-only
    ``FORMAT TSV`` (the driver manages its own wire format) and emit the same TSV the docker-exec
    client would, decoding FixedString bytes to match."""

    def run(query: str) -> str:
        rows = cast("list[tuple[Any, ...]]", ch.execute(query.replace("FORMAT TSV", "")))
        return "\n".join(
            "\t".join(c.decode() if isinstance(c, bytes) else str(c) for c in row) for row in rows
        )

    return run


def test_export_query_runs_against_real_ch_schema(ch: Client, tmp_path: Path) -> None:
    _recreate(ch)
    ch.execute(f"INSERT INTO pitches ({_PITCH_COLS}) VALUES", _PITCHES)
    ch.execute(f"INSERT INTO bbip_retrodicted_labels ({_RETRO_COLS_SQL}) VALUES", _RETRO)

    export_batted_ball_full(tmp_path, season_from=2024, season_to=2024, runner=_ch_runner(ch))

    out = ParquetSampleLoader(tmp_path, "batted_ball_mlp")(2024, 2024, fold_id=0)

    # Only A + B survive the in-play / physics-non-null / home-park / observed-outcome gates.
    assert len(out) == 2, f"WHERE contract drifted: expected 2 rows, got {len(out)}"
    assert set(out["park"]) == {"BOS", "NYY"}
    # Schema is the full 15-feature champion vector + label + park + retro.
    for col in (*BATTED_BALL_FEATURES, "label", "park", "carry_ft", *RETRO_COLS):
        assert col in out.columns, f"missing {col}"
    # Label is the realized observed_outcome (BOS row = hr = 4, NYY row = out = 0).
    by_park = {p: lab for p, lab in zip(out["park"], out["label"], strict=True)}
    assert by_park == {"BOS": 4, "NYY": 0}
    # base_state one-hot survived the round-trip (BOS row base_state 0, NYY row 3); exactly one bit.
    bs = out[[f"base_state_{b}" for b in range(8)]].to_numpy()
    assert (bs.sum(axis=1) == 1.0).all()
    # retro is the real distribution and sums to ~1.
    for row in out[list(RETRO_COLS)].to_numpy():
        assert abs(float(row.sum()) - 1.0) < 1e-5


def test_export_empty_year_against_real_ch(ch: Client, tmp_path: Path) -> None:
    """A year with no qualifying BIPs writes a well-formed empty parquet (not a crash / missing file
    the loader then trips on)."""
    _recreate(ch)  # no rows inserted
    export_batted_ball_full(tmp_path, season_from=2024, season_to=2024, runner=_ch_runner(ch))
    out = ParquetSampleLoader(tmp_path, "batted_ball_mlp")(2024, 2024, fold_id=0)
    assert len(out) == 0
    for col in (*BATTED_BALL_FEATURES, "label"):
        assert col in out.columns
