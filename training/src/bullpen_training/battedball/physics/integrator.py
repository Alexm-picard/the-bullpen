"""RK4 integrator for the ball-flight simulator (Phase 2c.1).

Classical 4th-order Runge-Kutta on a 6-dim state vector
(position 3 + velocity 3). The integrator is agnostic to forces — the
acceleration function is passed in. Adaptive timestep: small near apex
and landing (where the trajectory curves most), larger during cruise.

Vectorisation: the same step function works for a single (6,) state or
a batch (N, 6) state, because the underlying acceleration is vectorised.
A bit-identical batched run is the building block for
``simulate_batch`` in simulator.py.

For the batted-ball use case the trajectory lasts ~4-7 s; with dt = 0.005 s
the loop runs 800-1400 times. RK4 at this step is well below 1 % integration
error vs the analytical vacuum-projectile reference (verified in
test_simulator.py::test_vacuum_match_within_1e3).
"""

from __future__ import annotations

from collections.abc import Callable

import numpy as np

# Acceleration callback signature: (velocity_3 or Nx3) -> same shape.
# Position isn't passed since none of the forces in this model depend
# on position. Wind / atmosphere / spin are closed over by the caller.
AccelFn = Callable[[np.ndarray], np.ndarray]


def rk4_step(state: np.ndarray, dt: float, accel_fn: AccelFn) -> np.ndarray:
    """One RK4 step.

    state layout: last axis = [x, y, z, vx, vy, vz]. Works for (6,) single
    or (N, 6) batch. Returns a fresh array of the same shape; does not
    mutate the input.
    """
    pos, vel = _split(state)

    # k1 — derivative at the current state
    a1 = accel_fn(vel)
    k1_pos = vel
    k1_vel = a1

    # k2 — derivative at the mid-step using k1
    a2 = accel_fn(vel + 0.5 * dt * k1_vel)
    k2_pos = vel + 0.5 * dt * k1_vel
    k2_vel = a2

    # k3 — derivative at the mid-step using k2
    a3 = accel_fn(vel + 0.5 * dt * k2_vel)
    k3_pos = vel + 0.5 * dt * k2_vel
    k3_vel = a3

    # k4 — derivative at the full step
    a4 = accel_fn(vel + dt * k3_vel)
    k4_pos = vel + dt * k3_vel
    k4_vel = a4

    pos_next = pos + (dt / 6.0) * (k1_pos + 2.0 * k2_pos + 2.0 * k3_pos + k4_pos)
    vel_next = vel + (dt / 6.0) * (k1_vel + 2.0 * k2_vel + 2.0 * k3_vel + k4_vel)
    return _join(pos_next, vel_next)


def adaptive_dt(state: np.ndarray, base_dt: float = 0.005) -> float:
    """Cheap heuristic adaptive timestep.

    Returns a smaller dt when the ball is moving slowly (near apex) or
    near the ground (the landing detector benefits from fine resolution).
    Capped to ``base_dt`` in cruise. Single-state form only — the batch
    integrator uses a fixed ``base_dt`` since per-trajectory dt would
    desynchronise the time array.

    The factor is conservative: integration error scales as dt^5 for
    RK4, so cutting dt in half drops error 32x. For a 7-second flight
    at base_dt=0.005 the global error is <0.5 ft on the analytical
    reference (test_simulator.py).
    """
    if state.ndim != 1:
        return base_dt
    _pos, vel = _split(state)
    speed = float(np.linalg.norm(vel))
    z = float(state[2])
    if speed < 8.0:  # apex hover
        return 0.5 * base_dt
    if z < 1.0:  # close to the ground
        return 0.5 * base_dt
    return base_dt


def _split(state: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    return state[..., :3], state[..., 3:]


def _join(pos: np.ndarray, vel: np.ndarray) -> np.ndarray:
    return np.concatenate([pos, vel], axis=-1)


__all__ = ("AccelFn", "adaptive_dt", "rk4_step")
