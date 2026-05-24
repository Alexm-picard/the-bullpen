"""Tests for the park-aware outcome classifier (Phase 2c.3).

The v1 classifier is a deterministic heuristic on a :class:`Trajectory`.
These tests construct ``Trajectory`` objects directly with known
landing points so the assertions test ONLY the classifier — not the
simulator dynamics (whose Magnus effect bends spray angles during
flight and would couple test outcomes to launch tuning).

Each fixture builds a minimal 3-sample trajectory: launch, apex, and a
back-interpolatable landing pair. That's the smallest input that
exercises both ``_crossing_height_at_fence`` and the ``landed`` flag.
"""

from __future__ import annotations

import math

import numpy as np
import pytest

from bullpen_training.battedball.parks import Outcome, classify_outcome, load_park_geometry
from bullpen_training.battedball.physics._constants import M_TO_FT
from bullpen_training.battedball.physics.simulator import Trajectory

_FT_TO_M = 1.0 / M_TO_FT


def _make_traj(
    landing_dist_ft: float,
    spray_deg: float,
    *,
    apex_ft: float = 90.0,
    hang_time_s: float = 4.5,
    height_at_fence_ft: float | None = None,
    fence_dist_ft: float | None = None,
    landed: bool = True,
    n_samples: int = 60,
) -> Trajectory:
    """Hand-build a Trajectory that lands at (dist, spray) with given apex.

    Samples a smooth quadratic z(r) along a straight-line ground path
    so the classifier's linear-interp height lookup is faithful at any
    fence radius — no test-by-test sample tuning. If
    ``height_at_fence_ft`` + ``fence_dist_ft`` are supplied, the
    quadratic is fit through three constraints (launch, fence-point,
    landing); otherwise just (launch, apex, landing).

    Geometry-only — no real physics integration — so the test asserts
    the classifier's behaviour against a precisely-known input.
    """
    cos_s = math.cos(math.radians(spray_deg))
    sin_s = math.sin(math.radians(spray_deg))

    def z_of_r(r_ft: float) -> float:
        """Quadratic z(r) anchored on (0, ~1ft), passing through either
        (fence_dist, height_at_fence) or (landing/2, apex), and (landing, 0)."""
        # Two anchors are fixed: launch (r=0, z~1ft contact height) and
        # landing (r=landing_dist, z=0). The third constraint shapes
        # the apex / fence-crossing height.
        launch_h = 1.0  # ~1 ft contact height
        if height_at_fence_ft is not None and fence_dist_ft is not None:
            r1, z1 = fence_dist_ft, height_at_fence_ft
        else:
            r1, z1 = landing_dist_ft / 2.0, apex_ft
        # Solve z = a*r^2 + b*r + c with c = launch_h
        c = launch_h
        # (a*r1^2 + b*r1) = z1 - c
        # (a*L^2 + b*L)   = -c
        L = landing_dist_ft
        det = r1 * r1 * L - L * L * r1
        if abs(det) < 1e-9:
            return 0.0
        a = ((z1 - c) * L - (-c) * r1) / det
        b = ((-c) * r1 * r1 - (z1 - c) * L * L) / det
        return a * r_ft * r_ft + b * r_ft + c

    samples: list[tuple[float, float, float, float]] = []
    # Distribute samples evenly along r in [0, landing], plus one just past.
    for i in range(n_samples):
        frac = i / (n_samples - 1)
        r_ft = frac * landing_dist_ft
        z_ft = max(z_of_r(r_ft), 0.0) if frac < 1.0 else 0.0
        t = frac * hang_time_s
        samples.append((r_ft * cos_s * _FT_TO_M, r_ft * sin_s * _FT_TO_M, z_ft * _FT_TO_M, t))
    # One sample just past landing so the back-interp picks z=0 cleanly.
    samples.append(
        (
            1.005 * landing_dist_ft * cos_s * _FT_TO_M,
            1.005 * landing_dist_ft * sin_s * _FT_TO_M,
            -0.1 * _FT_TO_M,
            hang_time_s + 0.01,
        )
    )
    pos = np.array([[x, y, z] for x, y, z, _t in samples], dtype=np.float64)
    vel = np.zeros_like(pos)
    t_arr = np.array([s[3] for s in samples], dtype=np.float64)
    return Trajectory(
        t=t_arr,
        pos=pos,
        vel=vel,
        landed=landed,
        hang_time=hang_time_s,
        distance_m=landing_dist_ft * _FT_TO_M,
        max_height_m=apex_ft * _FT_TO_M,
    )


# --- HRs that should clear the fence --------------------------------------


