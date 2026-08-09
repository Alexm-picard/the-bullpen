#!/usr/bin/env python3
"""Issue #24 diagnostic: LA-binned carry residuals on the FULL HR sample under real weather.

Purpose: confirm whether the launch-angle carry gradient (Magnus over-lift at
low LA, under-carry at high LA) seen in the 100-fixture local probe is real at
scale or a small-n tail artifact.

Run on the desktop (needs ClickHouse + weather_observed):

    cd ~/code/the-bullpen/training
    uv run python /path/to/issue_24_diagnostic.py --sample 6000

Or copy the script into training/scripts/ first:

    cp issue_24_diagnostic.py ~/code/the-bullpen/training/scripts/
    cd ~/code/the-bullpen/training
    uv run python scripts/issue_24_diagnostic.py --sample 6000

Output: a table of LA buckets with n, mean bias (pred - obs), MAE, and std,
plus the overall numbers. Paste the output into issue #24.

Rule 13: seasons are clamped to 2015-2025. 2026 is holdout-only.
"""

from __future__ import annotations

import argparse
import importlib
from pathlib import Path

import numpy as np
from bullpen_training.battedball.physics.simulator import LaunchParams, simulate_batch
from bullpen_training.battedball.physics.spin import (
    PHYSICS_PRIOR_COEFFS,
    PhysicsCalibration,
    batted_ball_spin,
    load_physics_calibration,
)
from bullpen_training.battedball.retrodict._atmospheres import load_weather_observed

_calibrate = importlib.import_module("scripts.calibrate_spin")
load_hr_sample = _calibrate.load_hr_sample
_atmospheres = _calibrate._atmospheres

_TRAINING_ROOT = Path(__file__).resolve().parents[1]
_DEFAULT_CALIB = _TRAINING_ROOT / "artifacts" / "physics_calibration.json"
_HOLDOUT_YEAR = 2026

# The LA buckets from the issue's original probe, extended for better coverage.
LA_BINS = [
    (15, 22),
    (22, 26),
    (26, 30),
    (30, 35),
    (35, 50),
]


def main() -> None:
    ap = argparse.ArgumentParser(description="Issue #24: LA-binned carry residuals at scale.")
    ap.add_argument("--season-from", type=int, default=2015)
    ap.add_argument("--season-to", type=int, default=2025)
    ap.add_argument("--sample", type=int, default=6000)
    ap.add_argument("--container", default="bullpen-clickhouse")
    ap.add_argument(
        "--calibration",
        type=Path,
        default=_DEFAULT_CALIB,
        help="Physics calibration JSON (cd_scale + spin coefficients).",
    )
    args = ap.parse_args()

    st = min(args.season_to, _HOLDOUT_YEAR - 1)
    print(f"Loading HR sample ({args.season_from}-{st}, target {args.sample}) ...")
    data = load_hr_sample(sf=args.season_from, st=st, sample=args.sample, container=args.container)
    n = len(data["ev"])
    print(f"Loaded {n} HRs.")

    print("Loading weather ...")
    weather_by_game = load_weather_observed(args.season_from, st, container=args.container)
    atmos = _atmospheres(data, weather_by_game)

    # Load the fitted calibration (cd_scale + spin coefficients).
    if args.calibration.exists():
        calib = load_physics_calibration(args.calibration)
        print(f"Using calibration from {args.calibration} (cd_scale={calib.cd_scale:.4f})")
    else:
        calib = PhysicsCalibration(spin=PHYSICS_PRIOR_COEFFS, cd_scale=1.0)
        print("No calibration file found, using cd_scale=1.0 + physics-prior spin.")

    print(f"Simulating {n} trajectories under real weather ...")
    rate, tilt = batted_ball_spin(data["ev"], data["la"], data["spray"], calib.spin)
    rate, tilt = np.asarray(rate), np.asarray(tilt)
    launches = [
        LaunchParams(
            launch_speed_mph=float(data["ev"][i]),
            launch_angle_deg=float(data["la"][i]),
            spray_angle_deg=float(data["spray"][i]),
            spin_rate_rpm=float(rate[i]),
            spin_axis_tilt_deg=float(tilt[i]),
        )
        for i in range(n)
    ]
    trajs = simulate_batch(launches, atmos, cd_scale=calib.cd_scale)
    pred = np.array([t.distance_ft if t.landed else np.nan for t in trajs])
    residuals = pred - data["obs"]
    la = data["la"]

    # Print the binned table.
    print()
    print("=" * 72)
    print("  Issue #24 LA-binned carry residuals (pred - obs, ft)")
    print(f"  Sample: {n} HRs, seasons {args.season_from}-{st}, real weather")
    print(f"  cd_scale={calib.cd_scale:.4f}")
    print("=" * 72)
    print(f"{'LA bucket':>12}  {'n':>6}  {'bias':>10}  {'MAE':>10}  {'std':>10}")
    print("-" * 56)

    total_valid = 0
    for lo, hi in LA_BINS:
        mask = (la >= lo) & (la < hi) & np.isfinite(residuals)
        bucket_n = int(mask.sum())
        if bucket_n == 0:
            print(f"  {lo:2d}-{hi:2d} deg    {'---':>6}  {'---':>10}  {'---':>10}  {'---':>10}")
            continue
        r = residuals[mask]
        bias = float(np.mean(r))
        mae = float(np.mean(np.abs(r)))
        std = float(np.std(r))
        total_valid += bucket_n
        print(f"  {lo:2d}-{hi:2d} deg    {bucket_n:6d}  {bias:+10.2f}  {mae:10.2f}  {std:10.2f}")

    print("-" * 56)
    valid = np.isfinite(residuals)
    overall_n = int(valid.sum())
    overall_bias = float(np.mean(residuals[valid]))
    overall_mae = float(np.mean(np.abs(residuals[valid])))
    overall_std = float(np.std(residuals[valid]))
    not_landed = int((~np.isfinite(pred)).sum())
    print(
        f"  {'Overall':>10}  {overall_n:6d}  {overall_bias:+10.2f}"
        f"  {overall_mae:10.2f}  {overall_std:10.2f}"
    )
    if not_landed > 0:
        print(f"  ({not_landed} trajectories did not land - excluded)")
    print()

    # Decision guidance from the issue:
    gradient = None
    for lo, hi in LA_BINS:
        mask = (la >= lo) & (la < hi) & np.isfinite(residuals)
        if mask.sum() >= 10:
            if gradient is None:
                gradient = []
            gradient.append((f"{lo}-{hi}", float(np.mean(residuals[mask]))))

    if gradient and len(gradient) >= 3:
        first_bias = gradient[0][1]
        last_bias = gradient[-1][1]
        swing = first_bias - last_bias
        print(f"Gradient swing (lowest LA bin - highest LA bin): {swing:+.1f} ft")
        if abs(swing) > 20:
            print("-> REAL AT SCALE: model-structure research item per issue #24.")
            print("   Candidates: spin decay, lift-coefficient saturation, or")
            print("   Statcast extrapolated hit_distance_ft vs full-carry.")
        else:
            print("-> SOFTENED AT SCALE: no action needed beyond mean-corrected calibration.")
    print()


if __name__ == "__main__":
    main()
