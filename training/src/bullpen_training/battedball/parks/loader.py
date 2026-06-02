"""Park-geometry loader + interpolation helpers (Phase 2c.3).

One JSON file per park lives at ``infra/park_geometry/<park_id>.json``.
Schema sits next to them at ``infra/park_geometry/_schema.json`` and is
validated on every load — bad data raises ``ParkGeometryError`` before
the simulator gets to see it.

Coordinates / units convention (matches the simulator):
  - distances + heights in feet (the published park-dimensions standard)
  - spray angle: + toward 3B (LF), - toward 1B (RF), 0 = CF
  - altitude in meters (matches Atmosphere.altitude_m for the
    ``park_atmosphere`` default)
"""

from __future__ import annotations

import json
import os
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path
from typing import Any

# jsonschema is the lone heavy-ish dep here; we already use it elsewhere
# in the contracts validator so it's not new.
import jsonschema

# Repo-relative path to the canonical (prod) park-geometry tree. Resolved
# from this file's location so it works under pytest cwd, the worker JAR
# packaging, and bare `python -m` invocations the same way.
_DEFAULT_GEOMETRY_DIR = Path(__file__).resolve().parents[5] / "infra" / "park_geometry"


def _resolve_geometry_dir() -> Path:
    """Park-file directory: ``BULLPEN_PARK_GEOMETRY_DIR`` override else the default.

    The override lets the retrodiction pipeline read a *staged* high-res geometry
    set (e.g. the empirical-fence estimator's output) without overwriting the
    committed prod JSONs — so a geometry experiment is reversible (decision [52]
    2c.7 work). The schema always loads from the canonical dir.
    """
    env = os.environ.get("BULLPEN_PARK_GEOMETRY_DIR")
    return Path(env).resolve() if env else _DEFAULT_GEOMETRY_DIR


_GEOMETRY_DIR = _resolve_geometry_dir()
_SCHEMA_PATH = _DEFAULT_GEOMETRY_DIR / "_schema.json"


class ParkGeometryError(RuntimeError):
    """Raised when a park JSON fails to load or validate against the schema."""


@dataclass(frozen=True)
class FencePoint:
    """One point on the outfield-fence polyline."""

    angle_from_centerline_deg: float
    distance_ft: float
    height_ft: float


@dataclass(frozen=True)
class DefaultAtmosphere:
    """Default atmosphere bundled into a park JSON (no per-game weather)."""

    temp_c: float
    pressure_hpa: float | None
    humidity_pct: float
    wind_speed_mph: float = 0.0
    wind_bearing_deg: float = 0.0


@dataclass(frozen=True)
class ParkGeometry:
    """A loaded park-geometry record. Use :func:`load_park_geometry`.

    The polyline is stored sorted by ``angle_from_centerline_deg`` so
    binary-search / linear interpolation in :func:`fence_height_at_spray_deg`
    can rely on monotonicity without re-sorting on every call.
    """

    schema_version: int
    park_id: str
    name: str
    altitude_m: float
    default_atmosphere: DefaultAtmosphere
    centerline_bearing_deg: float
    fence_polyline: tuple[FencePoint, ...]
    behind_fence_stand_height_ft: float
    foul_territory_sqft: float

    def angles(self) -> list[float]:
        return [p.angle_from_centerline_deg for p in self.fence_polyline]


@lru_cache(maxsize=1)
def _schema() -> dict[str, Any]:
    if not _SCHEMA_PATH.exists():
        raise ParkGeometryError(f"park-geometry schema missing at {_SCHEMA_PATH}")
    return json.loads(_SCHEMA_PATH.read_text())


def _validate(park_id: str, data: dict[str, Any]) -> None:
    try:
        jsonschema.validate(instance=data, schema=_schema())
    except jsonschema.ValidationError as exc:
        raise ParkGeometryError(
            f"park {park_id!r} failed schema validation: {exc.message}"
        ) from exc


