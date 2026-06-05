"""Re-tune the fielder HR margins (distance AND height) — D5, 2D sweep.

The classifier calls a fly a HR only when it clears the fence by margin in
**both** dimensions (``_classify.classify_outcome``)::

    z_at_fence > fence_h + hr_min_height_over_fence_ft   AND
    landing    > fence_d + hr_min_dist_past_fence_ft

Decision [132] set those to (dist=45, height=25), tuned to hold the league HR
rate at ~4.2% — but both were inflated to offset the simulator's **no-wind
over-carry** ([131]'s +13/+21 ft bias). Phase 1 removed that over-carry
(physical batted-ball spin + calibrated cd_scale + real per-game weather), so
the margins are now over-conservative. Crucially they are over-conservative
**asymmetrically**: the 25 ft *height* gate rejects low line-drive HRs over the
short porches (a liner clearing NYY's ~8 ft RF wall at 20 ft of height is a real
HR but fails ``20 > 8+25``), which is exactly the per-park expressiveness the
cross-park gate [52] needs. A distance-only re-tune leaves the porches capped.

This sweeps a 2-D grid of (dist_margin, height_margin) on the CURRENT physics
(physics-prior spin + cd_scale from the committed calibration) + whatever
geometry the loader resolves (set BULLPEN_PARK_GEOMETRY_DIR to the empirical
fences to match the retrodiction). Each home BIP is simulated ONCE at its home
park; the grid only re-classifies, so the whole sweep is cheap. Per cell it
reports:

  - **global** predicted HR rate (the calibration constraint: match the sample's
    observed rate), and
  - **per-park rho** — Spearman of per-park *predicted* home HR rate vs per-park
    *observed* home HR rate (a cheap in-sample proxy for "does this fielder
    shape make parks produce the right HR counts"; the gate's roster-stripped
    counterfactual-vs-observed_norm is confirmed later by compare_park_factors).

It recommends the (dist, height) that **maximises per-park rho subject to the
global rate matching observed** — i.e. the most reality-faithful fielder shape on
the realistic iso-rate curve. We then set DEFAULT_HR_MIN_DIST_PAST_FENCE_FT and
DEFAULT_HR_MIN_HEIGHT_OVER_FENCE_FT in parks/_classify.py (which _fused imports),
commit, and re-retrodict. Desktop-only (weather); season-bounded excl 2026.

    export BULLPEN_PARK_GEOMETRY_DIR=~/code/the-bullpen/infra/park_geometry_estimated
    uv run python scripts/calibrate_fielder.py --sample 30000
"""

from __future__ import annotations

import argparse
import subprocess
from collections import defaultdict

import numpy as np
from scipy.stats import spearmanr  # type: ignore[import-untyped]

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

# Grid: 0 ft = "any ball over the wall is a HR" (the ~12% bare-physics rate); the
# global-rate constraint excludes the degenerate low corners, leaving the
# realistic iso-rate curve where per-park rho discriminates the shape.
_DIST_GRID = np.arange(0.0, 35.1, 5.0)
_HEIGHT_GRID = np.arange(0.0, 35.1, 5.0)
# A cell is "rate-feasible" if its global predicted HR rate is within this of the
# sample's observed rate; we pick max-rho among feasible cells.
_RATE_TOL = 0.004  # 0.4 percentage points


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


def _per_park_rho(
    cached: list[tuple], is_hr: np.ndarray, park_ids: list[str], d: float, h: float
) -> tuple[float, float]:
    """(global predicted HR rate, Spearman of per-park predicted vs observed)."""
    pred = np.array(
        [
            classify_outcome(
                traj,
                park,
                hr_min_dist_past_fence_ft=d,
                hr_min_height_over_fence_ft=h,
            )
            == Outcome.HOME_RUN
            for traj, park in cached
        ]
    )
    # Group both predicted and observed by home park; rho over the parks present.
    pred_by_park: dict[str, list[float]] = defaultdict(list)
    obs_by_park: dict[str, list[float]] = defaultdict(list)
    for pid, p, o in zip(park_ids, pred, is_hr, strict=True):
        pred_by_park[pid].append(float(p))
        obs_by_park[pid].append(float(o))
    parks = sorted(pred_by_park)
    pred_rate = [float(np.mean(pred_by_park[k])) for k in parks]
    obs_rate = [float(np.mean(obs_by_park[k])) for k in parks]
    rho = (
        float(spearmanr(pred_rate, obs_rate).statistic)  # type: ignore[attr-defined]
        if len(parks) >= 3
        else float("nan")
    )
    return float(pred.mean()), rho


