"""Public simulator API for ball-flight physics (Phase 2c.1).

Two callables make up the entire public surface:

- ``simulate(launch, atmosphere) -> Trajectory`` — single trajectory.
- ``simulate_batch(launches, atmospheres) -> list[Trajectory]`` — N at a time.

Both accept user-facing units (mph, deg, rpm) and convert internally to
SI (m/s, rad, rad/s). The returned :class:`Trajectory` carries both — SI
in the time-series arrays + summary stats in baseball units (ft, sec).

Landing is detected by sign change on the z coordinate; the final point
is back-interpolated to the exact z = 0 plane so ``distance_m`` and
``hang_time`` aren't quantised to the integration step.

The hot integration loop runs in :mod:`._jit` (Numba-compiled scalar
math, no Python overhead per step). The Python ``equations.py`` is the
reference implementation kept for unit tests and external callers; the
two paths agree within floating-point noise (parity test lives in
``test_jit_parity.py``).
"""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np

from bullpen_training.battedball.physics._constants import (
    DEG_TO_RAD,
    M_TO_FT,
    MPH_TO_M_S,
    RPM_TO_RAD_S,
)
from bullpen_training.battedball.physics._jit import (
    _CD_X,
    _CD_Y,
    integrate_batch,
    integrate_single,
)
from bullpen_training.battedball.physics.atmosphere import Atmosphere


@dataclass(frozen=True)
class LaunchParams:
    """Inputs the simulator takes off the bat.

    Units are deliberately Statcast-native (mph / deg / rpm) so callers
    can pass values straight through from the ``pitches`` table.
    Conversion to SI happens once at simulate() entry.

    spin_axis_tilt_deg: 0 = pure topspin around the y-axis, 90 = pure
    sidespin around the z-axis. Backspin (the usual case off a bat) =
    180 °, modelled as -y axis with positive spin_rate.
    """

    launch_speed_mph: float
    launch_angle_deg: float
    spray_angle_deg: float = 0.0  # 0 = straight to CF, + toward 3B
    spin_rate_rpm: float = 2000.0
    spin_axis_tilt_deg: float = 180.0  # backspin default
    initial_height_m: float = 1.0  # contact height above the plate


@dataclass(frozen=True)
class Trajectory:
    """Full integration output.

    Time arrays use the integrator's step granularity (~0.005 s); summary
    scalars (distance / hang time / max height) are exact via landing
    interpolation.
    """

    t: np.ndarray  # (N,) seconds
    pos: np.ndarray  # (N, 3) meters
    vel: np.ndarray  # (N, 3) m/s
    landed: bool
    hang_time: float  # seconds
    distance_m: float  # ground distance from launch (m)
    max_height_m: float  # peak z (m)

    @property
    def distance_ft(self) -> float:
        return self.distance_m * M_TO_FT

    @property
    def max_height_ft(self) -> float:
        return self.max_height_m * M_TO_FT


# --- defaults --------------------------------------------------------------

_BASE_DT_S: float = 0.005
_DEFAULT_N_STEPS_MAX: int = 2000
_STANDARD_ATMOSPHERE = Atmosphere()  # 20 °C / sea level / 50 % RH


# --- entry points ----------------------------------------------------------


def simulate(
    launch: LaunchParams,
    atmosphere: Atmosphere | None = None,
    *,
    n_steps_max: int = _DEFAULT_N_STEPS_MAX,
    dt: float = _BASE_DT_S,
) -> Trajectory:
    """Integrate one batted-ball trajectory.

    Returns when the ball lands (z crosses 0) or after ``n_steps_max``
    steps, whichever comes first. The ``landed`` flag distinguishes the
    two cases; non-landed trajectories don't have meaningful
    distance/hang_time but the time series is still valid.
    """
    atmo = atmosphere or _STANDARD_ATMOSPHERE
    state0 = _initial_state(launch)
    spin_axis = _spin_axis_unit_from_tilt(launch.spin_axis_tilt_deg)
    spin_rate = float(launch.spin_rate_rpm * RPM_TO_RAD_S)
    wind = atmo.wind_vec_m_s.astype(np.float64)

    states, landing_step = integrate_single(
        state0, dt, n_steps_max, spin_axis, spin_rate, float(atmo.density), wind, _CD_X, _CD_Y
    )
    t = np.arange(states.shape[0], dtype=np.float64) * dt
    return _trajectory_from_states(states, t, int(landing_step))


