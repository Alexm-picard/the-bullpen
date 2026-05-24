"""Tests for the BIP-streaming + TSV-writer side of the pipeline (2c.4).

The pipeline talks to ClickHouse via `docker exec`, which is awkward
to test in CI. Instead the tests cover the pure-Python pieces:
spray-angle transform, TSV row parsing, and TSV row rendering. The
end-to-end ClickHouse path is exercised manually with `--limit 100
--dry-run` as part of the smoke check.
"""

from __future__ import annotations

import pytest

from bullpen_training.battedball.retrodict.labels import BBIP, RetrodictionResult
from bullpen_training.battedball.retrodict.run_pipeline import (
    _result_to_tsv_row,
    _row_to_bbip,
    _spray_deg_from_hc,
)


def test_spray_deg_from_hc_dead_center() -> None:
    """A ball that landed at home-plate XY -> ~0 deg spray."""
    s = _spray_deg_from_hc(hc_x=125.42, hc_y=50.0)
    assert s == pytest.approx(0.0, abs=0.01)


def test_spray_deg_from_hc_pulled_to_lf_for_rhb() -> None:
    """A low hc_x (LF side) should yield +ve spray (toward 3B) — our
    simulator convention. Statcast's hc_x increases left -> right
    (LF -> RF), so LF means hc_x < 125.42."""
    s = _spray_deg_from_hc(hc_x=40.0, hc_y=80.0)
    assert s > 0, f"low hc_x should be +spray (LF), got {s:.2f}"


def test_spray_deg_from_hc_pulled_to_rf() -> None:
    """High hc_x (RF side) -> -ve spray (toward 1B)."""
    s = _spray_deg_from_hc(hc_x=210.0, hc_y=80.0)
    assert s < 0, f"high hc_x should be -spray (RF), got {s:.2f}"


def test_row_to_bbip_parses_clean_row() -> None:
    row = [
        "2024-07-15",
        "746000123",
        "42",
        "4",
        "NYY",
        "110.5",
        "28.0",
        "180.0",  # hc_x (slight RF)
        "60.0",  # hc_y
        "home_run",
    ]
    bbip = _row_to_bbip(row)
    assert bbip is not None
    assert bbip.game_date == "2024-07-15"
    assert bbip.game_id == 746000123
    assert bbip.home_park_id == "NYY"
    assert bbip.launch_speed_mph == 110.5
    assert bbip.observed_event == "home_run"
    # hc_x=180 > 125 -> negative spray (RF for RHB)
    assert bbip.spray_angle_deg < 0


def test_row_to_bbip_returns_none_on_short_row() -> None:
    assert _row_to_bbip(["just", "two", "fields"]) is None


def test_row_to_bbip_returns_none_on_empty_park() -> None:
    row = [
        "2024-07-15",
        "746000123",
        "42",
        "4",
        "",
        "110.5",
        "28.0",
        "180.0",
        "60.0",
        "home_run",
    ]
    assert _row_to_bbip(row) is None


def test_result_to_tsv_row_observed_outcome_null_on_away_park() -> None:
    bbip = BBIP(
        game_date="2024-07-15",
        game_id=746000123,
        at_bat_index=42,
        pitch_number=4,
        home_park_id="NYY",
        launch_speed_mph=110.0,
        launch_angle_deg=28.0,
        spray_angle_deg=-30.0,
        spin_rate_rpm=1800.0,
        spin_axis_tilt_deg=180.0,
        observed_event="home_run",
    )
    r = RetrodictionResult(
        bbip=bbip,
        park_id="COL",
        is_home_park=False,
        prob_out=0.1,
        prob_1b=0.1,
        prob_2b=0.2,
        prob_3b=0.0,
        prob_hr=0.6,
        observed_outcome=None,
        n_mc=10,
    )
    tsv = _result_to_tsv_row(r)
    fields = tsv.split("\t")
    assert fields[0] == "2024-07-15"
    assert fields[4] == "COL"
    assert fields[5] == "0"  # is_home_park
    assert fields[-2] == "\\N"  # observed_outcome NULL marker for ClickHouse
    assert fields[-1] == "10"


def test_result_to_tsv_row_home_park_carries_observed_outcome() -> None:
    bbip = BBIP(
        game_date="2024-07-15",
        game_id=746000123,
        at_bat_index=42,
        pitch_number=4,
        home_park_id="NYY",
        launch_speed_mph=110.0,
        launch_angle_deg=28.0,
        spray_angle_deg=-30.0,
        spin_rate_rpm=1800.0,
        spin_axis_tilt_deg=180.0,
        observed_event="home_run",
    )
    r = RetrodictionResult(
        bbip=bbip,
        park_id="NYY",
        is_home_park=True,
        prob_out=0.0,
        prob_1b=0.0,
        prob_2b=0.0,
        prob_3b=0.0,
        prob_hr=1.0,
        observed_outcome="hr",
        n_mc=10,
    )
    tsv = _result_to_tsv_row(r)
    fields = tsv.split("\t")
    assert fields[5] == "1"  # is_home_park
    assert fields[-2] == "hr"
