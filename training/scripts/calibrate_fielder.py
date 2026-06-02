"""Re-tune the HR-margin fielder threshold to the observed HR rate (D5).

Decision [132] set ``hr_min_dist_past_fence_ft = 45`` (a ball must clear the
fence by >=45 ft to be called a HR) to hold the league HR rate at ~4.2% — but
that margin was inflated to offset the **no-wind over-carry** the simulator had
at the time. Phase 1 removed that over-carry (physical spin + calibrated
cd_scale), so the +45 ft margin is now over-conservative: it caps short-porch
parks (NYY/PHI stalled mid-pack even after the empirical fences went in).

This sweeps ``hr_min_dist_past_fence_ft`` on the CURRENT physics (physics-prior
spin + cd_scale, from the committed calibration) + whatever geometry the loader
resolves (set BULLPEN_PARK_GEOMETRY_DIR to the empirical fences to match the
retrodiction) and finds the margin whose predicted HR rate matches the observed
rate in the sample. Each BIP is simulated ONCE; only the classification is
re-run per margin, so the sweep is cheap.

It does NOT edit code — it prints the recommended margin; we then set
DEFAULT_HR_MIN_DIST_PAST_FENCE_FT in parks/_classify.py (which _fused imports),
commit, and re-retrodict. Desktop-only (weather); season-bounded excl 2026.

    export BULLPEN_PARK_GEOMETRY_DIR=~/code/the-bullpen/infra/park_geometry_estimated
    uv run python scripts/calibrate_fielder.py --sample 8000
"""

from __future__ import annotations

import argparse
import subprocess

import numpy as np

from bullpen_training.battedball.features_shared import hc_to_spray_deg
from bullpen_training.battedball.parks import Outcome, classify_outcome, load_park_geometry
from bullpen_training.battedball.physics.simulator import LaunchParams, simulate
from bullpen_training.battedball.physics.spin import batted_ball_spin, load_physics_calibration
from bullpen_training.battedball.retrodict._atmospheres import (
    load_weather_observed,
    still_air_atmosphere,
    weather_to_atmosphere,
)

_HOLDOUT_YEAR = 2026


def _run_ch(query: str, *, container: str) -> list[list[str]]:
    out = subprocess.run(
        ["docker", "exec", container, "clickhouse-client", "--query", query],
        check=True,
        capture_output=True,
        text=True,
    )
    return [ln.split("\t") for ln in out.stdout.strip().split("\n") if ln]


def load_home_bips(*, sf: int, st: int, sample: int, container: str) -> list[dict]:
    """A sample of in-play BIPs at their HOME park (the observed-outcome park)."""
    rows = _run_ch(
        "SELECT toString(game_id), toString(launch_speed_mph), toString(launch_angle_deg), "
        "toString(hc_x), toString(hc_y), park_id, events "
        "FROM pitches "
        "WHERE description='in_play' "
        f"AND toYear(game_date) BETWEEN {sf} AND {st} "
        "AND launch_speed_mph IS NOT NULL AND launch_angle_deg IS NOT NULL "
        "AND hc_x IS NOT NULL AND hc_y IS NOT NULL "
        f"ORDER BY cityHash64(game_id, at_bat_index, pitch_number) LIMIT {sample} FORMAT TSV",
        container=container,
    )
    out = []
    for r in rows:
        out.append(
            {
                "game_id": int(r[0]),
                "ev": float(r[1]),
                "la": float(r[2]),
                "spray": hc_to_spray_deg(float(r[3]), float(r[4])),
                "park_id": r[5],
                "is_hr": r[6] == "home_run",
            }
        )
    return out


def main() -> None:
    ap = argparse.ArgumentParser(description="Re-tune the HR-margin fielder threshold (D5).")
    ap.add_argument("--season-from", type=int, default=2015)
    ap.add_argument("--season-to", type=int, default=2025)
    ap.add_argument("--sample", type=int, default=8000)
    ap.add_argument("--container", default="bullpen-clickhouse")
    args = ap.parse_args()

    st = min(args.season_to, _HOLDOUT_YEAR - 1)
    calib = load_physics_calibration()
    bips = load_home_bips(sf=args.season_from, st=st, sample=args.sample, container=args.container)
    weather_by_game = load_weather_observed(args.season_from, st, container=args.container)
    geom_dir = load_park_geometry("NYY")  # touch the loader so the override (if any) is logged
    print(f"loaded {len(bips)} BIPs; spin/cd from calibration (cd_scale={calib.cd_scale:.4f})")
    print(
        f"geometry: NYY polyline has {len(geom_dir.fence_polyline)} points "
        "(19 => empirical override active)"
    )

    observed_rate = float(np.mean([b["is_hr"] for b in bips]))

    # Simulate each BIP ONCE under the current physics; cache the trajectory so
    # the margin sweep only re-classifies.
    cached: list[tuple] = []  # (trajectory, home_park_geometry)
    rate, tilt = batted_ball_spin(
        np.array([b["ev"] for b in bips]),
        np.array([b["la"] for b in bips]),
        np.array([b["spray"] for b in bips]),
        calib.spin,
    )
    rate, tilt = np.asarray(rate), np.asarray(tilt)
    for i, b in enumerate(bips):
        park = load_park_geometry(b["park_id"])
        w = weather_by_game.get(b["game_id"])
        atmo = weather_to_atmosphere(w, park) if w is not None else still_air_atmosphere(park)
        launch = LaunchParams(
            launch_speed_mph=b["ev"],
            launch_angle_deg=b["la"],
            spray_angle_deg=b["spray"],
            spin_rate_rpm=float(rate[i]),
            spin_axis_tilt_deg=float(tilt[i]),
        )
        traj = simulate(launch, atmo, cd_scale=calib.cd_scale)
        cached.append((traj, park))

    print(f"\nobserved HR rate in sample: {observed_rate:.4%}")
    print(f"{'hr_min_dist_ft':>14}  {'pred HR rate':>12}")
    best_m, best_gap = 45.0, 1e9
    for m in np.arange(15.0, 50.1, 2.5):
        n_hr = sum(
            classify_outcome(t, p, hr_min_dist_past_fence_ft=float(m)) == Outcome.HOME_RUN
            for t, p in cached
        )
        pred = n_hr / len(cached)
        if abs(pred - observed_rate) < best_gap:
            best_gap, best_m = abs(pred - observed_rate), float(m)
        print(f"{m:>14.1f}  {pred:>12.4%}")
    print(
        f"\n-> recommended hr_min_dist_past_fence_ft ~= {best_m:.1f} "
        f"(matches observed {observed_rate:.3%}); [132] used 45.0.\n"
        "   Set DEFAULT_HR_MIN_DIST_PAST_FENCE_FT in parks/_classify.py, then re-retrodict."
    )


if __name__ == "__main__":
    main()