def test_dead_pull_hr_at_yankee_short_porch() -> None:
    """A ball that lands at 380 ft, -45 deg (RF foul pole) with 40 ft
    of height when it crosses NYY's 314 ft RF foul-pole fence is a HR.
    Margins set well past the decision-[132] HR thresholds
    (45 ft past fence + 25 ft above wall) so the test pins HR semantics
    rather than the exact threshold values."""
    park = load_park_geometry("NYY")
    traj = _make_traj(
        landing_dist_ft=380.0,
        spray_deg=-45.0,  # RF foul pole — matches polyline endpoint exactly
        apex_ft=100.0,
        hang_time_s=4.8,
        height_at_fence_ft=40.0,
        fence_dist_ft=314.0,
    )
    assert classify_outcome(traj, park) == Outcome.HOME_RUN


def test_moon_shot_to_cf_at_coors() -> None:
    """A 475 ft shot to dead CF clears Coors' 415 ft CF wall by enough
    margin to defeat the fielder-model HR thresholds."""
    park = load_park_geometry("COL")
    traj = _make_traj(
        landing_dist_ft=475.0,
        spray_deg=0.0,
        apex_ft=120.0,
        hang_time_s=5.5,
        height_at_fence_ft=40.0,
        fence_dist_ft=415.0,
    )
    assert classify_outcome(traj, park) == Outcome.HOME_RUN


def test_borderline_shot_clears_nyy_but_not_comerica() -> None:
    """A 460 ft shot to CF clears Yankee Stadium (CF 408, 460-408=52 >
    45 ft HR margin) but NOT Comerica (CF 420, 460-420=40 < 45). Same
    trajectory, different parks — the headline geometry case from the
    leaf, retuned for the decision [132] thresholds."""
    traj = _make_traj(
        landing_dist_ft=460.0,
        spray_deg=0.0,
        apex_ft=110.0,
        hang_time_s=5.0,
        height_at_fence_ft=40.0,
        fence_dist_ft=408.0,
    )
    nyy = load_park_geometry("NYY")
    det = load_park_geometry("DET")
    assert classify_outcome(traj, nyy) == Outcome.HOME_RUN
    assert classify_outcome(traj, det) != Outcome.HOME_RUN


# --- Fenway's Monster -----------------------------------------------------


def test_high_fly_off_the_monster_is_not_hr() -> None:
    """A fly to LF that lands 330 ft from the plate, 30 ft of height when
    it crosses BOS's Monster (310 ft / 37 ft tall), is NOT a HR — the
    Monster's height stopped it. spray=+45 sits on the LF foul-pole
    polyline endpoint so the classifier's fence lookup matches the
    test's ``fence_dist_ft`` exactly (no polyline interpolation in
    play)."""
    park = load_park_geometry("BOS")
    traj = _make_traj(
        landing_dist_ft=330.0,
        spray_deg=+45.0,
        apex_ft=90.0,
        hang_time_s=4.6,
        height_at_fence_ft=30.0,  # below Monster's 37 ft
        fence_dist_ft=310.0,
    )
    assert classify_outcome(traj, park) != Outcome.HOME_RUN


def test_same_fly_to_lf_at_yankee_is_hr() -> None:
    """The same trajectory archetype that died on the Monster but
    lands 370 ft (52 ft past NYY's 318 LF wall) and clears it by
    enough height to defeat the fielder-model thresholds is a HR."""
    park = load_park_geometry("NYY")
    traj = _make_traj(
        landing_dist_ft=370.0,
        spray_deg=+45.0,
        apex_ft=100.0,
        hang_time_s=4.6,
        height_at_fence_ft=45.0,
        fence_dist_ft=318.0,
    )
    assert classify_outcome(traj, park) == Outcome.HOME_RUN


# --- foul + in-play classification ---------------------------------------


def test_well_struck_ball_in_foul_territory_is_out() -> None:
    """A line drive with |spray| > 45 deg lands foul -> v1 classifies as out."""
    park = load_park_geometry("STL")
    traj = _make_traj(landing_dist_ft=320.0, spray_deg=+55.0)
    assert classify_outcome(traj, park) == Outcome.OUT


def test_long_high_fly_in_play_is_out() -> None:
    """A 340 ft fly with 5 s hang to CF is a routine fly out."""
    park = load_park_geometry("STL")
    traj = _make_traj(landing_dist_ft=340.0, spray_deg=0.0, hang_time_s=5.0)
    assert classify_outcome(traj, park) == Outcome.OUT


def test_gap_double_in_lcf() -> None:
    """A 340 ft liner to LCF with 3.5 s hang is too low for the OF to
    catch and deep enough to be a double."""
    park = load_park_geometry("STL")
    traj = _make_traj(
        landing_dist_ft=340.0,
        spray_deg=+25.0,
        apex_ft=50.0,
        hang_time_s=3.5,
    )
    assert classify_outcome(traj, park) == Outcome.DOUBLE


def test_soft_liner_is_single() -> None:
    """200 ft liner with 2.5 s hang falls between IF and OF for a single."""
    park = load_park_geometry("STL")
    traj = _make_traj(
        landing_dist_ft=200.0,
        spray_deg=0.0,
        apex_ft=25.0,
        hang_time_s=2.5,
    )
    assert classify_outcome(traj, park) == Outcome.SINGLE


