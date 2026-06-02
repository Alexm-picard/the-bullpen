"""Trajectory + park -> 5-class batted-ball outcome (Phase 2c.3).

Given a simulated :class:`Trajectory` and a :class:`ParkGeometry`,
produce one of ``out / single / double / triple / home_run``. This is
the function 2c.4's retrodiction pipeline calls per (BIP, park).

The v1 classifier is geometry-driven (not learned):

  1. Find landing point in stadium frame (x_m, y_m) and the spray angle.
  2. If outside the foul lines (|spray| > 45 deg), call it ``out`` —
     foul outs are real outcomes; foul balls themselves don't enter
     the 5-class space (they stay live pitches, handled in 2c.5).
  3. Look up the fence distance + height at that spray angle.
  4. If the ball is over the fence height when it crosses the fence's
     spray-angle radius -> ``home_run``.
  5. Otherwise the ball lands in the field of play. The class is a
     coarse hang-time + landing-distance heuristic:
       - hang_time > 4.0 s     -> ``out`` (fly out, fielder catches it)
       - landing > 320 ft      -> ``double`` (in the gap, runner takes 2)
       - landing > 250 ft      -> ``single``
       - landing > 150 ft      -> ``single``  (looper)
       - otherwise             -> ``out`` (weak grounder / liner caught)

The triple branch is intentionally rare here; v1 only emits ``triple``
on long, low-angle balls (>= 380 ft landing AND hang_time < 5 s AND
near the deep corners). Real triples are noisy enough that 2c.4's
Monte Carlo wrapper will dominate the per-(BIP, park) probability
estimate anyway — this deterministic core just needs to be in the
right ballpark.

The simulator uses metres internally; this module converts to feet at
the API boundary. Conversion is centralised here so the rest of the
parks/ module stays unit-coherent with the public park geometry JSONs
(which are in feet).
"""

from __future__ import annotations

import math
from enum import StrEnum
from typing import TYPE_CHECKING

from bullpen_training.battedball.parks.loader import (
    ParkGeometry,
    fence_distance_at_spray_deg,
    fence_height_at_spray_deg,
)
from bullpen_training.battedball.physics._constants import M_TO_FT

if TYPE_CHECKING:
    from bullpen_training.battedball.physics.simulator import Trajectory


class Outcome(StrEnum):
    OUT = "out"
    SINGLE = "single"
    DOUBLE = "double"
    TRIPLE = "triple"
    HOME_RUN = "home_run"


# Foul-line limit (degrees off CF). ±45 is the canonical MLB foul line.
_FOUL_LINE_DEG = 45.0

# Default league-mean sprint speed; 2c.4 will call with batter-specific
# speed if available. Used to gate the triple branch (faster runners
# turn doubles into triples more often).
DEFAULT_SPRINT_SPEED_FPS = 27.0

# Fielder model — a ball must clear the fence by some margin (in distance
# AND height) before it's called HR, with near-misses splitting into
# warning-track OUTs (long hang) and wall-banger DOUBLES (short). The
# margin stands in for (a) residual carry slack and (b) outfielders robbing
# borderline HRs at the wall.
#
# Decision [132] originally set the distance margin to 45 ft, tuned to land
# the league HR rate at ~4.2 % — but that value was inflated to offset the
# simulator's no-wind over-carry (the +13/+21 ft bias of [131]). Phase 1
# removed that over-carry (physical batted-ball spin + calibrated cd_scale
# with real per-game weather), so +45 ft became badly over-conservative:
# it capped short-porch parks (NYY/PHI) from expressing their geometry and
# crushed the cross-park ranking (gate [52]). Re-tuned via
# scripts/calibrate_fielder.py on 8000 BIPs (2015-2025, empirical fences,
# Phase-1 physics): the 45 ft margin predicts 2.85 % HR vs ~4.6 % observed;
# 15 ft lands it at 4.19 % (= the [132] 4.2 % target) while letting the
# porches express. The height margin (25 ft) is unchanged — a secondary,
# unswept knob that becomes binding below ~15 ft distance.
DEFAULT_HR_MIN_DIST_PAST_FENCE_FT = 15.0
DEFAULT_HR_MIN_HEIGHT_OVER_FENCE_FT = 25.0
DEFAULT_WALL_HANG_CUTOFF_S = 4.5


def _landing_xy_ft(traj: Trajectory) -> tuple[float, float]:
    """Final ground position in stadium feet (x=CF axis, y=3B axis).

    Back-interpolates between the last two trajectory samples to the
    exact z=0 plane — the simulator stops one step past landing (z<0),
    so traj.pos[-1] would over-estimate XY by 5-15 ft otherwise. The
    same back-interp lives inside the simulator for distance_m; this
    is just its (x, y) projection.
    """
    pos = traj.pos
    if traj.landed and pos.shape[0] >= 2:
        p0, p1 = pos[-2], pos[-1]
        z0, z1 = float(p0[2]), float(p1[2])
        if z0 > 0.0 > z1:
            frac = z0 / (z0 - z1)
            x = float(p0[0]) + frac * (float(p1[0]) - float(p0[0]))
            y = float(p0[1]) + frac * (float(p1[1]) - float(p0[1]))
            return x * M_TO_FT, y * M_TO_FT
    return float(pos[-1, 0]) * M_TO_FT, float(pos[-1, 1]) * M_TO_FT


def _spray_deg_from_xy(x_ft: float, y_ft: float) -> float:
    """Spray angle (deg, + toward 3B / LF) from a landing (x, y) in feet."""
    if x_ft <= 0.0:
        # Ball ended up behind the plate or at the origin — caller's bug,
        # but classify it as out rather than crash.
        return 0.0
    return math.degrees(math.atan2(y_ft, x_ft))


