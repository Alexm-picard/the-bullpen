"""Atmosphere lookup for retrodiction (Phase 2c.4).

Two atmosphere sources live here:

1. **Per-game observed weather** (the production path since the 2c.4 weather
   upgrade). :func:`load_weather_observed` bulk-loads the ``weather_observed``
   table (decision [88], the post-game observed pull) into ``{game_id: Weather}``,
   and :func:`weather_to_atmosphere` projects one game's actual wind + temperature
   onto any park. The retrodiction pipeline applies each BIP's *actual*
   field-relative wind identically at all 30 counterfactual parks (varying only
   per-park altitude/geometry), which isolates each park's physical HR factor —
   the fix for the scrambled 2c.7 cross-park ranking.

2. **Seasonal default** (:func:`get_atmosphere`). The park's bundled
   ``default_atmosphere`` with its single seasonal prevailing wind. This is the
   legacy path that, applied per-park to every BIP, scrambled the ranking (a
   constant out-blowing wind made Oakland look like a launching pad). Kept only
   for callers that have no per-game weather; the pipeline's no-weather fallback
   is :func:`still_air_atmosphere`, NOT this, so a missing weather row never
   reintroduces the seasonal-wind bias.

Wind frames:
  - ``weather_observed`` stores wind **field-relative** exactly as the MLB feed
    reports it (``wind_dir_label``, e.g. "Out To CF" / "L To R"). The simulator's
    stadium frame is already field-relative (x = toward CF, y = toward 3B/LF), so
    :func:`parse_wind_label` maps the label straight to a unit vector with no
    centerline projection.
  - The seasonal default stores wind in meteorological compass convention and so
    needs :func:`_wind_to_stadium_xyz` (kept for that path only).
"""

from __future__ import annotations

import math
import subprocess
from dataclasses import dataclass
from functools import lru_cache
from typing import Final

from bullpen_training.battedball.parks.loader import ParkGeometry, load_park_geometry
from bullpen_training.battedball.physics.atmosphere import Atmosphere

_MPH_TO_M_S: Final[float] = 0.44704
_SQRT_HALF: Final[float] = math.sqrt(0.5)


def _f_to_c(temp_f: float) -> float:
    return (temp_f - 32.0) * 5.0 / 9.0


# --- field-relative wind labels -------------------------------------------

# MLB Stats API reports wind direction field-relative ("Out To CF", "In From
# LF", "L To R", ...). The simulator's stadium frame is also field-relative:
#   x = toward CF (positive = blowing OUT)
#   y = toward 3B / LF (positive = toward the 3B side)
# so each label maps directly to a unit (x, y) — no compass / centerline math.
# LF sits on the +y (3B) side, RF on the -y (1B) side.
_WIND_LABEL_VECTORS: Final[dict[str, tuple[float, float]]] = {
    "out to cf": (1.0, 0.0),
    "in from cf": (-1.0, 0.0),
    "out to lf": (_SQRT_HALF, _SQRT_HALF),
    "out to rf": (_SQRT_HALF, -_SQRT_HALF),
    "in from lf": (-_SQRT_HALF, -_SQRT_HALF),
    "in from rf": (-_SQRT_HALF, _SQRT_HALF),
    "l to r": (0.0, -1.0),
    "r to l": (0.0, 1.0),
}

# No-wind labels: calm, indoor/dome games, or unparseable.
_ZERO_WIND_LABELS: Final[frozenset[str]] = frozenset(
    {"", "calm", "indoors", "indoor", "dome", "roof closed", "varies", "none"}
)