def test_weak_grounder_classifies_as_out() -> None:
    """A 100 ft topped grounder is an out under v1."""
    park = load_park_geometry("STL")
    traj = _make_traj(
        landing_dist_ft=100.0,
        spray_deg=+10.0,
        apex_ft=8.0,
        hang_time_s=1.5,
    )
    assert classify_outcome(traj, park) == Outcome.OUT


# --- altitude / park-comparison edge cases ---------------------------------


def test_borderline_hr_clears_coors_but_not_oracle() -> None:
    """A 450 ft shot to CF clears Oracle's 391 ft CF (450-391=59 > 45
    decision [132] HR margin) but NOT Coors' 415 ft CF (450-415=35 <
    45). Same trajectory, different parks; the contrast pins the
    park-geometry distinction even with the fielder model on."""
    traj = _make_traj(
        landing_dist_ft=450.0,
        spray_deg=0.0,
        apex_ft=115.0,
        hang_time_s=5.0,
        height_at_fence_ft=40.0,
        fence_dist_ft=391.0,
    )
    col = load_park_geometry("COL")
    sf = load_park_geometry("SF")
    assert classify_outcome(traj, col) != Outcome.HOME_RUN
    assert classify_outcome(traj, sf) == Outcome.HOME_RUN


def test_low_liner_off_the_wall_is_double() -> None:
    """A 100 mph liner with 3.0 s hang that lands at the LF foul pole
    of NYY (318 ft), clearing the 8 ft wall by 10 ft, falls short of
    the HR distance threshold (decision [132]) and gets called a
    DOUBLE — the wall-banger branch (short hang at the wall)."""
    park = load_park_geometry("NYY")
    traj = _make_traj(
        landing_dist_ft=325.0,  # only 7 ft past 318 ft fence
        spray_deg=+45.0,
        apex_ft=40.0,
        hang_time_s=3.0,
        height_at_fence_ft=18.0,  # 10 ft above 8 ft wall but inside HR margin
        fence_dist_ft=318.0,
    )
    assert classify_outcome(traj, park) == Outcome.DOUBLE


def test_high_fly_caught_at_wall_is_out() -> None:
    """A 95 mph high fly with 5.0 s hang that lands at the wall (10 ft
    past the fence, just above wall height) is a warning-track OUT —
    long hang means the CF catches it before it reaches the seats."""
    park = load_park_geometry("NYY")
    traj = _make_traj(
        landing_dist_ft=328.0,
        spray_deg=+45.0,
        apex_ft=80.0,
        hang_time_s=5.0,
        height_at_fence_ft=12.0,  # 4 ft above wall but inside HR margin
        fence_dist_ft=318.0,
    )
    assert classify_outcome(traj, park) == Outcome.OUT


def test_ball_clears_fence_distance_but_below_wall_height_is_not_hr() -> None:
    """A line drive to PIT's RF (Clemente Wall: 320 ft / 21 ft tall) that
    lands 340 ft but is only 15 ft high at the wall is NOT a HR."""
    park = load_park_geometry("PIT")
    traj = _make_traj(
        landing_dist_ft=340.0,
        spray_deg=-42.0,
        apex_ft=45.0,
        hang_time_s=3.4,
        height_at_fence_ft=15.0,  # below the 21 ft Clemente Wall
        fence_dist_ft=320.0,
    )
    assert classify_outcome(traj, park) != Outcome.HOME_RUN


# --- defensive coverage of classifier internals ----------------------------


def test_classifier_handles_unlanded_trajectory_gracefully() -> None:
    """If the simulator never reports landing (n_steps_max too small),
    classify falls back to OUT rather than crashing."""
    traj = _make_traj(
        landing_dist_ft=300.0, spray_deg=0.0, apex_ft=80.0, hang_time_s=3.0, landed=False
    )
    park = load_park_geometry("STL")
    assert classify_outcome(traj, park) == Outcome.OUT


def test_outcome_enum_values_match_5_class_spec() -> None:
    """Phase 2c spec is 5-class: out / single / double / triple / home_run."""
    values = {o.value for o in Outcome}
    assert values == {"out", "single", "double", "triple", "home_run"}


@pytest.mark.parametrize("park_id", ["NYY", "BOS", "COL", "DET", "SF"])
def test_classifier_returns_known_outcome_for_every_park(park_id: str) -> None:
    """Smoke: classifying a generic 380 ft fly at any park returns
    one of the 5 valid outcomes (no None, no crash)."""
    park = load_park_geometry(park_id)
    traj = _make_traj(landing_dist_ft=380.0, spray_deg=0.0, hang_time_s=5.0)
    out = classify_outcome(traj, park)
    assert isinstance(out, Outcome)
