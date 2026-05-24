"""Park-id -> stadium location lookup for the ball-flight simulator (Phase 2c.2).

The only physically-load-bearing field for the simulator today is
``altitude_m`` — air density at altitude drives carry distance more than
anything else (Coors at 1580 m gets the well-known +25 ft on a 400 ft
fly). Latitude / longitude are carried for future weather-pull joins
(planned in 2c.4) but unused by the simulator itself.

Elevations are stadium-floor / field-level above sea level, rounded to
the nearest 10 m from publicly documented values (Wikipedia, MLB park
profiles). For domes (TB, MIL, TOR retractable, HOU retractable) we use
the listed altitude — temperature inside the dome differs from outside
but density is dominated by altitude here, not air-conditioning.

A more refined model (per-park weather, dome temperature regulation,
roof state per game) is a 2c.4-era refinement; this layer just gives
the simulator a defensible default ``Atmosphere`` per park.
"""

from __future__ import annotations

from dataclasses import dataclass

from bullpen_training.battedball.physics.atmosphere import Atmosphere


@dataclass(frozen=True)
class ParkLocation:
    """Static park reference data the simulator can map to an Atmosphere."""

    park_id: str
    name: str
    altitude_m: float
    indoor: bool = False  # dome / retractable closed — defer roof state until 2c.4


# Source: Wikipedia stadium pages cross-referenced with MLB Park Factors.
# Field-level elevation above sea level (m), rounded to nearest 10 m unless
# the source carries finer precision (Coors is well-known to within 1 ft).
_PARKS: dict[str, ParkLocation] = {
    "ARI": ParkLocation("ARI", "Chase Field", 331.0, indoor=True),  # legacy 2-letter code
    "AZ": ParkLocation("AZ", "Chase Field", 331.0, indoor=True),
    "ATH": ParkLocation("ATH", "Oakland Coliseum / Sutter", 8.0),
    "ATL": ParkLocation("ATL", "Truist Park", 280.0),
    "BAL": ParkLocation("BAL", "Camden Yards", 17.0),
    "BOS": ParkLocation("BOS", "Fenway Park", 6.0),
    "CHC": ParkLocation("CHC", "Wrigley Field", 182.0),
    "CIN": ParkLocation("CIN", "Great American Ball Park", 150.0),
    "CLE": ParkLocation("CLE", "Progressive Field", 207.0),
    "COL": ParkLocation("COL", "Coors Field", 1580.0),
    "CWS": ParkLocation("CWS", "Guaranteed Rate Field", 180.0),
    "DET": ParkLocation("DET", "Comerica Park", 180.0),
    "HOU": ParkLocation("HOU", "Minute Maid Park", 7.0, indoor=True),
    "KC": ParkLocation("KC", "Kauffman Stadium", 247.0),
    "LAA": ParkLocation("LAA", "Angel Stadium", 50.0),
    "LAD": ParkLocation("LAD", "Dodger Stadium", 132.0),
    "MIA": ParkLocation("MIA", "loanDepot park", 2.0, indoor=True),
    "MIL": ParkLocation("MIL", "American Family Field", 195.0, indoor=True),
    "MIN": ParkLocation("MIN", "Target Field", 251.0),
    "NYM": ParkLocation("NYM", "Citi Field", 11.0),
    "NYY": ParkLocation("NYY", "Yankee Stadium", 16.0),
    "PHI": ParkLocation("PHI", "Citizens Bank Park", 5.0),
    "PIT": ParkLocation("PIT", "PNC Park", 215.0),
    "SD": ParkLocation("SD", "Petco Park", 17.0),
    "SEA": ParkLocation("SEA", "T-Mobile Park", 60.0, indoor=True),
    "SF": ParkLocation("SF", "Oracle Park", 12.0),
    "STL": ParkLocation("STL", "Busch Stadium", 142.0),
    "TB": ParkLocation("TB", "Tropicana Field", 11.0, indoor=True),
    "TEX": ParkLocation("TEX", "Globe Life Field", 130.0, indoor=True),
    "TOR": ParkLocation("TOR", "Rogers Centre", 76.0, indoor=True),
    "WSH": ParkLocation("WSH", "Nationals Park", 12.0),
}


def get_park(park_id: str) -> ParkLocation:
    """Return the ParkLocation for a MLB team abbreviation.

    Raises ``KeyError`` for unknown codes — callers must handle the
    "park id we don't recognise" case explicitly rather than silently
    defaulting to sea level. The pitches table uses the home-team
    abbreviation as park_id today (see V003__pitches.sql), so any
    abbreviation that hasn't been in MLB since 2015 will fail loud.
    """
    if park_id not in _PARKS:
        raise KeyError(f"unknown park_id {park_id!r}; known codes: {sorted(_PARKS)}")
    return _PARKS[park_id]


def park_atmosphere(
    park_id: str,
    *,
    temp_c: float = 20.0,
    humidity_pct: float = 50.0,
    wind_x_m_s: float = 0.0,
    wind_y_m_s: float = 0.0,
    wind_z_m_s: float = 0.0,
) -> Atmosphere:
    """Build a defensible default :class:`Atmosphere` for a park.

    Only altitude is sourced from the park entry — temperature, humidity
    and wind are caller-overridable defaults (20 °C / 50 % RH / no wind).
    The validation harness in 2c.2 calls this with no overrides; 2c.4 +
    onwards will join against a weather feed.
    """
    park = get_park(park_id)
    return Atmosphere(
        temp_c=temp_c,
        pressure_hpa=None,  # let ISA derive from altitude
        altitude_m=park.altitude_m,
        humidity_pct=humidity_pct,
        wind_x_m_s=wind_x_m_s,
        wind_y_m_s=wind_y_m_s,
        wind_z_m_s=wind_z_m_s,
    )


__all__ = ("ParkLocation", "get_park", "park_atmosphere")