def parse_wind_label(label: str) -> tuple[float, float]:
    """Field-relative wind label -> stadium-frame unit vector ``(x, y)``.

    ``x`` is toward CF (out), ``y`` toward 3B/LF. Tolerant of aliases
    ("Left Field" -> "lf"), an embedded speed prefix ("8 mph, Out To CF"),
    and case. Unknown / calm / indoor labels return ``(0.0, 0.0)``.

    This is the single source of truth for the label -> vector mapping; the
    ``weather_observed`` table stores only the cleaned label string.
    """
    s = label.strip().lower()
    # Drop a leading "<n> mph," speed prefix if a full feed string slipped in.
    if "," in s:
        s = s.rsplit(",", 1)[-1].strip()
    s = s.replace("mph", "").strip()
    if s in _ZERO_WIND_LABELS:
        return 0.0, 0.0
    # Alias field words to the lf/rf/cf tokens used in the vector table.
    s = (
        s.replace("center field", "cf")
        .replace("centerfield", "cf")
        .replace("center", "cf")
        .replace("left field", "lf")
        .replace("leftfield", "lf")
        .replace("right field", "rf")
        .replace("rightfield", "rf")
    )
    # "out to left" / "in from right" forms.
    s = s.replace(" left", " lf").replace(" right", " rf")
    return _WIND_LABEL_VECTORS.get(s, (0.0, 0.0))


# --- per-game observed weather --------------------------------------------


@dataclass(frozen=True)
class Weather:
    """One game's observed conditions, park-agnostic and pre-projected.

    Wind is held in the field-relative stadium frame (unit vector
    ``(wind_out_x, wind_out_y)`` times ``wind_speed_m_s``) so it can be applied
    at any park without re-projection — that is exactly the "same actual wind at
    all 30 parks" counterfactual. ``temp_c`` is ``None`` when the feed omitted
    temperature, in which case :func:`weather_to_atmosphere` falls back to the
    target park's seasonal temperature.
    """

    game_id: int
    temp_c: float | None
    wind_speed_m_s: float
    wind_out_x: float
    wind_out_y: float
    is_indoor: bool

    @classmethod
    def from_observed(
        cls,
        *,
        game_id: int,
        temp_f: float | None,
        wind_speed_mph: float,
        wind_dir_label: str,
        is_indoor: bool,
    ) -> Weather:
        """Build from a ``weather_observed`` row (field-relative label)."""
        if is_indoor:
            return cls(
                game_id=game_id,
                temp_c=None if temp_f is None else _f_to_c(temp_f),
                wind_speed_m_s=0.0,
                wind_out_x=0.0,
                wind_out_y=0.0,
                is_indoor=True,
            )
        out_x, out_y = parse_wind_label(wind_dir_label)
        return cls(
            game_id=game_id,
            temp_c=None if temp_f is None else _f_to_c(temp_f),
            wind_speed_m_s=wind_speed_mph * _MPH_TO_M_S,
            wind_out_x=out_x,
            wind_out_y=out_y,
            is_indoor=False,
        )


def weather_to_atmosphere(weather: Weather, park: ParkGeometry) -> Atmosphere:
    """Project one game's observed weather onto a park's geometry.

    Per-park altitude (intrinsic) + the game's actual temperature and
    field-relative wind. Humidity is not in the feed, so we use the park's
    seasonal humidity (a small density effect). Pressure stays ``None`` → ISA
    standard at the park's altitude. Indoor games carry zero wind.
    """
    temp_c = weather.temp_c if weather.temp_c is not None else park.default_atmosphere.temp_c
    return Atmosphere(
        temp_c=temp_c,
        pressure_hpa=None,
        altitude_m=park.altitude_m,
        humidity_pct=park.default_atmosphere.humidity_pct,
        wind_x_m_s=weather.wind_speed_m_s * weather.wind_out_x,
        wind_y_m_s=weather.wind_speed_m_s * weather.wind_out_y,
        wind_z_m_s=0.0,
    )


def still_air_atmosphere(park: ParkGeometry) -> Atmosphere:
    """No-wind atmosphere at a park's altitude + seasonal temp/humidity.

    The retrodiction fallback when a game has no ``weather_observed`` row. We use
    still air rather than :func:`get_atmosphere` so a missing weather row never
    reintroduces the per-park seasonal prevailing wind that scrambled the
    cross-park ranking.
    """
    atmo = park.default_atmosphere
    return Atmosphere(
        temp_c=atmo.temp_c,
        pressure_hpa=None,
        altitude_m=park.altitude_m,
        humidity_pct=atmo.humidity_pct,
        wind_x_m_s=0.0,
        wind_y_m_s=0.0,
        wind_z_m_s=0.0,
    )


