"""Numba JIT-compiled hot loop for the ball-flight simulator (Phase 2c.1).

The Python ``equations.py`` + ``integrator.py`` form the reference
implementation — readable, fully tested, but pure-NumPy dispatch
overhead caps single-trajectory throughput around 50 ms/traj and batch
around 3K traj/s. The leaf target is <1 ms / >=10K traj/s, which on
CPython means moving the inner loop outside Python.

This module exposes two callables consumed by ``simulator.py``:

- ``integrate_single(...)`` — one full trajectory in compiled code.
- ``integrate_batch(...)`` — N trajectories in parallel via prange.

Both inline the drag + Magnus + gravity force model with scalar math
(no allocation inside the step loop), match the Python reference within
floating-point noise (verified in ``test_jit_parity.py``), and cache the
compiled binary across runs (``@njit(cache=True)``).
"""

from __future__ import annotations

import numpy as np
from numba import njit, prange

from bullpen_training.battedball.physics._constants import (
    _DRAG_CD_TABLE_CD,
    _DRAG_CD_TABLE_V_MS,
    _LIFT_A,
    _LIFT_B,
    _LIFT_NUM,
    BALL_AREA_M2,
    BALL_MASS_KG,
    BALL_RADIUS_M,
    G_M_S2,
)

# Pull table data into module-level arrays the JIT closures can specialise on.
_CD_X = _DRAG_CD_TABLE_V_MS.copy()
_CD_Y = _DRAG_CD_TABLE_CD.copy()


@njit(cache=True, fastmath=True, inline="always")
def _cd_interp(speed: float) -> float:
    """Hardcoded piecewise-linear CD lookup over the Nathan 2008 5-point table.

    Faster than np.interp inside the hot loop — Numba's np.interp goes
    through a generic implementation that's order-of-magnitude slower than
    direct branches for a small fixed table."""
    if speed <= 25.0:
        return 0.50
    if speed <= 35.0:
        # 25..35: CD 0.50 -> 0.40
        return 0.50 + (speed - 25.0) * (-0.10 / 10.0)
    if speed <= 45.0:
        # 35..45: CD 0.40 -> 0.32
        return 0.40 + (speed - 35.0) * (-0.08 / 10.0)
    if speed <= 55.0:
        # 45..55: CD 0.32 -> 0.30
        return 0.32 + (speed - 45.0) * (-0.02 / 10.0)
    return 0.30


@njit(cache=True, fastmath=True, inline="always")
def _accel_scalar(
    vx: float,
    vy: float,
    vz: float,
    wind_x: float,
    wind_y: float,
    wind_z: float,
    spin_x: float,
    spin_y: float,
    spin_z: float,
    spin_rate: float,
    rho: float,
    cd_x: np.ndarray,
    cd_y: np.ndarray,
    cd_scale: float = 1.0,
) -> tuple[float, float, float]:
    """Return (ax, ay, az) for one velocity sample.

    Pure scalar math — no array allocation. Mirrors total_acceleration()
    in equations.py with the helper functions unrolled.

    cd_x/cd_y are accepted for API symmetry with the Python equations but
    not used — the inner loop calls ``_cd_interp`` instead (hardcoded
    branches over the Nathan 2008 5-point table — faster than np.interp
    inside the JIT).

    ``cd_scale`` is the calibrated global drag multiplier (Phase 1 physics
    overhaul); 1.0 = the raw Nathan CD curve (default keeps parity)."""
    # Wind-relative velocity
    rx = vx - wind_x
    ry = vy - wind_y
    rz = vz - wind_z
    speed_sq = rx * rx + ry * ry + rz * rz
    if speed_sq < 1e-18:
        # Zero relative velocity -> only gravity contributes.
        return 0.0, 0.0, -G_M_S2

    speed = np.sqrt(speed_sq)

    # Drag: -0.5 * rho * CD * A * speed * v_rel / m (CD scaled by the calibrated cd_scale)
    cd = _cd_interp(speed) * cd_scale
    drag_coef = -0.5 * rho * cd * BALL_AREA_M2 * speed / BALL_MASS_KG
    a_drag_x = drag_coef * rx
    a_drag_y = drag_coef * ry
    a_drag_z = drag_coef * rz

    # Magnus: 0.5 * rho * CL * A * speed^2 * (omega_hat x v_hat) / m
    s_param = abs(spin_rate) * BALL_RADIUS_M / speed
    cl = _LIFT_NUM * s_param / (_LIFT_A + _LIFT_B * s_param + 1e-12)
    inv_speed = 1.0 / speed
    vhx = rx * inv_speed
    vhy = ry * inv_speed
    vhz = rz * inv_speed
    cx = spin_y * vhz - spin_z * vhy
    cy = spin_z * vhx - spin_x * vhz
    cz = spin_x * vhy - spin_y * vhx
    cross_mag = np.sqrt(cx * cx + cy * cy + cz * cz)
    if cross_mag > 1e-9:
        m_coef = 0.5 * rho * cl * BALL_AREA_M2 * speed_sq / BALL_MASS_KG
        inv_cross = 1.0 / cross_mag
        a_mag_x = m_coef * cx * inv_cross
        a_mag_y = m_coef * cy * inv_cross
        a_mag_z = m_coef * cz * inv_cross
    else:
        a_mag_x = 0.0
        a_mag_y = 0.0
        a_mag_z = 0.0

    return (a_drag_x + a_mag_x, a_drag_y + a_mag_y, a_drag_z + a_mag_z - G_M_S2)


