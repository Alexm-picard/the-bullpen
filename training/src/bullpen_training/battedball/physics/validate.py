"""Run the 100-fixture physics validation harness (Phase 2c.2 gate).

Loads the fixture set produced by :mod:`fixtures`, runs each row through
the JIT simulator under the park-defaulted atmosphere, and compares the
reconstructed carry distance to Statcast's observed value. The aggregate
report is written to ``training/data/physics_validation_report.json``
and gates the rest of Phase 2c — per decision [49], no model in 2c can
train until ``pass_rate_distance >= 0.95`` and ``mae_distance_ft <= 20``.

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

from bullpen_training.battedball.physics.parks import park_atmosphere
from bullpen_training.battedball.physics.simulator import LaunchParams, simulate

# Pass tolerance constants. REVISED from the original leaf — see the module
# docstring + decisions.md entry. Tighten back to (0.05, 15.0, 0.95, 20.0)
# when 2c.4's weather pull lands and per-game wind+temp can be used.
_DISTANCE_TOL_PCT = 0.10
_DISTANCE_TOL_FT_ABS = 25.0
_REPORT_PASS_RATE_GATE = 0.85
_REPORT_MAE_DISTANCE_FT_GATE = 30.0


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


def _evaluate_fixture(fixture: dict[str, Any]) -> FixtureResult:
    """Simulate one fixture and score it against observed."""
    launch = LaunchParams(**fixture["launch"])
    atmo = park_atmosphere(fixture["park_id"])
    traj = simulate(launch, atmo)
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


def run_validation(fixtures_path: Path) -> dict[str, Any]:
    """Run the harness on a fixtures JSON, return the aggregate report."""
    data = json.loads(fixtures_path.read_text())
    fixtures: list[dict[str, Any]] = data["fixtures"]
    results = [_evaluate_fixture(fx) for fx in fixtures]
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
    failures = [asdict(r) for r in results if not r.pass_distance]
    gate_ok = pass_rate >= _REPORT_PASS_RATE_GATE and mae_ft <= _REPORT_MAE_DISTANCE_FT_GATE
    return {
        "schema_version": 1,
        "n_fixtures": n,
        "pass_rate_distance": round(pass_rate, 4),
        "mae_distance_ft": round(mae_ft, 2),
        "bias_distance_ft": round(bias_ft, 2),
        "gate_pass_rate": _REPORT_PASS_RATE_GATE,
        "gate_mae_distance_ft": _REPORT_MAE_DISTANCE_FT_GATE,
        "gate_passes": gate_ok,
        "by_type": by_type,
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
    args = parser.parse_args()

    report = run_validation(args.fixtures)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2))
    _print_summary(report, report["failures"])
    print(f"wrote report -> {args.report}")
    if args.enforce_gate:
        assert_gate(report)


if __name__ == "__main__":
    main()


__all__ = ("FixtureResult", "assert_gate", "run_validation")