def _run_clickhouse(query: str, *, container: str = "bullpen-clickhouse") -> str:
    """Execute a query in the local Docker ClickHouse and return stdout TSV.

    Mirrors ``run_pipeline._run_clickhouse`` so the whole retrodiction path uses
    one ClickHouse access pattern on the desktop.
    """
    res = subprocess.run(
        ["docker", "exec", container, "clickhouse-client", "--query", query],
        check=True,
        capture_output=True,
        text=True,
    )
    return res.stdout


def _weather_query(*, season_from: int, season_to: int) -> str:
    return (
        "SELECT "
        "toString(game_id) AS game_id_str, "
        "toString(temp_f) AS temp_f_str, "
        "toString(wind_speed_mph) AS wind_speed_mph_str, "
        "wind_dir_label, "
        "toString(is_indoor) AS is_indoor_str "
        "FROM weather_observed FINAL "
        f"WHERE toYear(game_date) BETWEEN {season_from} AND {season_to} "
        "FORMAT TSV"
    )


def _weather_from_row(row: list[str]) -> Weather | None:
    """One TSV row from :func:`_weather_query` -> Weather (None on malformed)."""
    try:
        game_id_str, temp_f_str, wind_speed_str, wind_dir_label, is_indoor_str = row
    except ValueError:
        return None
    try:
        game_id = int(game_id_str)
    except ValueError:
        return None
    temp_f = None if temp_f_str in ("", "\\N") else float(temp_f_str)
    wind_speed_mph = 0.0 if wind_speed_str in ("", "\\N") else float(wind_speed_str)
    is_indoor = is_indoor_str.strip() == "1"
    return Weather.from_observed(
        game_id=game_id,
        temp_f=temp_f,
        wind_speed_mph=wind_speed_mph,
        wind_dir_label=wind_dir_label,
        is_indoor=is_indoor,
    )


def load_weather_observed(
    season_from: int,
    season_to: int,
    *,
    container: str = "bullpen-clickhouse",
) -> dict[int, Weather]:
    """Bulk-load ``weather_observed`` for a season range -> ``{game_id: Weather}``.

    One ClickHouse query; the table is tiny (one row per game, ≤ ~2.4 k/season).
    Games absent from the result have no observed weather and the pipeline falls
    back to :func:`still_air_atmosphere`.
    """
    tsv = _run_clickhouse(
        _weather_query(season_from=season_from, season_to=season_to), container=container
    )
    out: dict[int, Weather] = {}
    for line in tsv.strip().split("\n"):
        if not line:
            continue
        weather = _weather_from_row(line.split("\t"))
        if weather is not None:
            out[weather.game_id] = weather
    return out


# --- seasonal default (legacy / no-weather path) --------------------------


def _wind_to_stadium_xyz(
    wind_speed_mph: float,
    wind_bearing_deg: float,
    centerline_bearing_deg: float,
) -> tuple[float, float, float]:
    """Convert meteorological compass wind to the simulator's stadium frame.

    Used only by the seasonal-default path (the park JSON stores wind in compass
    convention). ``wind_bearing_deg`` is the direction the wind blows FROM;
    ``centerline_bearing_deg`` is the compass bearing of the CF axis.

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
    """Return the seasonal-default Atmosphere for a park.

    Per-park seasonal temperature, humidity, and prevailing wind projected into
    the stadium frame. Legacy / no-weather path only — the retrodiction pipeline
    uses :func:`weather_to_atmosphere` (real weather) or
    :func:`still_air_atmosphere` (fallback) instead, because applying this
    single seasonal wind to every BIP scrambled the cross-park HR ranking.
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
    """Legacy per-(park, game) entry point; ignores ``game_id``.

    Superseded by :func:`load_weather_observed` + :func:`weather_to_atmosphere`,
    which the pipeline uses for the real per-game weather join. Retained for
    callers that predate the weather upgrade.
    """
    _ = game_id
    return get_atmosphere(park_id)


__all__ = (
    "Weather",
    "get_atmosphere",
    "get_atmosphere_for_game",
    "load_weather_observed",
    "parse_wind_label",
    "still_air_atmosphere",
    "weather_to_atmosphere",
)
