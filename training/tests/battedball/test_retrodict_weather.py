"""Per-game weather wiring for retrodiction (Phase 2c.4 weather upgrade).

Locks the field-relative wind mapping, the projection of a game's observed
weather onto a park, the still-air fallback, and — end to end through the
physics — that an out-blowing wind carries a ball farther than an in-blowing
one (the sign wiring that fixes the cross-park HR ranking).
"""

from __future__ import annotations

import math

import pytest

from bullpen_training.battedball.parks.loader import load_park_geometry
from bullpen_training.battedball.physics.simulator import LaunchParams, simulate
from bullpen_training.battedball.retrodict._atmospheres import (
    Weather,
    parse_wind_label,
    still_air_atmosphere,
    weather_to_atmosphere,
)

_SQRT_HALF = math.sqrt(0.5)


# --- parse_wind_label: field-relative label -> stadium-frame unit vector ----


@pytest.mark.parametrize(
    ("label", "expected"),
    [
        ("Out To CF", (1.0, 0.0)),
        ("In From CF", (-1.0, 0.0)),
        ("Out To LF", (_SQRT_HALF, _SQRT_HALF)),
        ("Out To RF", (_SQRT_HALF, -_SQRT_HALF)),
        ("In From LF", (-_SQRT_HALF, -_SQRT_HALF)),
        ("In From RF", (-_SQRT_HALF, _SQRT_HALF)),
        ("L To R", (0.0, -1.0)),
        ("R To L", (0.0, 1.0)),
        ("Calm", (0.0, 0.0)),
        ("Indoors", (0.0, 0.0)),
        ("", (0.0, 0.0)),
        ("Varies", (0.0, 0.0)),
    ],
)
def test_parse_wind_label_canonical(label: str, expected: tuple[float, float]) -> None:
    x, y = parse_wind_label(label)
    assert x == pytest.approx(expected[0], abs=1e-9)
    assert y == pytest.approx(expected[1], abs=1e-9)


@pytest.mark.parametrize(
    "label",
    ["out to cf", "OUT TO CF", "8 mph, Out To CF", "Out To Center Field"],
)
def test_parse_wind_label_tolerant(label: str) -> None:
    """Case, an embedded speed prefix, and field-word aliases all resolve."""
    assert parse_wind_label(label) == pytest.approx((1.0, 0.0))


def test_parse_wind_label_unknown_is_zero() -> None:
    assert parse_wind_label("sideways and up") == (0.0, 0.0)


# --- Weather.from_observed -------------------------------------------------


def test_weather_from_observed_decomposes_wind() -> None:
    w = Weather.from_observed(
        game_id=42,
        temp_f=72.0,
        wind_speed_mph=10.0,
        wind_dir_label="Out To CF",
        is_indoor=False,
    )
    assert w.temp_c == pytest.approx((72.0 - 32.0) * 5.0 / 9.0)
    assert w.wind_speed_m_s == pytest.approx(10.0 * 0.44704)
    assert (w.wind_out_x, w.wind_out_y) == pytest.approx((1.0, 0.0))
    assert w.is_indoor is False


def test_weather_from_observed_indoor_zeroes_wind() -> None:
    w = Weather.from_observed(
        game_id=42, temp_f=72.0, wind_speed_mph=10.0, wind_dir_label="Indoors", is_indoor=True
    )
    assert w.is_indoor is True
    assert w.wind_speed_m_s == 0.0
    assert (w.wind_out_x, w.wind_out_y) == (0.0, 0.0)


def test_weather_from_observed_missing_temp() -> None:
    w = Weather.from_observed(
        game_id=1, temp_f=None, wind_speed_mph=5.0, wind_dir_label="L To R", is_indoor=False
    )
    assert w.temp_c is None


# --- weather_to_atmosphere -------------------------------------------------


def _weather(out_x: float, out_y: float, speed_mph: float = 15.0, temp_c: float | None = 21.0):
    return Weather(
        game_id=1,
        temp_c=temp_c,
        wind_speed_m_s=speed_mph * 0.44704,
        wind_out_x=out_x,
        wind_out_y=out_y,
        is_indoor=False,
    )


def test_weather_to_atmosphere_wind_signs() -> None:
    park = load_park_geometry("NYY")
    out = weather_to_atmosphere(_weather(1.0, 0.0), park)  # Out To CF
    assert out.wind_x_m_s > 0
    assert out.wind_y_m_s == pytest.approx(0.0, abs=1e-9)

    inward = weather_to_atmosphere(_weather(-1.0, 0.0), park)  # In From CF
    assert inward.wind_x_m_s < 0

    l_to_r = weather_to_atmosphere(_weather(0.0, -1.0), park)  # toward 1B
    assert l_to_r.wind_y_m_s < 0
    assert l_to_r.wind_x_m_s == pytest.approx(0.0, abs=1e-9)


def test_weather_to_atmosphere_keeps_park_altitude() -> None:
    """The game's wind/temp travel across parks, but altitude stays the park's."""
    coors = load_park_geometry("COL")
    nyy = load_park_geometry("NYY")
    w = _weather(1.0, 0.0)
    assert weather_to_atmosphere(w, coors).altitude_m == coors.altitude_m
    assert weather_to_atmosphere(w, nyy).altitude_m == nyy.altitude_m
    assert coors.altitude_m > nyy.altitude_m  # sanity: Coors is the high one


def test_weather_to_atmosphere_temp_fallback() -> None:
    park = load_park_geometry("NYY")
    atmo = weather_to_atmosphere(_weather(1.0, 0.0, temp_c=None), park)
    assert atmo.temp_c == park.default_atmosphere.temp_c


def test_still_air_atmosphere_has_no_wind() -> None:
    park = load_park_geometry("CHC")
    atmo = still_air_atmosphere(park)
    assert (atmo.wind_x_m_s, atmo.wind_y_m_s, atmo.wind_z_m_s) == (0.0, 0.0, 0.0)
    assert atmo.altitude_m == park.altitude_m


# --- end to end through the physics ----------------------------------------


def test_out_wind_carries_farther_than_in_wind() -> None:
    """A dead-center ball travels farther with an out-blowing wind than calm,
    and farther calm than into an in-blowing wind — the sign wiring that makes
    real game wind move HR probabilities the right direction."""
    park = load_park_geometry("NYY")
    launch = LaunchParams(
        launch_speed_mph=103.0,
        launch_angle_deg=28.0,
        spray_angle_deg=0.0,  # dead CF → aligned with the +x (out) wind axis
        spin_rate_rpm=1800.0,
        spin_axis_tilt_deg=180.0,
        initial_height_m=1.0,
    )

    def carry(out_x: float, speed_mph: float) -> float:
        w = _weather(out_x, 0.0, speed_mph=speed_mph)
        return simulate(launch, weather_to_atmosphere(w, park)).distance_ft

    out = carry(1.0, 18.0)
    calm = carry(0.0, 0.0)
    into = carry(-1.0, 18.0)
    assert out > calm > into