@njit(cache=True, fastmath=True, boundscheck=False, error_model="numpy")
def _integrate_into(
    states: np.ndarray,
    state0: np.ndarray,
    dt: float,
    n_steps_max: int,
    spin_axis: np.ndarray,
    spin_rate: float,
    rho: float,
    wind: np.ndarray,
    cd_x: np.ndarray,
    cd_y: np.ndarray,
    cd_scale: float = 1.0,
) -> int:
    """Run one trajectory writing into a pre-allocated ``states`` buffer.

    Returns the landing step (-1 if no landing within n_steps_max). Frames
    beyond the landing step are left untouched in ``states`` — callers
    slice using the returned index.

    This is the integration core; ``integrate_single`` allocates a fresh
    buffer and calls it, while ``integrate_batch`` calls it per thread on
    a slice of a shared (N, n_steps_max+1, 6) buffer so the inner loop
    never allocates.
    """
    states[0] = state0
    px = state0[0]
    py = state0[1]
    pz = state0[2]
    vx = state0[3]
    vy = state0[4]
    vz = state0[5]
    sx = spin_axis[0]
    sy = spin_axis[1]
    sz = spin_axis[2]
    wx = wind[0]
    wy = wind[1]
    wz = wind[2]

    landing_step = -1
    for step in range(1, n_steps_max + 1):
        prev_pz = pz

        # k1
        a1x, a1y, a1z = _accel_scalar(
            vx, vy, vz, wx, wy, wz, sx, sy, sz, spin_rate, rho, cd_x, cd_y, cd_scale
        )
        # k2 (midpoint with k1)
        vx2 = vx + 0.5 * dt * a1x
        vy2 = vy + 0.5 * dt * a1y
        vz2 = vz + 0.5 * dt * a1z
        a2x, a2y, a2z = _accel_scalar(
            vx2, vy2, vz2, wx, wy, wz, sx, sy, sz, spin_rate, rho, cd_x, cd_y, cd_scale
        )
        # k3 (midpoint with k2)
        vx3 = vx + 0.5 * dt * a2x
        vy3 = vy + 0.5 * dt * a2y
        vz3 = vz + 0.5 * dt * a2z
        a3x, a3y, a3z = _accel_scalar(
            vx3, vy3, vz3, wx, wy, wz, sx, sy, sz, spin_rate, rho, cd_x, cd_y, cd_scale
        )
        # k4 (full step with k3)
        vx4 = vx + dt * a3x
        vy4 = vy + dt * a3y
        vz4 = vz + dt * a3z
        a4x, a4y, a4z = _accel_scalar(
            vx4, vy4, vz4, wx, wy, wz, sx, sy, sz, spin_rate, rho, cd_x, cd_y, cd_scale
        )

        sixth = dt / 6.0
        # Position update: integrate velocity midpoints (same shape as RK4 in integrator.py)
        # k_pos_i = vel + half_dt * k_vel_{i-1}: vx, vx2, vx3, vx4 already encode that.
        px_n = px + sixth * (vx + 2.0 * vx2 + 2.0 * vx3 + vx4)
        py_n = py + sixth * (vy + 2.0 * vy2 + 2.0 * vy3 + vy4)
        pz_n = pz + sixth * (vz + 2.0 * vz2 + 2.0 * vz3 + vz4)
        vx_n = vx + sixth * (a1x + 2.0 * a2x + 2.0 * a3x + a4x)
        vy_n = vy + sixth * (a1y + 2.0 * a2y + 2.0 * a3y + a4y)
        vz_n = vz + sixth * (a1z + 2.0 * a2z + 2.0 * a3z + a4z)

        states[step, 0] = px_n
        states[step, 1] = py_n
        states[step, 2] = pz_n
        states[step, 3] = vx_n
        states[step, 4] = vy_n
        states[step, 5] = vz_n

        if pz_n <= 0.0 and prev_pz > 0.0:
            landing_step = step
            return landing_step

        px = px_n
        py = py_n
        pz = pz_n
        vx = vx_n
        vy = vy_n
        vz = vz_n

    return landing_step


