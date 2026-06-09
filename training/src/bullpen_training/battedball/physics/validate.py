"""Run the 100-fixture physics validation harness (Phase 2c.2 gate).

Loads the fixture set produced by :mod:`fixtures`, runs each row through
the JIT simulator under the park-defaulted atmosphere, and compares the
reconstructed carry distance to Statcast's observed value. The aggregate
report is written to ``training/data/physics_validation_report.json``
and gates the rest of Phase 2c — per decision [49], no model in 2c can
train until ``pass_rate_distance >= 0.95`` and ``mae_distance_ft <= 20``.

The harness applies the SAME calibrated physics the retrodiction labels use
(``retrodict/labels.py``): spin is recomputed per fixture from EV/LA/spray via
the calibrated launch-angle spin model and the calibrated global drag scale
(``physics_calibration.json``) is passed to the simulator. The flat spin prior
baked into each fixture at build time is informational only — overriding it here
keeps the gate a test of the real label-generating physics rather than an
obsolete prior. (Before this wiring the gate silently validated the legacy flat
1800 rpm + cd_scale=1.0, diverging from the labels it was meant to gate.)

Pass criterion per fixture (REVISED — see decisions.md gate-revision
entry; original leaf was ±5 % / ±15 ft / 95 % gate, which is unachievable
without measured spin + game-day weather):
  - Within 10 % of observed distance, OR within 25 ft absolute.
  - Aggregate gate: pass_rate >= 0.85 AND mae_distance_ft <= 30.
  - The OR-form tolerance is important for short flights where 10 %
    is < 25 ft and Statcast's quantisation (~5 ft) eats the budget.
  - The original tighter gate will be re-attempted once 2c.4's weather
    pull provides game-time wind + temperature (today defaulted to
    no-wind + 20 °C, which alone produces a +12 ft mean over-bias).

What we do NOT gate on (deviation from the original leaf):
  - Hang time. Statcast doesn't publish observed hang time, so the
    report carries simulator hang time for visibility but the leaf's
    original ``mae_hang_s <= 0.3`` criterion is informational only.
"""

from __future__ import annotations

import argparse
import json
from collections.abc import Iterable
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any

from bullpen_training.battedball.physics.atmosphere import Atmosphere
from bullpen_training.battedball.physics.parks import park_atmosphere
from bullpen_training.battedball.physics.simulator import LaunchParams, simulate
from bullpen_training.battedball.physics.spin import (
    PhysicsCalibration,
    batted_ball_spin,
    load_physics_calibration,
)

# Pass tolerance constants. REVISED from the original leaf — see the module
# docstring + decisions.md entry. Tighten back to (0.05, 15.0, 0.95, 20.0)
# when 2c.4's weather pull lands and per-game wind+temp can be used.
_DISTANCE_TOL_PCT = 0.10
_DISTANCE_TOL_FT_ABS = 25.0
_REPORT_PASS_RATE_GATE = 0.85
_REPORT_MAE_DISTANCE_FT_GATE = 30.0

# Launch-angle buckets for the informational per-LA breakdown (issue #24's carry
# gradient). Outer bounds (0, 90) so every HR lands in exactly one bucket.
_LA_BUCKETS: tuple[tuple[float, float], ...] = (
    (0.0, 22.0),
    (22.0, 26.0),
    (26.0, 30.0),
    (30.0, 35.0),
    (35.0, 90.0),
)


@dataclass(frozen=True)
class FixtureResult:
    fixture_id: str
    bb_type: str
    park_id: str
    stand: str
    observed_distance_ft: float
    pred_distance_ft: float
    pred_hang_time_s: float
    err_distance_ft: float
    err_distance_pct: float
    pass_distance: bool