def _crossing_height_at_fence(traj: Trajectory, fence_distance_ft: float) -> float | None:
    """Linearly interpolate the ball's height (ft) when it first crosses
    the fence-distance radius from home plate. Returns ``None`` if the
    ball never reaches that radius (short fly).

    "Crosses" here means "the XY distance from origin first reaches
    ``fence_distance_ft`` while z > 0". For HR balls this happens on
    the way down through the fence plane; for short flies it never
    happens.
    """
    # Walk pos in order; first sample whose XY radius exceeds the
    # fence radius gives us the crossing pair to interpolate between.
    pos = traj.pos
    fence_m = fence_distance_ft / M_TO_FT
    prev_r = 0.0
    prev_z = float(pos[0, 2])
    for i in range(1, pos.shape[0]):
        x, y, z = float(pos[i, 0]), float(pos[i, 1]), float(pos[i, 2])
        r = math.hypot(x, y)
        if r >= fence_m:
            if r == prev_r:
                return z * M_TO_FT
            frac = (fence_m - prev_r) / (r - prev_r)
            z_at_fence = prev_z + frac * (z - prev_z)
            return z_at_fence * M_TO_FT
        prev_r, prev_z = r, z
    return None


def classify_outcome(
    traj: Trajectory,
    park: ParkGeometry,
    *,
    sprint_speed_fps: float = DEFAULT_SPRINT_SPEED_FPS,
    hr_min_dist_past_fence_ft: float = DEFAULT_HR_MIN_DIST_PAST_FENCE_FT,
    hr_min_height_over_fence_ft: float = DEFAULT_HR_MIN_HEIGHT_OVER_FENCE_FT,
    wall_hang_cutoff_s: float = DEFAULT_WALL_HANG_CUTOFF_S,
) -> Outcome:
    """Classify a simulator :class:`Trajectory` against a park's fence.

    See module docstring for the decision flow. ``sprint_speed_fps``
    gates the triple branch (faster runners convert deep doubles into
    triples more often); the v1 classifier is otherwise speed-agnostic.

    Decision [132] tuning knobs (see DEFAULT_* constants above for
    rationale on why the bare physics check over-calls HRs):

    - ``hr_min_dist_past_fence_ft`` / ``hr_min_height_over_fence_ft``:
      a "vacuum" trajectory clearing the wall is necessary but not
      sufficient for a HR; the ball must clear by margin in both
      dimensions to outrun a leaping fielder + the simulator's known
      no-wind over-bias.
    - ``wall_hang_cutoff_s``: at the wall, long-hang flies are caught
      (warning-track outs) and short-hang liners hit the wall in play
      (doubles). Tuned on 500-BIP 2024 sample.
    """
    if not traj.landed:
        # The ball never came down — almost always means n_steps_max was
        # too small for an unusual launch; treat as out (the upstream
        # caller should bump steps).
        return Outcome.OUT

    x_ft, y_ft = _landing_xy_ft(traj)
    spray_deg = _spray_deg_from_xy(x_ft, y_ft)

    if abs(spray_deg) > _FOUL_LINE_DEG:
        # Foul territory landing -> the v1 classifier returns OUT (foul
        # outs are real). Foul balls in the stands aren't in the 5-class
        # space (they're handled upstream as live pitches).
        return Outcome.OUT

    landing_dist_ft = math.hypot(x_ft, y_ft)

    # HR test: ball must clear the wall by enough margin in both
    # distance AND height to not be caught at the warning track.
    fence_dist = fence_distance_at_spray_deg(park, spray_deg)
    fence_h = fence_height_at_spray_deg(park, spray_deg)
    if landing_dist_ft >= fence_dist:
        z_at_fence = _crossing_height_at_fence(traj, fence_dist)
        if (
            z_at_fence is not None
            and z_at_fence > fence_h + hr_min_height_over_fence_ft
            and landing_dist_ft >= fence_dist + hr_min_dist_past_fence_ft
        ):
            return Outcome.HOME_RUN
        # Ball reached the wall area but failed the HR margin check.
        # Two cases at the wall: high fly caught by OF; line drive off
        # the wall for a double. Hang time discriminates.
        if z_at_fence is not None and z_at_fence > fence_h:
            if traj.hang_time >= wall_hang_cutoff_s:
                return Outcome.OUT  # warning-track / wall catch
            return Outcome.DOUBLE  # off-the-wall line drive

    # In the park: distance + hang_time + sprint_speed heuristic.
    # The heuristic exists so the 2c.4 Monte Carlo wrapper has a sane
    # deterministic base; per-park noise is applied on top.
    if landing_dist_ft >= 380.0 and traj.hang_time < 5.0 and sprint_speed_fps >= 28.0:
        # Deep ball, low arc, fast runner -> triple is plausible.
        return Outcome.TRIPLE
    if traj.hang_time >= 4.0 and landing_dist_ft >= 250.0:
        # High enough fly that an outfielder reaches it.
        return Outcome.OUT
    if landing_dist_ft >= 320.0:
        return Outcome.DOUBLE
    if landing_dist_ft >= 150.0:
        return Outcome.SINGLE
    return Outcome.OUT


__all__ = (
    "DEFAULT_HR_MIN_DIST_PAST_FENCE_FT",
    "DEFAULT_HR_MIN_HEIGHT_OVER_FENCE_FT",
    "DEFAULT_SPRINT_SPEED_FPS",
    "DEFAULT_WALL_HANG_CUTOFF_S",
    "Outcome",
    "classify_outcome",
)