@njit(cache=True, fastmath=True, boundscheck=False, error_model="numpy")
def integrate_single(
    state0: np.ndarray,
    dt: float,
    n_steps_max: int,
    spin_axis: np.ndarray,
    spin_rate: float,
    rho: float,
    wind: np.ndarray,
    cd_x: np.ndarray,
    cd_y: np.ndarray,
    cd_scale: float = 1.0,
) -> tuple[np.ndarray, int]:
    """Run a single trajectory through RK4 until landing or n_steps_max.

    Returns (states[final_len, 6], landing_step) — landing_step is -1
    if the ball never crossed z=0. ``final_len`` is landing_step+1 if
    landed, else n_steps_max+1.
    """
    states = np.empty((n_steps_max + 1, 6), dtype=np.float64)
    ls = _integrate_into(
        states, state0, dt, n_steps_max, spin_axis, spin_rate, rho, wind, cd_x, cd_y, cd_scale
    )
    final_len = ls + 1 if ls >= 0 else n_steps_max + 1
    return states[:final_len], ls


@njit(cache=True, fastmath=True, parallel=True, boundscheck=False, error_model="numpy")
def integrate_batch(
    states0: np.ndarray,
    dt: float,
    n_steps_max: int,
    spin_axes: np.ndarray,
    spin_rates: np.ndarray,
    rhos: np.ndarray,
    winds: np.ndarray,
    cd_x: np.ndarray,
    cd_y: np.ndarray,
    cd_scale: float = 1.0,
) -> tuple[np.ndarray, np.ndarray]:
    """Run N trajectories in parallel.

    Each trajectory is independent — RK4 is embarrassingly parallel across
    samples — so we hand the per-i loop to ``prange`` and let Numba's
    threading layer spread across cores.

    Returns:
        states_history: (N, n_steps_max+1, 6) — every trajectory writes its
            actual frames; frames beyond ``landing_steps[i]`` are
            uninitialised and MUST be sliced off by the caller via
            ``landing_steps[i] + 1``.
        landing_steps: (N,) — step index where z crossed 0, or -1.

    Skipping the post-landing pad halves the memory traffic in the hot loop
    for typical batches (where most trajectories land in ~700-1500 steps but
    the buffer covers up to n_steps_max+1).
    """
    n = states0.shape[0]
    states_history = np.empty((n, n_steps_max + 1, 6), dtype=np.float64)
    landing_steps = np.full(n, -1, dtype=np.int64)
    for i in prange(n):
        ls = _integrate_into(
            states_history[i],
            states0[i],
            dt,
            n_steps_max,
            spin_axes[i],
            spin_rates[i],
            rhos[i],
            winds[i],
            cd_x,
            cd_y,
            cd_scale,
        )
        landing_steps[i] = ls
    return states_history, landing_steps


__all__ = ("_CD_X", "_CD_Y", "integrate_batch", "integrate_single")