def _fixture_atmosphere(
    fixture: dict[str, Any], weather_by_game: dict[int, Any] | None
) -> Atmosphere:
    """Atmosphere for one fixture.

    With ``weather_by_game`` (``--weather`` mode), use the HR's *actual* game
    conditions via the same path the retrodiction labels use
    (``weather_to_atmosphere``; still-air fallback when a game has no row) — so
    the harness validates carry under real wind/temp instead of the no-wind/20C
    park default that decision [131] flagged as a +12 ft over-bias. Without it,
    the legacy park-default behaviour (CI / offline).
    """
    park_id = fixture["park_id"]
    if weather_by_game is None:
        return park_atmosphere(park_id)
    # Lazy import: keeps the default (offline/CI) path free of the ClickHouse +
    # retrodict coupling, and avoids a physics->retrodict layering import at load.
    from bullpen_training.battedball.parks.loader import load_park_geometry
    from bullpen_training.battedball.retrodict._atmospheres import (
        still_air_atmosphere,
        weather_to_atmosphere,
    )

    park = load_park_geometry(park_id)
    weather = weather_by_game.get(int(fixture.get("game_id", 0)))
    return (
        weather_to_atmosphere(weather, park) if weather is not None else still_air_atmosphere(park)
    )


def _evaluate_fixture(
    fixture: dict[str, Any], atmo: Atmosphere, calib: PhysicsCalibration
) -> FixtureResult:
    """Simulate one fixture under ``atmo`` + ``calib`` and score it against observed.

    Spin is recomputed from the fixture's EV/LA/spray via the calibrated
    launch-angle spin model (NOT the legacy flat prior baked into the fixture),
    and the calibrated global drag scale is applied — identical to the physics
    the retrodiction labels run (``retrodict/labels.py``).
    """
    launch_kwargs = dict(fixture["launch"])
    rate, tilt = batted_ball_spin(
        launch_kwargs["launch_speed_mph"],
        launch_kwargs["launch_angle_deg"],
        launch_kwargs["spray_angle_deg"],
        calib.spin,
    )
    launch_kwargs["spin_rate_rpm"] = float(rate)
    launch_kwargs["spin_axis_tilt_deg"] = float(tilt)
    launch = LaunchParams(**launch_kwargs)
    traj = simulate(launch, atmo, cd_scale=calib.cd_scale)
    pred_distance_ft = traj.distance_ft
    observed = float(fixture["observed_distance_ft"])
    err_ft = pred_distance_ft - observed
    err_pct = abs(err_ft) / observed if observed > 0 else 0.0
    passed = err_pct <= _DISTANCE_TOL_PCT or abs(err_ft) <= _DISTANCE_TOL_FT_ABS
    return FixtureResult(
        fixture_id=fixture["fixture_id"],
        bb_type=fixture["bb_type"],
        park_id=fixture["park_id"],
        stand=fixture["stand"],
        observed_distance_ft=observed,
        pred_distance_ft=round(pred_distance_ft, 2),
        pred_hang_time_s=round(traj.hang_time, 3),
        err_distance_ft=round(err_ft, 2),
        err_distance_pct=round(err_pct, 4),
        pass_distance=passed,
    )


