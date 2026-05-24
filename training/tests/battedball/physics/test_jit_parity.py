"""Parity tests: Numba JIT scalar accel == Python equations.total_acceleration.

The Python ``equations.py`` is the reference implementation (vectorised
NumPy, readable, tested in ``test_equations.py``). The JIT ``_accel_scalar``
inlines the same maths in scalar form for the hot loop. These tests pin
the two against each other across the parameter space the simulator
actually sees, so any silent divergence (e.g. a tweak to the CD lookup
that lands in one path but not the other) trips immediately.
"""

from __future__ import annotations

import numpy as np
import pytest

from bullpen_training.battedball.physics._constants import RPM_TO_RAD_S
from bullpen_training.battedball.physics._jit import _CD_X, _CD_Y, _accel_scalar, _cd_interp
from bullpen_training.battedball.physics.equations import total_acceleration


def _ref_accel(
    vel: np.ndarray,
    spin_axis: np.ndarray,
    spin_rate: float,
    rho: float,
    wind: np.ndarray,
) -> np.ndarray:
    return total_acceleration(vel, spin_axis, spin_rate, rho, wind)


def _jit_accel(
    vel: np.ndarray,
    spin_axis: np.ndarray,
    spin_rate: float,
    rho: float,
    wind: np.ndarray,
) -> np.ndarray:
    ax, ay, az = _accel_scalar(
        float(vel[0]),
        float(vel[1]),
        float(vel[2]),
        float(wind[0]),
        float(wind[1]),
        float(wind[2]),
        float(spin_axis[0]),
        float(spin_axis[1]),
        float(spin_axis[2]),
        float(spin_rate),
        float(rho),
        _CD_X,
        _CD_Y,
    )
    return np.array([ax, ay, az], dtype=np.float64)


@pytest.mark.parametrize(
    ("vel", "spin_axis", "spin_rpm", "rho", "wind"),
    [
        # Typical HR launch
        ([35.0, 0.0, 18.0], [0.0, -1.0, 0.0], 2200.0, 1.225, [0.0, 0.0, 0.0]),
        # Sidespin pull
        ([30.0, 5.0, 15.0], [0.0, 0.0, 1.0], 1800.0, 1.18, [0.0, 0.0, 0.0]),
        # Low liner, no spin
        ([42.0, 2.0, 6.0], [0.0, -1.0, 0.0], 0.0, 1.225, [0.0, 0.0, 0.0]),
        # Wind from RF
        ([25.0, -3.0, 12.0], [0.0, -1.0, 0.0], 2000.0, 1.225, [2.0, 1.0, 0.5]),
        # Slow popup
        ([5.0, 0.0, 22.0], [0.0, -1.0, 0.0], 2500.0, 1.225, [0.0, 0.0, 0.0]),
        # Coors-altitude density
        ([38.0, 1.0, 17.0], [0.0, -1.0, 0.0], 2200.0, 1.02, [0.0, 0.0, 0.0]),
    ],
)
def test_accel_parity_python_vs_jit(
    vel: list[float],
    spin_axis: list[float],
    spin_rpm: float,
    rho: float,
    wind: list[float],
) -> None:
    """Each parameter set should agree to ~1e-9 (the float64 noise floor for
    arithmetic this shallow). Spin axis is already a unit vector by
    construction in the simulator entry points."""
    v = np.array(vel, dtype=np.float64)
    s = np.array(spin_axis, dtype=np.float64)
    w = np.array(wind, dtype=np.float64)
    spin_rate = spin_rpm * RPM_TO_RAD_S

    a_ref = _ref_accel(v, s, spin_rate, rho, w)
    a_jit = _jit_accel(v, s, spin_rate, rho, w)
    np.testing.assert_allclose(a_jit, a_ref, atol=1e-9, rtol=1e-9)


def test_cd_interp_matches_python_lookup() -> None:
    """Hardcoded JIT CD branches must match the np.interp Python path on
    the full speed range the simulator exercises (5--65 m/s)."""
    speeds = np.linspace(5.0, 65.0, 121)
    py = np.interp(speeds, _CD_X, _CD_Y)
    jit = np.array([_cd_interp(float(s)) for s in speeds])
    np.testing.assert_allclose(jit, py, atol=1e-12)


def test_accel_parity_random_grid() -> None:
    """Hammer the parity with 200 random parameter draws to catch any
    branch the hand-curated list above missed."""
    rng = np.random.default_rng(20260524)
    for _ in range(200):
        speed = rng.uniform(5.0, 55.0)
        # Sample a unit velocity then scale (skip the degenerate v=0 case
        # — that branch is covered by the scalar early-return path and
        # the equations.py zero-velocity test).
        direction = rng.normal(size=3)
        direction /= np.linalg.norm(direction)
        v = direction * speed
        # Spin axis sampled on the unit sphere
        s_dir = rng.normal(size=3)
        s_dir /= np.linalg.norm(s_dir)
        spin_rate = rng.uniform(0.0, 3000.0) * RPM_TO_RAD_S
        rho = rng.uniform(0.95, 1.30)
        wind = rng.normal(size=3) * 1.5
        a_ref = _ref_accel(v, s_dir, spin_rate, rho, wind)
        a_jit = _jit_accel(v, s_dir, spin_rate, rho, wind)
        np.testing.assert_allclose(a_jit, a_ref, atol=1e-9, rtol=1e-9)
