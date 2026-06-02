"""Tests for the park-geometry loader (Phase 2c.3).

Every park_geometry/*.json must load + schema-validate. Spot-checks
pin a few well-known dimensions so future edits to the data table
don't silently break the canonical numbers downstream code relies on
(Coors at altitude, the Monster, the short porch).
"""

from __future__ import annotations

from pathlib import Path

import pytest

from bullpen_training.battedball.parks.loader import (
    _DEFAULT_GEOMETRY_DIR,
    ParkGeometryError,
    _resolve_geometry_dir,
    fence_distance_at_spray_deg,
    fence_height_at_spray_deg,
    load_all_parks,
    load_park_geometry,
)


def test_geometry_dir_defaults_without_env(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("BULLPEN_PARK_GEOMETRY_DIR", raising=False)
    assert _resolve_geometry_dir() == _DEFAULT_GEOMETRY_DIR


def test_geometry_dir_honors_env_override(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    monkeypatch.setenv("BULLPEN_PARK_GEOMETRY_DIR", str(tmp_path))
    assert _resolve_geometry_dir() == tmp_path.resolve()


# All 30 MLB parks (matching the abbreviations used in pitches.park_id —
# Arizona is AZ in the Statcast feed, not ARI; Athletics is ATH). If
# any of these is missing the loader fails the whole suite.
_EXPECTED_PARK_IDS = {
    "ATH",
    "ATL",
    "AZ",
    "BAL",
    "BOS",
    "CHC",
    "CIN",
    "CLE",
    "COL",
    "CWS",
    "DET",
    "HOU",
    "KC",
    "LAA",
    "LAD",
    "MIA",
    "MIL",
    "MIN",
    "NYM",
    "NYY",
    "PHI",
    "PIT",
    "SD",
    "SEA",
    "SF",
    "STL",
    "TB",
    "TEX",
    "TOR",
    "WSH",
}


def test_all_30_parks_load_and_validate() -> None:
    parks = load_all_parks()
    # 30 active parks + maybe an alias (AZ/ARI are both Diamondbacks); the
    # set we ship in the data table is 30 unique park_ids.
    assert len(parks) == 30, f"expected 30 parks, got {sorted(parks)}"
    # Every park id is in the expected set (no rogue files snuck in).
    extras = set(parks) - _EXPECTED_PARK_IDS
    assert not extras, f"unexpected park ids: {extras}"


def test_unknown_park_raises() -> None:
    with pytest.raises(ParkGeometryError, match="not found"):
        load_park_geometry("XYZ")


def test_polyline_is_sorted_after_load() -> None:
    parks = load_all_parks()
    for pid, p in parks.items():
        angles = p.angles()
        assert angles == sorted(angles), f"{pid} polyline not sorted: {angles}"


def test_polyline_spans_foul_lines() -> None:
    """First / last polyline points should be at or beyond the foul lines."""
    parks = load_all_parks()
    for pid, p in parks.items():
        angles = p.angles()
        assert angles[0] <= -45.0 + 1.0, f"{pid} RF foul edge too narrow: {angles[0]}"
        assert angles[-1] >= 45.0 - 1.0, f"{pid} LF foul edge too narrow: {angles[-1]}"


def test_fenway_monster_present() -> None:
    """Spot check: Fenway's LF (+45 deg) wall is 37 ft (Green Monster)."""
    bos = load_park_geometry("BOS")
    assert fence_height_at_spray_deg(bos, +45.0) == pytest.approx(37.0)
    assert fence_distance_at_spray_deg(bos, +45.0) == pytest.approx(310.0)


def test_coors_altitude() -> None:
    """Spot check: Coors Field is at 1580 m (the altitude park)."""
    col = load_park_geometry("COL")
    assert col.altitude_m == pytest.approx(1580.0)


def test_yankee_short_porch() -> None:
    """Spot check: Yankee Stadium RF (-45) is the famous 314 ft short porch."""
    nyy = load_park_geometry("NYY")
    assert fence_distance_at_spray_deg(nyy, -45.0) == pytest.approx(314.0)


def test_pirates_clemente_wall() -> None:
    """Spot check: PNC Park's RF wall (Clemente Wall) is 21 ft."""
    pit = load_park_geometry("PIT")
    assert fence_height_at_spray_deg(pit, -45.0) == pytest.approx(21.0)


def test_fence_interpolation_between_points() -> None:
    """Interp at a midpoint should land between adjacent polyline values."""
    nyy = load_park_geometry("NYY")
    # NYY polyline at +45 (LF) is 318 ft, +22.5 is 399 ft
    d_at_33_deg = fence_distance_at_spray_deg(nyy, 33.75)  # midpoint
    assert 318.0 < d_at_33_deg < 399.0


def test_fence_interpolation_clamps_outside_polyline() -> None:
    """Querying outside the polyline (foul territory) clamps to endpoint."""
    nyy = load_park_geometry("NYY")
    assert fence_distance_at_spray_deg(nyy, 60.0) == fence_distance_at_spray_deg(nyy, 45.0)
    assert fence_distance_at_spray_deg(nyy, -60.0) == fence_distance_at_spray_deg(nyy, -45.0)