def run_validation(
    fixtures_path: Path,
    *,
    use_weather: bool = False,
    container: str = "bullpen-clickhouse",
    calibration: PhysicsCalibration | None = None,
) -> dict[str, Any]:
    """Run the harness on a fixtures JSON, return the aggregate report.

    ``use_weather`` joins per-game ``weather_observed`` (the desktop re-baseline);
    requires fixtures carrying ``game_id`` + a populated weather table. Default
    False keeps the legacy no-wind park-default behaviour (CI / offline).

    ``calibration`` is the spin+drag physics calibration applied to every fixture;
    ``None`` loads the canonical committed artifact (``physics_calibration.json``)
    so the gate tracks the same physics as the labels.
    """
    data = json.loads(fixtures_path.read_text())
    fixtures: list[dict[str, Any]] = data["fixtures"]
    calib = calibration if calibration is not None else load_physics_calibration()

    weather_by_game: dict[int, Any] | None = None
    if use_weather:
        from bullpen_training.battedball.retrodict._atmospheres import load_weather_observed

        seasons = [int(fx["game_date"][:4]) for fx in fixtures if fx.get("game_date")]
        sf, st = (min(seasons), max(seasons)) if seasons else (2024, 2024)
        weather_by_game = load_weather_observed(sf, st, container=container)
        covered = sum(1 for fx in fixtures if int(fx.get("game_id", 0)) in weather_by_game)
        print(
            f"weather mode: {len(weather_by_game)} games loaded; "
            f"{covered}/{len(fixtures)} fixtures covered"
        )

    results = [
        _evaluate_fixture(fx, _fixture_atmosphere(fx, weather_by_game), calib) for fx in fixtures
    ]
    n = len(results)
    pass_rate = sum(r.pass_distance for r in results) / n if n else 0.0
    mae_ft = sum(abs(r.err_distance_ft) for r in results) / n if n else 0.0
    bias_ft = sum(r.err_distance_ft for r in results) / n if n else 0.0
    by_type: dict[str, dict[str, Any]] = {}
    for label in ("home_run", "fly_ball", "line_drive"):
        subset = [r for r in results if r.bb_type == label]
        if not subset:
            continue
        by_type[label] = {
            "n": len(subset),
            "pass_rate": round(sum(r.pass_distance for r in subset) / len(subset), 4),
            "mae_distance_ft": round(sum(abs(r.err_distance_ft) for r in subset) / len(subset), 2),
            "bias_distance_ft": round(sum(r.err_distance_ft for r in subset) / len(subset), 2),
        }
    # Per-launch-angle breakdown: surfaces the carry gradient (issue #24) that the
    # mean bias hides — informative, not gated. Buckets keyed off the fixture's LA.
    by_launch_angle: dict[str, dict[str, Any]] = {}
    for lo, hi in _LA_BUCKETS:
        subset = [
            r
            for r, fx in zip(results, fixtures, strict=True)
            if lo <= fx["launch"]["launch_angle_deg"] < hi
        ]
        if not subset:
            continue
        by_launch_angle[f"{lo:g}-{hi:g}"] = {
            "n": len(subset),
            "pass_rate": round(sum(r.pass_distance for r in subset) / len(subset), 4),
            "mae_distance_ft": round(sum(abs(r.err_distance_ft) for r in subset) / len(subset), 2),
            "bias_distance_ft": round(sum(r.err_distance_ft for r in subset) / len(subset), 2),
        }
    failures = [asdict(r) for r in results if not r.pass_distance]
    gate_ok = pass_rate >= _REPORT_PASS_RATE_GATE and mae_ft <= _REPORT_MAE_DISTANCE_FT_GATE
    return {
        "schema_version": 1,
        "n_fixtures": n,
        "weather_mode": use_weather,
        "calibration": calib.to_dict(),
        "pass_rate_distance": round(pass_rate, 4),
        "mae_distance_ft": round(mae_ft, 2),
        "bias_distance_ft": round(bias_ft, 2),
        "gate_pass_rate": _REPORT_PASS_RATE_GATE,
        "gate_mae_distance_ft": _REPORT_MAE_DISTANCE_FT_GATE,
        "gate_passes": gate_ok,
        "by_type": by_type,
        "by_launch_angle": by_launch_angle,
        "failures": failures,
        "results": [asdict(r) for r in results],
    }


def assert_gate(report: dict[str, Any]) -> None:
    """Raise SystemExit if the report fails the leaf's gate criteria."""
    if report["gate_passes"]:
        return
    pr = report["pass_rate_distance"]
    gpr = report["gate_pass_rate"]
    mae = report["mae_distance_ft"]
    gmae = report["gate_mae_distance_ft"]
    n_fail = len(report["failures"])
    msg = (
        f"physics validation FAILED: pass_rate={pr:.2%} (gate >= {gpr:.0%}), "
        f"mae={mae:.1f} ft (gate <= {gmae:.0f} ft); {n_fail} failures"
    )
    raise SystemExit(msg)