def main() -> None:
    ap = argparse.ArgumentParser(description="Re-tune the fielder HR margins, 2-D (D5).")
    ap.add_argument("--season-from", type=int, default=2015)
    ap.add_argument("--season-to", type=int, default=2025)
    ap.add_argument("--sample", type=int, default=30000)
    ap.add_argument("--container", default="bullpen-clickhouse")
    args = ap.parse_args()

    st = min(args.season_to, _HOLDOUT_YEAR - 1)
    calib = load_physics_calibration()
    bips = load_home_bips(sf=args.season_from, st=st, sample=args.sample, container=args.container)
    weather_by_game = load_weather_observed(args.season_from, st, container=args.container)
    nyy = load_park_geometry("NYY")  # touch the loader so the override (if any) is logged
    print(f"loaded {len(bips)} BIPs; spin/cd from calibration (cd_scale={calib.cd_scale:.4f})")
    print(
        f"geometry: NYY polyline has {len(nyy.fence_polyline)} points "
        "(19 => empirical override active)"
    )

    is_hr = np.array([b["is_hr"] for b in bips], dtype=bool)
    park_ids = [b["park_id"] for b in bips]
    observed_global = float(is_hr.mean())

    # Simulate each BIP ONCE under the current physics; cache the trajectory so
    # the (dist, height) grid only re-classifies.
    rate, tilt = batted_ball_spin(
        np.array([b["ev"] for b in bips]),
        np.array([b["la"] for b in bips]),
        np.array([b["spray"] for b in bips]),
        calib.spin,
    )
    rate, tilt = np.asarray(rate), np.asarray(tilt)
    cached: list[tuple] = []  # (trajectory, home_park_geometry)
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
        cached.append((simulate(launch, atmo, cd_scale=calib.cd_scale), park))

    print(f"\nobserved global HR rate in sample: {observed_global:.4%}")
    print("2-D fielder margin sweep — cell = predicted global HR rate (per-park rho):")
    print("(* marks rate-feasible cells within ±0.4pp of observed; pick max rho among them)\n")

    header = "  dist\\height " + "".join(f"{h:>13.0f}" for h in _HEIGHT_GRID)
    print(header)
    best = None  # (rho, dist, height, global_rate)
    for d in _DIST_GRID:
        cells = []
        for h in _HEIGHT_GRID:
            g, rho = _per_park_rho(cached, is_hr, park_ids, float(d), float(h))
            feasible = abs(g - observed_global) <= _RATE_TOL
            mark = "*" if feasible else " "
            cells.append(f"{g:>6.2%}/{rho:+.2f}{mark}")
            if feasible and (best is None or rho > best[0]):
                best = (rho, float(d), float(h), g)
        print(f"  {d:>5.0f}      " + "".join(f"{c:>13}" for c in cells))

    if best is None:
        # No cell hit the rate band — widen tolerance to just report the closest.
        all_cells = [
            (
                abs(
                    _per_park_rho(cached, is_hr, park_ids, float(d), float(h))[0] - observed_global
                ),
                d,
                h,
            )
            for d in _DIST_GRID
            for h in _HEIGHT_GRID
        ]
        _, d0, h0 = min(all_cells)
        print(
            f"\n-> no cell within ±{_RATE_TOL:.1%} of observed {observed_global:.2%}; "
            f"closest global-rate cell is (dist={d0:.0f}, height={h0:.0f}). "
            "Widen the grid or sample more."
        )
        return

    rho, d_best, h_best, g_best = best
    print(
        f"\n-> recommended (dist={d_best:.0f}, height={h_best:.0f}) ft: "
        f"global {g_best:.2%} (obs {observed_global:.2%}), per-park rho {rho:+.3f}.\n"
        f"   [132] used (45, 25). Set DEFAULT_HR_MIN_DIST_PAST_FENCE_FT={d_best:.1f} and "
        f"DEFAULT_HR_MIN_HEIGHT_OVER_FENCE_FT={h_best:.1f} in parks/_classify.py, then "
        "re-retrodict. (Per-park rho here is the in-sample home-rate proxy; "
        "compare_park_factors confirms on the roster-stripped counterfactual.)"
    )


if __name__ == "__main__":
    main()
