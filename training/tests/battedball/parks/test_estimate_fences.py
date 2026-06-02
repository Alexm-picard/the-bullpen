"""Tests for the empirical fence estimator (2c.7 geometry fix).

The estimator is a script (scripts/estimate_park_fences.py); its pure pieces are
loaded by file path. We pin the wall-recovery math, the documented fallback
interpolation, and — importantly — that the high-resolution output (variable
polyline length + the new ``geometry_source`` field) still validates against the
park-geometry schema, so an eventual ``--apply`` produces loadable geometry.
"""

from __future__ import annotations

import importlib.util
import json
from pathlib import Path

import jsonschema
import numpy as np
import pytest

_TRAINING = Path(__file__).resolve().parents[3]
_SCHEMA = _TRAINING.parent / "infra" / "park_geometry" / "_schema.json"


def _load_estimator():
    spec = importlib.util.spec_from_file_location(
        "estimate_park_fences", _TRAINING / "scripts" / "estimate_park_fences.py"
    )
    assert spec is not None and spec.loader is not None
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


_EST = _load_estimator()


def test_estimate_recovers_wall_from_nonhr_tail() -> None:
    """Non-HR balls in a bin clustered at a wall distance -> estimate ~= wall."""
    rng = np.random.default_rng(0)
    n = 500
    # One bin centered at 0; non-HR distances pile up just short of a 400 ft wall,
    # HRs land beyond it. The non-HR p90 should sit near the wall.
    spray = rng.uniform(-2.0, 2.0, n)
    nonhr_d = rng.normal(385, 12, n)  # wall-bangers + in-front catches
    is_hr = rng.random(n) < 0.15
    hit = np.where(is_hr, rng.normal(420, 15, n), nonhr_d)
    dist, counts = _EST.estimate_fence_distances(
        spray, hit, is_hr, bin_centers=np.array([0.0]), bin_width=10.0, min_samples=20
    )
    assert counts[0] > 0
    assert 395 <= dist[0] <= 410, f"expected ~wall (~400), got {dist[0]:.0f}"


def test_estimate_sparse_bin_is_nan() -> None:
    spray = np.array([0.0, 0.1, 0.2])
    hit = np.array([390.0, 395.0, 392.0])
    is_hr = np.array([False, False, False])
    dist, counts = _EST.estimate_fence_distances(
        spray, hit, is_hr, bin_centers=np.array([0.0]), bin_width=10.0, min_samples=40
    )
    assert counts[0] == 3
    assert np.isnan(dist[0])


def test_doc_interp_endpoints_and_midpoint() -> None:
    pts = [
        {"angle_from_centerline_deg": -45.0, "distance_ft": 314.0, "height_ft": 8.0},
        {"angle_from_centerline_deg": 0.0, "distance_ft": 408.0, "height_ft": 8.0},
        {"angle_from_centerline_deg": 45.0, "distance_ft": 318.0, "height_ft": 8.0},
    ]
    assert _EST._doc_interp(pts, -45.0, "distance_ft") == pytest.approx(314.0)
    assert _EST._doc_interp(pts, -90.0, "distance_ft") == pytest.approx(314.0)  # clamps
    assert _EST._doc_interp(pts, -22.5, "distance_ft") == pytest.approx(361.0)  # midpoint


def test_highres_output_validates_against_schema() -> None:
    """A 19-point polyline + geometry_source must pass the park-geometry schema."""
    schema = json.loads(_SCHEMA.read_text())
    polyline = [
        {"angle_from_centerline_deg": float(a), "distance_ft": 360.0, "height_ft": 8.0}
        for a in range(-45, 50, 5)
    ]
    instance = {
        "schema_version": 1,
        "park_id": "NYY",
        "name": "Yankee Stadium",
        "altitude_m": 16.0,
        "default_atmosphere": {"temp_c": 20.0, "pressure_hpa": None, "humidity_pct": 62},
        "centerline_bearing_deg": 28.0,
        "fence_polyline": polyline,
        "behind_fence_stand_height_ft": 25.0,
        "foul_territory_sqft": 22000,
        "geometry_source": "empirical Statcast wall-ball estimate",
    }
    jsonschema.validate(instance=instance, schema=schema)  # raises on failure
