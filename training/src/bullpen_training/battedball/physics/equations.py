"""Force equations for the ball-flight simulator (Phase 2c.1).

All forces in Newtons, accelerations in m/s², velocities in m/s,
positions in meters, spin in rad/s. Single-trajectory functions take
(3,) vectors; batch functions take (N, 3). Pure numpy, no allocation
inside hot paths beyond what the integrator demands.

Coordinate convention (stadium frame):
    x — horizontal, toward center field
    y — horizontal, toward third-base (lateral)
    z — vertical, up
The wind vector in :class:`Atmosphere` is in the same frame; the
relative-velocity used for drag/Magnus is ``v_ball - v_wind``.
"""

from __future__ import annotations

import numpy as np

from bullpen_training.battedball.physics._constants import (
    BALL_AREA_M2,
    BALL_MASS_KG,
    BALL_RADIUS_M,
    G_M_S2,
    drag_coefficient,
    lift_coefficient,
)

# Gravity is constant; pre-allocate the vector so the integrator doesn't.
GRAVITY_M_S2: np.ndarray = np.array([0.0, 0.0, -G_M_S2], dtype=np.float64)


def drag_acceleration(velocity_rel: np.ndarray, air_density: float) -> np.ndarray:
    """Drag deceleration vector (m/s²) — opposes the ball's air-relative velocity.

    F_drag = -½ · rho · CD(v) · A · |v| · v_hat
    a_drag = F_drag / m

    Parameters
    ----------
    velocity_rel : (3,) or (N, 3) array. Ball velocity MINUS wind velocity.
    air_density : kg/m³ (scalar — single Atmosphere per call).

    Returns the acceleration in the same shape as ``velocity_rel``.
    """
    speed = _norm(velocity_rel)
    cd = drag_coefficient(speed)
    # |v|² = speed * |v_hat|^2 — but |v_hat|=1, so factor is speed.
    # F = -0.5 · rho · CD · A · speed · v_vec     → drop the explicit v_hat split.
    coef = -0.5 * air_density * cd * BALL_AREA_M2 * speed / BALL_MASS_KG
    return _broadcast_scalar_into_vec(coef, velocity_rel)


def magnus_acceleration(
    velocity_rel: np.ndarray,
    spin_axis_unit: np.ndarray,
    spin_rate_rad_s: float | np.ndarray,
    air_density: float,
) -> np.ndarray:
    """Magnus lift acceleration (m/s²).

    F_lift = ½ · rho · CL(S) · A · |v|² · (ω̂ x v̂)
    where S = ω·r / v is the spin parameter.
    a_lift = F_lift / m

    The cross product points perpendicular to both spin axis and velocity —
    that's the direction the ball gets pushed (e.g. backspin lifts a fly ball
    because ω̂ x v̂ has a positive z component when v is forward and ω is
    "spinning up" toward 3B).

    For ``spin_rate_rad_s`` near 0 the result is exactly zero (matches the
    physical no-spin limit, tested in test_equations.py).
    """
    speed = _norm(velocity_rel)
    # Spin parameter S = ω·r / v. Guard against divide-by-zero on stationary
    # balls (returns 0 acceleration via the lift_coefficient(0) → 0 path).
    safe_speed = np.where(speed > 1e-9, speed, 1.0)
    s_param = np.abs(spin_rate_rad_s) * BALL_RADIUS_M / safe_speed
    cl = lift_coefficient(s_param)
    coef = 0.5 * air_density * cl * BALL_AREA_M2 * speed * speed / BALL_MASS_KG
    # ω̂ x v̂
    v_hat = velocity_rel / _expand_for_div(safe_speed, velocity_rel)
    cross = _cross(spin_axis_unit, v_hat)
    cross_mag = _norm(cross)
    # Normalise the cross product to a unit vector (the spin-axis convention
    # already gave us magnitude in the coefficient). Guard against parallel
    # spin axis + velocity (no Magnus force in that limit).
    safe_cross_mag = np.where(cross_mag > 1e-9, cross_mag, 1.0)
    cross_hat = cross / _expand_for_div(safe_cross_mag, cross)
    # Zero out where speed was zero or spin was zero (cl handles the latter,
    # speed handles the former).
    # coef is a scalar (or (N,) for batches); apply it to cross_hat without
    # going through _broadcast_scalar_into_vec — that helper multiplies the
    # scalar element-wise with the *velocity* vector (right for drag, wrong
    # for Magnus). Here we just need a column broadcast.
    return _expand_for_div(coef, cross_hat) * cross_hat


def total_acceleration(
    velocity: np.ndarray,
    spin_axis_unit: np.ndarray,
    spin_rate_rad_s: float | np.ndarray,
    air_density: float,
    wind_vec: np.ndarray,
) -> np.ndarray:
    """Sum of drag + Magnus + gravity, m/s². The integrator calls this every step.

    Spin axis is treated as constant over the flight (the published
    Nathan model uses spin decay τ ≈ 30 s, much longer than batted-ball
    hang time ≈ 4-7 s — error <2 %).
    """
    velocity_rel = velocity - wind_vec
    a_drag = drag_acceleration(velocity_rel, air_density)
    a_magnus = magnus_acceleration(velocity_rel, spin_axis_unit, spin_rate_rad_s, air_density)
    return a_drag + a_magnus + GRAVITY_M_S2


# --- internal numpy helpers ------------------------------------------------


def _norm(v: np.ndarray) -> np.ndarray:
    """L2 norm along the last axis — works for both (3,) and (N, 3) inputs.

    Inlined sqrt(sum(v*v)) is ~5x faster than np.linalg.norm for the
    (3,) and (N, 3) shapes the simulator uses — np.linalg.norm spends
    most of its time in axis-normalization scaffolding for these sizes.
    """
    return np.sqrt(np.einsum("...i,...i->...", v, v))


def _cross(a: np.ndarray, b: np.ndarray) -> np.ndarray:
    """Cross product along the last axis. Broadcasts (3,) against (N, 3).

    Inlined component-wise to avoid np.cross's per-call overhead — it goes
    through moveaxis and is dominated by axis bookkeeping for (3,) inputs.
    Manual form is ~10x faster for the simulator's tight inner loop.
    """
    a0 = a[..., 0]
    a1 = a[..., 1]
    a2 = a[..., 2]
    b0 = b[..., 0]
    b1 = b[..., 1]
    b2 = b[..., 2]
    return np.stack(
        [a1 * b2 - a2 * b1, a2 * b0 - a0 * b2, a0 * b1 - a1 * b0],
        axis=-1,
    )


def _broadcast_scalar_into_vec(scalar: np.ndarray, vec_template: np.ndarray) -> np.ndarray:
    """Expand a scalar (or (N,) array) to broadcast against a (3,) or (N, 3) vector."""
    arr = np.asarray(scalar, dtype=np.float64)
    if vec_template.ndim == 1:
        return arr * vec_template
    return arr[:, np.newaxis] * vec_template


def _expand_for_div(scalar: np.ndarray, vec_template: np.ndarray) -> np.ndarray:
    """Same as ``_broadcast_scalar_into_vec`` but returns a divisor shape."""
    arr = np.asarray(scalar, dtype=np.float64)
    if vec_template.ndim == 1:
        return arr  # type: ignore[return-value]
    return arr[:, np.newaxis]


__all__ = (
    "GRAVITY_M_S2",
    "drag_acceleration",
    "magnus_acceleration",
    "total_acceleration",
)