def _parse(park_id: str, data: dict[str, Any]) -> ParkGeometry:
    declared = data.get("park_id")
    if declared != park_id:
        raise ParkGeometryError(
            f"park file {park_id}.json declares park_id={declared!r} (must match filename)"
        )
    polyline_raw = sorted(data["fence_polyline"], key=lambda p: p["angle_from_centerline_deg"])
    polyline = tuple(
        FencePoint(
            angle_from_centerline_deg=float(p["angle_from_centerline_deg"]),
            distance_ft=float(p["distance_ft"]),
            height_ft=float(p["height_ft"]),
        )
        for p in polyline_raw
    )
    atmo = data["default_atmosphere"]
    return ParkGeometry(
        schema_version=int(data["schema_version"]),
        park_id=data["park_id"],
        name=data["name"],
        altitude_m=float(data["altitude_m"]),
        default_atmosphere=DefaultAtmosphere(
            temp_c=float(atmo["temp_c"]),
            pressure_hpa=None if atmo["pressure_hpa"] is None else float(atmo["pressure_hpa"]),
            humidity_pct=float(atmo["humidity_pct"]),
            wind_speed_mph=float(atmo.get("wind_speed_mph", 0.0)),
            wind_bearing_deg=float(atmo.get("wind_bearing_deg", 0.0)),
        ),
        centerline_bearing_deg=float(data["centerline_bearing_deg"]),
        fence_polyline=polyline,
        behind_fence_stand_height_ft=float(data["behind_fence_stand_height_ft"]),
        foul_territory_sqft=float(data["foul_territory_sqft"]),
    )


@lru_cache(maxsize=64)
def load_park_geometry(park_id: str) -> ParkGeometry:
    """Load + validate one park's geometry.

    Cached — the JSON parse + schema validation is well under 1 ms but
    classify_outcome is hot enough on the retrodiction path (2c.4 will
    call it ~150 K times) that the cache is worth keeping.
    """
    path = _GEOMETRY_DIR / f"{park_id}.json"
    if not path.exists():
        raise ParkGeometryError(f"park geometry not found at {path}")
    try:
        data = json.loads(path.read_text())
    except json.JSONDecodeError as exc:
        raise ParkGeometryError(f"park {park_id}.json is invalid JSON: {exc}") from exc
    _validate(park_id, data)
    return _parse(park_id, data)


def load_all_parks() -> dict[str, ParkGeometry]:
    """Load every park_geometry/*.json file under the geometry dir.

    Used by :func:`tests.battedball.parks.test_loader` to confirm the
    full set survives schema validation. Returns a dict keyed on
    ``park_id`` for direct lookup.
    """
    parks: dict[str, ParkGeometry] = {}
    for path in sorted(_GEOMETRY_DIR.glob("*.json")):
        if path.name.startswith("_"):
            continue
        park_id = path.stem
        parks[park_id] = load_park_geometry(park_id)
    return parks


def fence_distance_at_spray_deg(park: ParkGeometry, spray_deg: float) -> float:
    """Linearly interpolate the fence distance at a given spray angle (deg).

    spray_deg follows the simulator convention (+ toward 3B). Outside
    the polyline's angular range (i.e. foul territory) we clamp to the
    endpoint distance — callers that care about foul are expected to
    check the angle separately before classifying.
    """
    angles = [p.angle_from_centerline_deg for p in park.fence_polyline]
    if spray_deg <= angles[0]:
        return park.fence_polyline[0].distance_ft
    if spray_deg >= angles[-1]:
        return park.fence_polyline[-1].distance_ft
    # Linear scan — 5-10 polyline points, branch predictor handles it.
    for i in range(1, len(park.fence_polyline)):
        if spray_deg <= angles[i]:
            a0, a1 = angles[i - 1], angles[i]
            d0, d1 = park.fence_polyline[i - 1].distance_ft, park.fence_polyline[i].distance_ft
            frac = (spray_deg - a0) / (a1 - a0)
            return d0 + frac * (d1 - d0)
    return park.fence_polyline[-1].distance_ft  # unreachable, here for type checker


def fence_height_at_spray_deg(park: ParkGeometry, spray_deg: float) -> float:
    """Linearly interpolate the fence height at a given spray angle (deg)."""
    angles = [p.angle_from_centerline_deg for p in park.fence_polyline]
    if spray_deg <= angles[0]:
        return park.fence_polyline[0].height_ft
    if spray_deg >= angles[-1]:
        return park.fence_polyline[-1].height_ft
    for i in range(1, len(park.fence_polyline)):
        if spray_deg <= angles[i]:
            a0, a1 = angles[i - 1], angles[i]
            h0, h1 = park.fence_polyline[i - 1].height_ft, park.fence_polyline[i].height_ft
            frac = (spray_deg - a0) / (a1 - a0)
            return h0 + frac * (h1 - h0)
    return park.fence_polyline[-1].height_ft


__all__ = (
    "DefaultAtmosphere",
    "FencePoint",
    "ParkGeometry",
    "ParkGeometryError",
    "fence_distance_at_spray_deg",
    "fence_height_at_spray_deg",
    "load_all_parks",
    "load_park_geometry",
)
