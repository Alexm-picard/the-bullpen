"""Unit tests for the Statcast pull module.

ClickHouse integration is covered by the live drill at the end of Phase 1.1
(documented in the leaf plan). Here we lock down the pure transformations:
column projection, dtype coercion, month-window math, and chunk iteration.
"""

from __future__ import annotations

from datetime import date

import numpy as np
import pandas as pd
import pytest

from bullpen_training.ingest.clickhouse_client import _row_tuples
from bullpen_training.ingest.statcast_pull import (
    RAW_STATCAST_COLUMNS,
    month_window,
    normalize_columns,
)


def _fake_statcast_row(**overrides: object) -> dict[str, object]:
    base: dict[str, object] = {
        "game_pk": 745001,
        "game_date": "2024-04-15",
        "game_type": "R",
        "home_team": "NYY",
        "away_team": "BOS",
        "at_bat_number": 12,  # pybaseball's name; normalize maps to at_bat_index
        "pitch_number": 3,
        "pitcher": 543037,
        "batter": 605141,
        "stand": "R",
        "p_throws": "R",
        "balls": 1,
        "strikes": 2,
        "inning": 5,
        "inning_topbot": "Top",
        "outs_when_up": 1,
        "on_1b": None,
        "on_2b": 593160,
        "on_3b": None,
        "pitch_type": "FF",
        "release_speed": 95.3,
        "release_pos_x": -1.42,
        "release_pos_z": 5.81,
        "plate_x": 0.12,
        "plate_z": 2.45,
        "sz_top": 3.41,
        "sz_bot": 1.62,
        "description": "hit_into_play",
        "events": "single",
        "type": "X",
        "bb_type": "ground_ball",
        "launch_speed": 88.2,
        "launch_angle": 4.0,
        "hit_distance_sc": 84.0,
        "hc_x": 132.5,
        "hc_y": 156.0,
    }
    base.update(overrides)
    return base


def test_month_window_april() -> None:
    assert month_window(2024, 4) == (date(2024, 4, 1), date(2024, 4, 30))


def test_month_window_february_leap() -> None:
    assert month_window(2024, 2) == (date(2024, 2, 1), date(2024, 2, 29))


def test_month_window_rejects_invalid() -> None:
    with pytest.raises(ValueError):
        month_window(2024, 13)


def test_normalize_projects_to_schema() -> None:
    df = pd.DataFrame([_fake_statcast_row()])
    out = normalize_columns(df)
    assert list(out.columns) == list(RAW_STATCAST_COLUMNS)
    assert len(out) == 1


def test_normalize_maps_at_bat_number_to_index() -> None:
    """Regression: pybaseball renamed at_bat_index → at_bat_number; the
    normalizer must alias it back so the V002 schema column populates."""
    df = pd.DataFrame([_fake_statcast_row(at_bat_number=42)])
    out = normalize_columns(df)
    assert out["at_bat_index"].iloc[0] == 42


def test_normalize_drops_extra_columns() -> None:
    row = _fake_statcast_row()
    # A real pybaseball column that is NOT in RAW_STATCAST_COLUMNS — the
    # normalizer keeps the schema allowlist and drops everything else.
    # (fielder_2 used to live here, but the 56-column expansion for the
    # fielder model (decision [132]) promoted it into the kept schema.)
    row["spin_dir"] = 270  # deprecated pybaseball column, never in our schema
    df = pd.DataFrame([row])
    out = normalize_columns(df)
    assert "spin_dir" not in out.columns


def test_normalize_fills_missing_columns_with_null() -> None:
    row = _fake_statcast_row()
    del row["hit_distance_sc"]
    df = pd.DataFrame([row])
    out = normalize_columns(df)
    assert "hit_distance_sc" in out.columns
    assert pd.isna(out["hit_distance_sc"].iloc[0])


def test_normalize_coerces_dates() -> None:
    df = pd.DataFrame([_fake_statcast_row(game_date="2024-07-04")])
    out = normalize_columns(df)
    assert out["game_date"].iloc[0] == date(2024, 7, 4)


def test_normalize_handles_nullable_ints() -> None:
    df = pd.DataFrame(
        [
            _fake_statcast_row(on_1b=np.nan, balls=np.nan),
            _fake_statcast_row(on_1b=12345, balls=2),
        ]
    )
    out = normalize_columns(df)
    assert out["on_1b"].iloc[0] is None
    assert out["on_1b"].iloc[1] == 12345
    assert out["balls"].iloc[0] is None
    assert out["balls"].iloc[1] == 2


def test_normalize_coerces_floats_to_float32() -> None:
    df = pd.DataFrame([_fake_statcast_row(launch_speed="88.2")])
    out = normalize_columns(df)
    assert out["launch_speed"].dtype == np.float32


def test_normalize_replaces_missing_strings_with_empty() -> None:
    df = pd.DataFrame([_fake_statcast_row(events=np.nan)])
    out = normalize_columns(df)
    assert out["events"].iloc[0] == ""


def test_row_tuples_converts_nan_to_none() -> None:
    df = pd.DataFrame(
        [
            {"a": 1, "b": np.nan, "c": "x"},
            {"a": 2, "b": 3.14, "c": None},
        ]
    )
    rows = list(_row_tuples(df, ["a", "b", "c"]))
    assert rows == [(1, None, "x"), (2, 3.14, None)]
