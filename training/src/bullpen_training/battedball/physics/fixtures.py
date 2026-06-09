"""Build the 100-fixture validation set for the physics simulator (Phase 2c.2).

Pulls real batted balls from ClickHouse, then assembles each row into a
JSON fixture the validation harness in :mod:`validate` can consume.

What Statcast actually gives us (per the V003__pitches.sql schema):
  - launch_speed_mph, launch_angle_deg — off-the-bat exit data
  - hc_x, hc_y — landing coords in scaled pixel space (use for spray angle)
  - hit_distance_ft — observed total carry distance (the validation target)
  - bb_type, events, park_id, stand — categorical context

What it does NOT give us:
  - Ball spin rate / spin axis off the bat. Statcast measures the
    pitch's spin (release_spin_rate) but not the bat's. We derive it from
    the calibrated launch-angle spin model (``physics/spin.py``), the same
    model the retrodiction labels + the validation gate use, so the fixture's
    baked spin matches the physics it will be scored under.
  - Hang time. No observed hang time, so the harness reports the
    simulator's hang time for visibility but doesn't gate on it.
  - Wind / temperature / humidity at game time. Defaulted to
    20 °C / 50 % RH / no wind via :func:`parks.park_atmosphere`. The
    weather-pull join is a 2c.4 refinement; the validation tolerance
    here covers the no-wind assumption for ~80 % of MLB days.

Spray-angle convention (Statcast hc_x/hc_y → simulator spray_angle_deg):

    sim_spray_rad = atan2(125.42 - hc_x, 198.27 - hc_y)

    The simulator uses +ve toward 3B (LF). Statcast hc_x increases from
    LF (low) toward RF (high); pivoting around (125.42, 198.27) (the
    Statcast home-plate coordinates) and negating the x term gives the
    LF-positive convention the sim expects.
"""

from __future__ import annotations

import argparse
import json
import math
import subprocess
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Literal

from bullpen_training.battedball.physics.spin import (
    PhysicsCalibration,
    batted_ball_spin,
    load_physics_calibration,
)

# Statcast home-plate coordinates in the scaled hc_x/hc_y system.
_HC_X_HOME = 125.42
_HC_Y_HOME = 198.27


@dataclass(frozen=True)
class Fixture:
    """One curated batted-ball fixture for physics validation.

    Inputs the simulator consumes are split into ``launch`` (LaunchParams
    kwargs) and ``atmosphere`` (park-id, harness derives Atmosphere via
    :func:`parks.park_atmosphere`). Observed ground truth is the carry
    distance Statcast measured.
    """

    fixture_id: str
    bb_type: Literal["home_run", "fly_ball", "line_drive"]
    park_id: str
    stand: str  # 'L' or 'R'
    launch: dict[str, float]  # kwargs for LaunchParams
    observed_distance_ft: float
    # game_id / game_date let the validation harness join per-game weather
    # (weather_observed) so carry is scored under the ACTUAL conditions of each
    # HR rather than the no-wind/20C park default (decision [131]'s +12 ft bias).
    game_id: int = 0
    game_date: str = ""
    notes: list[str] = field(default_factory=list)


def spray_angle_deg_from_hc(hc_x: float, hc_y: float) -> float:
    """Statcast hc_x/hc_y -> sim spray angle (deg, + toward 3B/LF)."""
    return math.degrees(math.atan2(_HC_X_HOME - hc_x, _HC_Y_HOME - hc_y))


def spin_prior_for(bb_type: str, launch_angle_deg: float) -> tuple[float, float]:
    """LEGACY flat spin prior — retained for reference only, no longer used.

    Superseded by the calibrated launch-angle spin model
    (``physics/spin.py::batted_ball_spin``). Fixtures now bake spin from that
    model, and the validation gate recomputes it from the live calibration, so
    this bucketed prior no longer feeds either path. Kept so the Phase-0 history
    (decision [131], the 2200->1800 reverse-tune) stays legible in the tree.

    Statcast doesn't measure batted-ball spin. The simulator needs both,
    so we use Nathan-typical priors keyed on the kind of batted ball:

      - HR / fly arcs (launch_angle >= 20 deg): 1800 rpm backspin
        (an empirical compromise — 2200 rpm matched HR median carry
        only at very low wind; 1800 rpm + no-wind defaults reproduces
        the +12 ft mean over-bias we observed against Statcast, which
        roughly equals MLB's mean light-tailwind effect)
      - Low liners (launch_angle 10-20 deg): 1500 rpm backspin
      - Topped balls (launch_angle < 10 deg): 1000 rpm — completeness
        only; the validation fixture set excludes ground balls.

    Tilt is 180 deg (pure backspin around -y) in all cases — sidespin
    is a real effect on hooked / sliced pulls but on the order of 10 %
    of total spin energy and inside the validation tolerance.
    """
    if launch_angle_deg >= 20.0:
        return 1800.0, 180.0
    if launch_angle_deg >= 10.0:
        return 1500.0, 180.0
    return 1000.0, 180.0


