"""Unit tests for the physics force equations (Phase 2c.1)."""

from __future__ import annotations

import numpy as np
import pytest

from bullpen_training.battedball.physics._constants import (
    SEA_LEVEL_DENSITY_KG_M3,
    drag_coefficient,
    lift_coefficient,
)
from bullpen_training.battedball.physics.atmosphere import air_density
from bullpen_training.battedball.physics.equations import (
    GRAVITY_M_S2,
    drag_acceleration,
    magnus_acceleration,
    total_acceleration,
)

# --- drag_coefficient ------------------------------------------------------


def test_drag_coefficient_low_speed_plateau() -> None:
    # CD ~= 0.50 for speeds in the pre-crisis regime (< 25 m/s)
    assert float(drag_coefficient(5.0)) == pytest.approx(0.50, abs=0.01)
    assert float(drag_coefficient(20.0)) == pytest.approx(0.50, abs=0.01)


def test_drag_coefficient_high_speed_plateau() -> None:
    # CD ~= 0.30 once well into the post-crisis regime
    assert float(drag_coefficient(60.0)) == pytest.approx(0.30, abs=0.02)


def test_drag_coefficient_vectorised() -> None:
    speeds = np.array([10.0, 30.0, 50.0])
    cds = np.asarray(drag_coefficient(speeds))
    assert cds.shape == (3,)
    assert bool((cds > 0).all()) and bool((cds < 1).all())


# --- lift_coefficient ------------------------------------------------------


def test_lift_coefficient_zero_spin_is_zero() -> None:
    # No spin -> no Magnus lift
    assert float(lift_coefficient(0.0)) == pytest.approx(0.0, abs=1e-9)


def test_lift_coefficient_monotone_in_spin_parameter() -> None:
    # CL should grow monotonically with spin parameter
    cls_arr = lift_coefficient(np.array([0.05, 0.1, 0.2, 0.4]))
    assert np.all(np.diff(cls_arr) > 0)


def test_lift_coefficient_saturates() -> None:
    # CL saturates near ~0.65 for very high spin parameter
    cl_high = float(lift_coefficient(10.0))
    assert 0.55 < cl_high < 0.75


# --- air_density -----------------------------------------------------------


def test_air_density_standard_atmosphere() -> None:
    # ISA at sea level: 15 C, 1013.25 hPa, 0% humidity -> 1.225 kg/m^3 within 0.5%.
    rho = float(air_density(temp_c=15.0, pressure_hpa=1013.25, altitude_m=0.0, humidity_pct=0.0))
    assert rho == pytest.approx(SEA_LEVEL_DENSITY_KG_M3, rel=0.005)


def test_air_density_drops_at_altitude() -> None:
    # Coors Field ~ 1580 m; expect density ~17% lower than sea level standard
    sea_level = float(air_density(temp_c=20.0, pressure_hpa=None, altitude_m=0.0, humidity_pct=0.0))
    coors = float(air_density(temp_c=20.0, pressure_hpa=None, altitude_m=1580.0, humidity_pct=0.0))
    assert coors < sea_level
    assert (sea_level - coors) / sea_level == pytest.approx(0.17, abs=0.02)


def test_air_density_drops_with_humidity() -> None:
    # Humid air is LIGHTER than dry air (counterintuitive but true)
    dry = float(air_density(temp_c=30.0, pressure_hpa=1013.25, altitude_m=0.0, humidity_pct=0.0))
    humid = float(air_density(temp_c=30.0, pressure_hpa=1013.25, altitude_m=0.0, humidity_pct=90.0))
    assert humid < dry


# --- drag_acceleration -----------------------------------------------------


def test_drag_acceleration_zero_velocity_is_zero() -> None:
    a = drag_acceleration(np.zeros(3), air_density=1.225)
    np.testing.assert_allclose(a, np.zeros(3), atol=1e-12)


def test_drag_acceleration_opposes_velocity() -> None:
    v = np.array([40.0, 0.0, 0.0])
    a = drag_acceleration(v, air_density=1.225)
    # All deceleration should be in -x (opposing velocity)
    assert a[0] < 0
    assert abs(a[1]) < 1e-9
    assert abs(a[2]) < 1e-9


def test_drag_acceleration_batched() -> None:
    vs = np.array([[40.0, 0.0, 0.0], [0.0, 30.0, 0.0], [20.0, 20.0, 0.0]])
    a = drag_acceleration(vs, air_density=1.225)
    assert a.shape == (3, 3)
    # Each row's acceleration anti-parallel to its velocity
    for i in range(3):
        if np.linalg.norm(vs[i]) > 0:
            assert np.dot(a[i], vs[i]) < 0


# --- magnus_acceleration ---------------------------------------------------


def test_magnus_acceleration_zero_spin_is_zero() -> None:
    v = np.array([40.0, 0.0, 5.0])
    spin_axis = np.array([0.0, -1.0, 0.0])  # backspin axis
    a = magnus_acceleration(v, spin_axis, spin_rate_rad_s=0.0, air_density=1.225)
    np.testing.assert_allclose(a, np.zeros(3), atol=1e-9)


def test_magnus_acceleration_backspin_lifts_forward_velocity() -> None:
    # Backspin (-y axis) on a forward-moving ball should produce +z Magnus lift.
    # omega_hat x v_hat = (-y) x (+x) = +z. Good.
    v = np.array([40.0, 0.0, 5.0])
    spin_axis = np.array([0.0, -1.0, 0.0])
    a = magnus_acceleration(v, spin_axis, spin_rate_rad_s=200.0, air_density=1.225)
    assert a[2] > 0  # upward Magnus force
    # Should also have a backward component (away from velocity direction in xz plane)
    assert abs(a[2]) > abs(a[0])  # mostly vertical


def test_magnus_acceleration_zero_velocity_is_zero() -> None:
    spin_axis = np.array([0.0, -1.0, 0.0])
    a = magnus_acceleration(np.zeros(3), spin_axis, spin_rate_rad_s=200.0, air_density=1.225)
    np.testing.assert_allclose(a, np.zeros(3), atol=1e-9)


# --- total_acceleration ---------------------------------------------------


def test_total_acceleration_includes_gravity() -> None:
    # With zero velocity + zero spin, only gravity remains
    a = total_acceleration(
        velocity=np.zeros(3),
        spin_axis_unit=np.array([0.0, -1.0, 0.0]),
        spin_rate_rad_s=0.0,
        air_density=1.225,
        wind_vec=np.zeros(3),
    )
    np.testing.assert_allclose(a, GRAVITY_M_S2, atol=1e-12)


def test_total_acceleration_wind_relative_velocity() -> None:
    # If ball and wind move identically, the air-relative velocity is zero
    # and only gravity remains.
    v = np.array([20.0, 0.0, 0.0])
    a = total_acceleration(
        velocity=v,
        spin_axis_unit=np.array([0.0, -1.0, 0.0]),
        spin_rate_rad_s=0.0,
        air_density=1.225,
        wind_vec=v,
    )
    np.testing.assert_allclose(a, GRAVITY_M_S2, atol=1e-9)