def _print_summary(
    report: dict[str, Any], failures: Iterable[dict[str, Any]] | None = None
) -> None:
    pr = report["pass_rate_distance"]
    gpr = report["gate_pass_rate"]
    mae = report["mae_distance_ft"]
    gmae = report["gate_mae_distance_ft"]
    print(f"physics validation @ n={report['n_fixtures']}")
    print(f"  pass_rate_distance: {pr:.2%} (gate >= {gpr:.0%})")
    print(f"  mae_distance_ft:    {mae:.2f}  (gate <= {gmae:.0f} ft)")
    print(f"  bias_distance_ft:   {report['bias_distance_ft']:+.2f}")
    print("  by bb_type:")
    for label, stats in report["by_type"].items():
        print(
            f"    {label:>10}  n={stats['n']:>3}  pass={stats['pass_rate']:.0%}  "
            f"mae={stats['mae_distance_ft']:.1f}  bias={stats['bias_distance_ft']:+.1f}"
        )
    if report.get("by_launch_angle"):
        print("  by launch angle (carry gradient — informational, not gated):")
        for label, stats in report["by_launch_angle"].items():
            print(
                f"    LA {label:>7}  n={stats['n']:>4}  pass={stats['pass_rate']:.0%}  "
                f"mae={stats['mae_distance_ft']:.1f}  bias={stats['bias_distance_ft']:+.1f}"
            )
    if failures is not None:
        worst = sorted(failures, key=lambda f: -abs(f["err_distance_ft"]))[:10]
        if worst:
            print("  worst 10 failures by |err_distance_ft|:")
            for f in worst:
                print(
                    f"    {f['fixture_id']:<10} {f['bb_type']:<10} {f['park_id']:>4} "
                    f"obs={f['observed_distance_ft']:>5.0f} pred={f['pred_distance_ft']:>6.1f} "
                    f"err={f['err_distance_ft']:+6.1f} ft ({f['err_distance_pct']:.1%})"
                )
    print(f"  gate_passes: {report['gate_passes']}")


def main() -> None:
    parser = argparse.ArgumentParser(description="Run the 2c.2 physics validation harness.")
    parser.add_argument(
        "--fixtures",
        type=Path,
        default=Path("training/data/physics_validation_fixtures.json"),
    )
    parser.add_argument(
        "--report",
        type=Path,
        default=Path("training/data/physics_validation_report.json"),
    )
    parser.add_argument(
        "--enforce-gate",
        action="store_true",
        help="Exit non-zero if the gate fails (use in CI; default: report and exit 0).",
    )
    parser.add_argument(
        "--weather",
        action="store_true",
        help=(
            "Score each fixture under its game's actual weather_observed conditions "
            "(removes the no-wind/20C +12 ft bias). Requires fixtures with game_id and a "
            "populated weather_observed table (desktop). Default: legacy park-default."
        ),
    )
    parser.add_argument("--container", default="bullpen-clickhouse")
    parser.add_argument(
        "--calibration",
        type=Path,
        default=None,
        help=(
            "Path to a physics_calibration.json (spin coeffs + cd_scale). "
            "Default: the canonical committed artifact via load_physics_calibration."
        ),
    )
    args = parser.parse_args()

    calibration = load_physics_calibration(args.calibration) if args.calibration else None
    report = run_validation(
        args.fixtures,
        use_weather=args.weather,
        container=args.container,
        calibration=calibration,
    )
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2))
    _print_summary(report, report["failures"])
    calib = report["calibration"]
    print(f"  calibration: cd_scale={calib['cd_scale']:.4f}  spin={calib['spin']}")
    print(f"wrote report -> {args.report}")
    if args.enforce_gate:
        assert_gate(report)


if __name__ == "__main__":
    main()


__all__ = ("FixtureResult", "assert_gate", "run_validation")