def _run_clickhouse_tsv(query: str) -> list[list[str]]:
    """Execute a TSV-formatted ClickHouse query via the docker container."""
    out = subprocess.run(
        ["docker", "exec", "bullpen-clickhouse", "clickhouse-client", "--query", query],
        check=True,
        capture_output=True,
        text=True,
    )
    rows = [line.split("\t") for line in out.stdout.strip().split("\n") if line]
    return rows


def _bucket_query(
    *,
    events_filter: str,
    bb_type_filter: str,
    distance_min: float,
    distance_max: float,
    launch_speed_min: float,
    limit_per_park: int,
    parks: list[str] | None = None,
    season: int = 2024,
) -> str:
    """Build a ClickHouse query that pulls a stratified bucket.

    Strategy: pull ``limit_per_park`` rows per requested park (default:
    all 30 parks) so the fixture set is geographically spread. Coors
    deliberately included to stress the altitude branch of the air-density
    model. Order by ``cityHash64(...)`` for a deterministic-but-shuffled
    sample (same fixture set on every re-run).
    """
    parks = parks or []
    park_filter = ""
    if parks:
        plist = ", ".join(f"'{p}'" for p in parks)
        park_filter = f"AND park_id IN ({plist})"
    return f"""
    SELECT
      toString(game_id) AS game_id,
      toString(at_bat_index) AS at_bat_index,
      toString(pitch_number) AS pitch_number,
      toString(launch_speed_mph) AS launch_speed_mph,
      toString(launch_angle_deg) AS launch_angle_deg,
      toString(hc_x) AS hc_x,
      toString(hc_y) AS hc_y,
      toString(hit_distance_ft) AS hit_distance_ft,
      park_id, stand, bb_type, events,
      toString(toDate(game_date)) AS game_date
    FROM (
        SELECT
          game_id, at_bat_index, pitch_number,
          launch_speed_mph, launch_angle_deg, hc_x, hc_y, hit_distance_ft,
          park_id, stand, bb_type, events, game_date,
          row_number() OVER (
            PARTITION BY park_id
            ORDER BY cityHash64(game_id, at_bat_index, pitch_number)
          ) AS rn
        FROM pitches
        WHERE description = 'in_play'
          AND events {events_filter}
          AND bb_type {bb_type_filter}
          AND launch_speed_mph >= {launch_speed_min}
          AND launch_angle_deg IS NOT NULL
          AND hc_x IS NOT NULL AND hc_y IS NOT NULL
          AND hit_distance_ft IS NOT NULL
          AND hit_distance_ft BETWEEN {distance_min} AND {distance_max}
          AND toYear(game_date) = {season}
          {park_filter}
    )
    WHERE rn <= {limit_per_park}
    ORDER BY park_id, cityHash64(game_id, at_bat_index, pitch_number)
    FORMAT TSV
    """


def _row_to_fixture(
    row: list[str], bucket_label: str, idx: int, calib: PhysicsCalibration
) -> Fixture | None:
    """Parse a ClickHouse TSV row into a Fixture. Returns None on bad data.

    Spin is derived from the calibrated launch-angle model (``calib.spin``) so
    the baked value matches the physics the gate + labels run. (The gate
    recomputes spin from the live calibration regardless, so this only keeps the
    on-disk fixture self-consistent.)
    """
    try:
        (
            game_id,
            _abi,
            _pn,
            launch_speed_mph,
            launch_angle_deg,
            hc_x,
            hc_y,
            hit_distance_ft,
            park_id,
            stand,
            bb_type,
            events,
            game_date,
        ) = row
    except ValueError:
        return None

    launch_speed = float(launch_speed_mph)
    launch_angle = float(launch_angle_deg)
    spray = spray_angle_deg_from_hc(float(hc_x), float(hc_y))
    spin_rate, tilt = batted_ball_spin(launch_speed, launch_angle, spray, calib.spin)
    observed = float(hit_distance_ft)

    label: Literal["home_run", "fly_ball", "line_drive"]
    if events == "home_run":
        label = "home_run"
    elif bb_type == "fly_ball":
        label = "fly_ball"
    elif bb_type == "line_drive":
        label = "line_drive"
    else:
        return None

    return Fixture(
        fixture_id=f"{bucket_label}_{idx:03d}",
        bb_type=label,
        park_id=park_id,
        stand=stand,
        launch={
            "launch_speed_mph": launch_speed,
            "launch_angle_deg": launch_angle,
            "spray_angle_deg": round(spray, 3),
            "spin_rate_rpm": spin_rate,
            "spin_axis_tilt_deg": tilt,
            "initial_height_m": 1.0,
        },
        observed_distance_ft=observed,
        game_id=int(game_id),
        game_date=game_date,
        notes=[],
    )