def simulate_batch(
    launches: list[LaunchParams],
    atmospheres: list[Atmosphere] | None = None,
    *,
    n_steps_max: int = _DEFAULT_N_STEPS_MAX,
    dt: float = _BASE_DT_S,
) -> list[Trajectory]:
    """Vectorised N-trajectory integration.

    Each trajectory is integrated independently in compiled code with
    Numba's ``prange`` distributing them across CPU cores. Per-trajectory
    landing is detected inside the JIT loop and reported back as a step
    index; back-interpolation to z=0 happens here in Python.

    Returns a list of :class:`Trajectory` in the same order as ``launches``.
    """
    n = len(launches)
    if atmospheres is None:
        atmospheres = [_STANDARD_ATMOSPHERE] * n
    if len(atmospheres) != n:
        raise ValueError(f"launches/atmospheres length mismatch: {n} vs {len(atmospheres)}")

    states0 = np.stack([_initial_state(lp) for lp in launches], axis=0)  # (N, 6)
    spin_axes = np.stack(
        [_spin_axis_unit_from_tilt(lp.spin_axis_tilt_deg) for lp in launches], axis=0
    )  # (N, 3)
    spin_rates = np.array(
        [lp.spin_rate_rpm * RPM_TO_RAD_S for lp in launches], dtype=np.float64
    )  # (N,)
    rhos = np.array([a.density for a in atmospheres], dtype=np.float64)  # (N,)
    winds = np.stack([a.wind_vec_m_s for a in atmospheres], axis=0).astype(np.float64)  # (N, 3)

    states_history, landing_steps = integrate_batch(
        states0, dt, n_steps_max, spin_axes, spin_rates, rhos, winds, _CD_X, _CD_Y
    )
    # states_history is (N, n_steps_max+1, 6); for the Python-facing Trajectory we
    # truncate each per-trajectory series at its landing step (so distance_ft etc.
    # don't see the padding frames).
    t_full = np.arange(states_history.shape[1], dtype=np.float64) * dt
    out: list[Trajectory] = []
    for i in range(n):
        ls = int(landing_steps[i])
        if ls > 0:
            states_i = states_history[i, : ls + 1]
            t_i = t_full[: ls + 1]
        else:
            states_i = states_history[i]
            t_i = t_full
        out.append(_trajectory_from_states(states_i, t_i, ls))
    return out


# --- helpers --------------------------------------------------------------


def _initial_state(launch: LaunchParams) -> np.ndarray:
    """Build the initial (6,) state from launch params."""
    v = launch.launch_speed_mph * MPH_TO_M_S
    la = launch.launch_angle_deg * DEG_TO_RAD
    sa = launch.spray_angle_deg * DEG_TO_RAD
    vx = v * np.cos(la) * np.cos(sa)
    vy = v * np.cos(la) * np.sin(sa)
    vz = v * np.sin(la)
    return np.array([0.0, 0.0, launch.initial_height_m, vx, vy, vz], dtype=np.float64)


def _spin_axis_unit_from_tilt(tilt_deg: float) -> np.ndarray:
    """Spin axis unit vector from the tilt angle convention.

    tilt = 0    -> topspin (rotation axis +y, ball "rolls over" forward)
    tilt = 90   -> sidespin to 3B (rotation axis +z)
    tilt = 180  -> backspin (rotation axis -y, ball "rolls back")
    tilt = 270  -> sidespin to 1B (rotation axis -z)
    """
    a = tilt_deg * DEG_TO_RAD
    return np.array([0.0, np.cos(a), np.sin(a)], dtype=np.float64)


def _trajectory_from_states(states: np.ndarray, times: np.ndarray, landing_step: int) -> Trajectory:
    """Assemble a Trajectory from raw integrator output + a landing index.

    landing_step is the index into ``states`` where z first crossed 0
    (i.e. states[landing_step, 2] <= 0 and states[landing_step - 1, 2] > 0),
    or -1 if the ball never landed within the integration window.
    """
    pos = states[:, :3]
    vel = states[:, 3:]
    if landing_step > 0 and landing_step < len(times):
        z0 = pos[landing_step - 1, 2]
        z1 = pos[landing_step, 2]
        frac = z0 / (z0 - z1) if z0 != z1 else 0.0
        landing_pos = pos[landing_step - 1] + frac * (pos[landing_step] - pos[landing_step - 1])
        landing_t = times[landing_step - 1] + frac * (times[landing_step] - times[landing_step - 1])
        dxy = landing_pos[:2] - pos[0, :2]
        distance_m = float(np.linalg.norm(dxy))
        hang_time = float(landing_t)
        landed = True
    else:
        distance_m = 0.0
        hang_time = float(times[-1])
        landed = False
    return Trajectory(
        t=times.copy(),
        pos=pos.copy(),
        vel=vel.copy(),
        landed=landed,
        hang_time=hang_time,
        distance_m=distance_m,
        max_height_m=float(pos[:, 2].max()),
    )


__all__ = ("LaunchParams", "Trajectory", "simulate", "simulate_batch")
