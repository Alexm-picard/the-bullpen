"""Atmosphere lookup for retrodiction (Phase 2c.4).

For each (game_id, park_id) request, return an :class:`Atmosphere`.
v1 uses the park's default atmosphere with per-park seasonal temperature,
humidity, and prevailing wind. Per-game ``weather_observed`` is a separate
ingest (decision [88]) that will enrich with game-day conditions; until
then, the per-park seasonal defaults capture the dominant cross-park
variation (altitude, temperature, humidity, prevailing wind).

Wind projection: the park JSON stores wind as (speed_mph, bearing_deg)
in meteorological convention (bearing = direction FROM which wind blows,
compass degrees). This module projects it into the simulator's stadium
coordinate frame using ``centerline_bearing_deg``:
  - x = toward CF (positive = blowing out)
  - y = toward 3B (positive = blowing from 1B side toward 3B)
  - z = 0 (no vertical wind component in the default)

The lookup is cached + tiny: 30 entries (one per park). Callers should
treat the returned ``Atmosphere`` as immutable and not mutate it.
"""

from __future__ import annotations

import math
from functools import lru_cache

from bullpen_training.battedball.parks.loader import load_park_geometry
from bullpen_training.battedball.physics.atmosphere import Atmosphere

_MPH_TO_M_S = 0.44704


def _wind_to_stadium_xyz(
    wind_speed_mph: float,
    wind_bearing_deg: float,
    centerline_bearing_deg: float,
) -> tuple[float, float, float]:
    """Convert meteorological wind to the simulator's stadium frame.

    wind_bearing_deg: compass direction the wind blows FROM.
    centerline_bearing_deg: compass bearing of the CF axis from home plate.

    Stadium frame: x = toward CF, y = toward 3B, z = up.
    """
    if wind_speed_mph <= 0:
        return 0.0, 0.0, 0.0
    wind_dir_to = (wind_bearing_deg + 180.0) % 360.0
    angle_from_cf = math.radians(wind_dir_to - centerline_bearing_deg)
    speed_m_s = wind_speed_mph * _MPH_TO_M_S
    wind_x = speed_m_s * math.cos(angle_from_cf)
    wind_y = speed_m_s * math.sin(angle_from_cf)
    return wind_x, wind_y, 0.0


@lru_cache(maxsize=64)
def get_atmosphere(park_id: str) -> Atmosphere:
    """Return the default Atmosphere for a park with per-park seasonal
    temperature, humidity, and prevailing wind projected into the
    stadium coordinate frame.
    """
    park = load_park_geometry(park_id)
    atmo = park.default_atmosphere
    wx, wy, wz = _wind_to_stadium_xyz(
        atmo.wind_speed_mph,
        atmo.wind_bearing_deg,
        park.centerline_bearing_deg,
    )
    return Atmosphere(
        temp_c=atmo.temp_c,
        pressure_hpa=atmo.pressure_hpa,
        altitude_m=park.altitude_m,
        humidity_pct=atmo.humidity_pct,
        wind_x_m_s=wx,
        wind_y_m_s=wy,
        wind_z_m_s=wz,
    )


def get_atmosphere_for_game(park_id: str, game_id: int) -> Atmosphere:
    """Per-(park, game) atmosphere. v1 ignores ``game_id``.

    When ``weather_observed`` lands (decision [88]), this becomes the
    real entry point — it joins the game's row from the weather table
    against the park default and returns a wind+temp-aware Atmosphere.
    """
    _ = game_id  # reserved for the weather upgrade
    return get_atmosphere(park_id)


__all__ = ("get_atmosphere", "get_atmosphere_for_game")