def build_fixture_set(
    season: int = 2024, *, limit_per_park: int = 4, max_fixtures: int = 100
) -> list[Fixture]:
    """Curate the HR validation set — HRs only.

    Defaults (``limit_per_park=4``, ``max_fixtures=100``) reproduce the committed
    100-fixture 2c.2 gate set exactly. Bumping ``limit_per_park`` (e.g. 200) builds
    a multi-thousand-HR set for an at-scale carry check under ``validate --weather``
    - same population (HR, 350-500 ft, EV >= 95), same per-park cityHash ordering,
    so it is a superset-shaped, deterministic draw, not a different sample.

    The original leaf called for a 50/30/20 split of HR / fly-out /
    line-drive, but Statcast's ``hit_distance_ft`` for non-HRs is the
    *first-contact* location (catch / wall / first bounce), NOT the
    full uninterrupted carry the simulator computes. Comparing the two
    yields systematic + ~100 ft over-bias on fly outs and line drives
    (the simulator is correct; Statcast is measuring a different
    thing). HRs are the only category where Statcast's distance equals
    full carry (the projectile model is extrapolated through the apex
    + trajectory to the ground for over-the-fence balls).

    So we pull 100 HRs, ~3-4 per park (Coors deliberately included)
    to keep geographic + altitude variety. distance 350-500 ft to
    avoid the "wall-scrapers" (~340 ft) where the fence cuts off the
    measurement, and to filter out the rare 500+ ft outliers where
    Statcast's extrapolation gets noisy.

    Once 2c.4's weather pull lands, the original 50/30/20 + tighter
    tolerance can be retried — see decisions.md entry on the gate
    revision for the original-tolerance re-validation plan.
    """
    out: list[Fixture] = []
    calib = load_physics_calibration()
    rows = _run_clickhouse_tsv(
        _bucket_query(
            events_filter="= 'home_run'",
            bb_type_filter="!= ''",
            distance_min=350.0,
            distance_max=500.0,
            launch_speed_min=95.0,
            limit_per_park=limit_per_park,
            season=season,
        )
    )
    for i, row in enumerate(rows[:max_fixtures]):
        fx = _row_to_fixture(row, "hr", i + 1, calib)
        if fx is not None:
            out.append(fx)
    return out


def fixtures_to_json(fixtures: list[Fixture]) -> str:
    return json.dumps(
        {
            "schema_version": 1,
            "n_fixtures": len(fixtures),
            "fixtures": [asdict(fx) for fx in fixtures],
        },
        indent=2,
    )


def main() -> None:
    parser = argparse.ArgumentParser(description="Build the 2c.2 physics validation fixture set.")
    parser.add_argument(
        "--out",
        type=Path,
        default=Path("training/data/physics_validation_fixtures.json"),
        help="Output JSON path (default: training/data/physics_validation_fixtures.json).",
    )
    parser.add_argument("--season", type=int, default=2024)
    parser.add_argument(
        "--limit-per-park",
        type=int,
        default=4,
        help="HRs per park (default 4 = the 100-fixture gate set; 200 = ~6k at-scale set).",
    )
    parser.add_argument(
        "--max",
        type=int,
        default=100,
        dest="max_fixtures",
        help="Total fixture cap (default 100 = the gate set; raise for an at-scale set).",
    )
    args = parser.parse_args()

    fixtures = build_fixture_set(
        season=args.season, limit_per_park=args.limit_per_park, max_fixtures=args.max_fixtures
    )
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(fixtures_to_json(fixtures))
    by_label: dict[str, int] = {}
    for fx in fixtures:
        by_label[fx.bb_type] = by_label.get(fx.bb_type, 0) + 1
    print(f"wrote {len(fixtures)} fixtures to {args.out}: {by_label}")


if __name__ == "__main__":
    main()


__all__ = (
    "Fixture",
    "build_fixture_set",
    "fixtures_to_json",
    "spin_prior_for",
    "spray_angle_deg_from_hc",
)
