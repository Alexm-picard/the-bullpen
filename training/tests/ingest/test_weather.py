"""Unit tests for the MLB Stats API weather parsing (Phase 2c.4 weather upgrade).

Pins the pure transformations — wind-string parsing, temperature extraction, and
feed → :class:`RawWeather` normalisation including indoor detection. The HTTP
boundary itself is exercised only via :func:`weather_from_feed` on canned feed
payloads (MLB Stats API is the one mock-acceptable external boundary).
"""

from __future__ import annotations

import pytest

from bullpen_training.ingest.weather import (
    RawWeather,
    _parse_temp,
    parse_wind,
    weather_from_feed,
)


@pytest.mark.parametrize(
    ("wind_str", "expected"),
    [
        ("8 mph, Out To CF", (8, "Out To CF", False)),
        ("12 MPH, In From LF", (12, "In From LF", False)),
        ("5 mph, L To R", (5, "L To R", False)),
        ("7 mph, R To L", (7, "R To L", False)),
        ("10 mph, Out To RF", (10, "Out To RF", False)),
        ("Calm", (0, "Calm", False)),
        ("0 mph", (0, "Calm", False)),
        ("Indoors", (0, "Indoors", True)),
        ("", (None, "", False)),
        ("7 mph, Out To Left Field", (7, "Out To LF", False)),
    ],
)
def test_parse_wind(wind_str: str, expected: tuple[int | None, str, bool]) -> None:
    assert parse_wind(wind_str) == expected


def test_parse_wind_none() -> None:
    assert parse_wind(None) == (None, "", False)


@pytest.mark.parametrize(
    ("raw", "expected"),
    [("72", 72), ("75°", 75), ("-3", -3), ("", None), (None, None), (68, 68)],
)
def test_parse_temp(raw: object, expected: int | None) -> None:
    assert _parse_temp(raw) == expected


def _feed(*, condition: str, temp: str | None, wind: str | None) -> dict[str, object]:
    weather: dict[str, object] = {"condition": condition}
    if temp is not None:
        weather["temp"] = temp
    if wind is not None:
        weather["wind"] = wind
    return {"gameData": {"weather": weather}}


def test_weather_from_feed_outdoor() -> None:
    out = weather_from_feed(745001, _feed(condition="Clear", temp="75", wind="10 mph, Out To RF"))
    assert out == RawWeather(
        game_pk=745001,
        condition="Clear",
        temp_f=75,
        wind_speed_mph=10,
        wind_dir_label="Out To RF",
        is_indoor=False,
    )


def test_weather_from_feed_dome_is_indoor() -> None:
    out = weather_from_feed(745002, _feed(condition="Dome", temp="72", wind="Indoors"))
    assert out is not None
    assert out.is_indoor is True
    assert out.wind_speed_mph == 0
    assert out.wind_dir_label == "Indoors"


def test_weather_from_feed_roof_closed_condition() -> None:
    """Indoor inferred from condition even when the wind string is empty."""
    out = weather_from_feed(745003, _feed(condition="Roof Closed", temp="70", wind=""))
    assert out is not None
    assert out.is_indoor is True
    assert out.wind_speed_mph == 0


def test_weather_from_feed_missing_weather_returns_none() -> None:
    assert weather_from_feed(745004, {"gameData": {}}) is None
    assert weather_from_feed(745004, {}) is None
    assert weather_from_feed(745004, {"gameData": {"weather": {}}}) is None
