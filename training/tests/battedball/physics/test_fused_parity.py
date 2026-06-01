"""Parity: fused integrate+classify (CPU) vs the reference simulate+classify.

The GPU-B fused kernel (``physics/_fused.py``) folds the RK4 integration and the
5-class classification into one register-resident pass that emits a single
outcome code — no trajectory history. The reference path keeps the two separate:
``simulate(...)`` materialises the full trajectory and ``classify_outcome(...)``
walks it.

These tests pin the fused CPU path (the njit/prange build — the only one runnable
off the desktop GPU, per ADR-0006) against that reference. They feed *identical*
initial conditions (same ``_initial_state`` velocities, same density + wind) so
the only sources of disagreement are the two intentional/accepted differences:

  1. float32 input cast on the fused side (the core still promotes to float64 via
     Python-float constants, so this is tiny), and
  2. the single-pass fence-crossing height uses the ball's spray *at the crossing
     step* rather than the *landing* spray the reference re-walk uses.

Both are documented in ``_fused.py``; for near-radial flight they shift fence
geometry by a fraction of a degree / ~1 ft. The agreement-rate thresholds below
are the guard that this never silently drifts. (The float32 *calibration* impact
is re-validated separately on the desktop against decision [131]'s ±25 ft gate.)
"""

from __future__ import annotations

import numpy as np
import pytest

from bullpen_training.battedball.parks import Outcome, classify_outcome, load_park_geometry
from bullpen_training.battedball.physics._constants import RPM_TO_RAD_S
from bullpen_training.battedball.physics._fused import (
    DOUBLE_CODE,
    HR_CODE,
    OUT_CODE,
    SINGLE_CODE,
    TRAJ_IN_COLS,
    TRIPLE_CODE,
    simulate_classify_cpu,
)
from bullpen_training.battedball.physics.atmosphere import Atmosphere
from bullpen_training.battedball.physics.simulator import (
    LaunchParams,
    _initial_state,
    _spin_axis_unit_from_tilt,
    simulate,
)
from bullpen_training.battedball.retrodict.labels import _build_fence_arrays

_CODE_TO_OUTCOME = {
    OUT_CODE: Outcome.OUT,
    SINGLE_CODE: Outcome.SINGLE,
    DOUBLE_CODE: Outcome.DOUBLE,
    TRIPLE_CODE: Outcome.TRIPLE,
    HR_CODE: Outcome.HOME_RUN,
}

_PARKS = ("NYY", "COL", "SF", "BOS", "DET", "SD")


def _ref_outcome(launch: LaunchParams, atmo: Atmosphere, park_id: str) -> Outcome:
    return classify_outcome(simulate(launch, atmo), load_park_geometry(park_id))


def _fused_outcome(launch: LaunchParams, atmo: Atmosphere, park_id: str) -> Outcome:
    state0 = _initial_state(launch)
    spin_axis = _spin_axis_unit_from_tilt(launch.spin_axis_tilt_deg)
    wind = atmo.wind_vec_m_s
    traj_in = np.zeros((1, TRAJ_IN_COLS), dtype=np.float32)
    traj_in[0, 0] = state0[3]  # vx
    traj_in[0, 1] = state0[4]  # vy
    traj_in[0, 2] = state0[5]  # vz
    traj_in[0, 3] = state0[2]  # initial height
    traj_in[0, 4] = spin_axis[0]
    traj_in[0, 5] = spin_axis[1]
    traj_in[0, 6] = spin_axis[2]
    traj_in[0, 7] = launch.spin_rate_rpm * RPM_TO_RAD_S
    traj_in[0, 8] = atmo.density
    traj_in[0, 9] = wind[0]
    traj_in[0, 10] = wind[1]
    traj_in[0, 11] = wind[2]
    angles, dists, heights, counts = _build_fence_arrays((park_id,))
    codes = simulate_classify_cpu(
        traj_in,
        np.zeros(1, dtype=np.int32),
        angles,
        dists,
        heights,
        counts,
        dt=0.005,
        n_steps_max=2000,
    )
    return _CODE_TO_OUTCOME[int(codes[0])]


# --- clear, non-borderline cases must match exactly -----------------------


def test_barrelled_hr_matches_reference_at_short_porch() -> None:
    """A 110 mph / 28 deg pull to RF at Yankee Stadium is a HR both ways."""
    launch = LaunchParams(launch_speed_mph=110.0, launch_angle_deg=28.0, spray_angle_deg=-30.0)
    atmo = Atmosphere()
    assert _ref_outcome(launch, atmo, "NYY") == Outcome.HOME_RUN
    assert _fused_outcome(launch, atmo, "NYY") == Outcome.HOME_RUN


def test_weak_grounder_matches_reference_out() -> None:
    """A 70 mph / 4 deg chopper is an OUT both ways at any park."""
    launch = LaunchParams(launch_speed_mph=70.0, launch_angle_deg=4.0, spray_angle_deg=5.0)
    atmo = Atmosphere()
    for park_id in ("NYY", "COL", "SF"):
        assert _ref_outcome(launch, atmo, park_id) == Outcome.OUT
        assert _fused_outcome(launch, atmo, park_id) == Outcome.OUT


# --- broad grid: agreement rate is the guard for the documented deviation --


def test_fused_matches_reference_on_representative_grid() -> None:
    launches = [
        LaunchParams(launch_speed_mph=s, launch_angle_deg=a, spray_angle_deg=sp)
        for s in (75.0, 90.0, 100.0, 108.0)
        for a in (10.0, 20.0, 30.0, 38.0)
        for sp in (-30.0, 0.0, 25.0)
    ]
    atmo = Atmosphere()
    agree = 0
    total = 0
    for launch in launches:
        for park_id in _PARKS:
            total += 1
            if _ref_outcome(launch, atmo, park_id) == _fused_outcome(launch, atmo, park_id):
                agree += 1
    rate = agree / total
    assert rate >= 0.9, f"fused/reference outcome agreement {rate:.3f} on grid of {total}"


def test_fused_matches_reference_on_random_grid() -> None:
    """200 random launches x a few parks — catches any branch the curated grid
    missed. Borderline wall balls may flip on the fence-crossing-spray
    approximation, so we require a high agreement rate, not exact match."""
    rng = np.random.default_rng(20260601)
    atmo = Atmosphere()
    agree = 0
    total = 0
    for _ in range(200):
        launch = LaunchParams(
            launch_speed_mph=float(rng.uniform(60.0, 110.0)),
            launch_angle_deg=float(rng.uniform(5.0, 40.0)),
            spray_angle_deg=float(rng.uniform(-40.0, 40.0)),
            spin_rate_rpm=float(rng.uniform(1500.0, 2500.0)),
        )
        park_id = _PARKS[int(rng.integers(len(_PARKS)))]
        total += 1
        if _ref_outcome(launch, atmo, park_id) == _fused_outcome(launch, atmo, park_id):
            agree += 1
    rate = agree / total
    assert rate >= 0.9, f"fused/reference random-grid agreement {rate:.3f} over {total}"


@pytest.mark.parametrize("altitude_m", [8.0, 1580.0])
def test_fused_respects_density_like_reference(altitude_m: float) -> None:
    """Carry at Coors altitude vs sea level should move the classification the
    same direction in both paths for a deep fly."""
    launch = LaunchParams(launch_speed_mph=103.0, launch_angle_deg=27.0, spray_angle_deg=0.0)
    atmo = Atmosphere(altitude_m=altitude_m)
    assert _fused_outcome(launch, atmo, "COL") == _ref_outcome(launch, atmo, "COL")
