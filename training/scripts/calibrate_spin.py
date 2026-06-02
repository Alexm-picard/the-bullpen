"""Calibrate the batted-ball spin model to weather-corrected Statcast carry.

Phase 1 of the physics overhaul. We can't observe batted-ball spin, so we fit
the spin model's coefficients (``physics/spin.py``) so that SIMULATED HR carry
matches OBSERVED carry under each game's ACTUAL weather — the non-circular
"both" path: physics-derived form, Statcast-calibrated coefficients.

Procedure:
  1. Pull a HR sample (EV, LA, spray, observed carry, game) from ``pitches``,
     season-bounded 2015-2025 (2026 holdout, rule 13), HR carry range 350-500 ft
     (same as the validation fixtures), EXCLUDING the 100 gate-fixture games so
     the gate stays a held-out test.
  2. Join per-game ``weather_observed`` -> per-HR atmosphere (still-air fallback),
     the same path retrodiction + the Phase-0 harness use.
  3. Fit ONLY the global drag scale cd_scale (1-D, minimize_scalar) to minimise
     mean |sim_carry - observed|, with spin FIXED at PHYSICS_PRIOR_COEFFS. HR
     carry can't identify spin separately from drag (both scale carry; two prior
     joint fits collapsed spin to its clamp), so spin is set from physics and
     drag is the single identifiable, Statcast-calibrated knob.
  4. Write the fitted PhysicsCalibration (physics-prior spin + fitted cd_scale)
     to ``--out`` (default training/artifacts/physics_calibration.json).

Desktop-only (needs ``weather_observed``). Author on the Mac (ADR-0006), run:

    uv run python scripts/calibrate_spin.py --sample 4000 --out artifacts/physics_calibration.json

Then re-run the Phase-0 gate with --weather and wire load_physics_calibration
into the fixtures + retrodiction, then re-retrodict (wiring step, after this).
"""

from __future__ import annotations

import argparse
import json
import subprocess
from pathlib import Path

import numpy as np
from scipy.optimize import minimize_scalar

from bullpen_training.battedball.features_shared import hc_to_spray_deg
from bullpen_training.battedball.parks.loader import load_park_geometry
from bullpen_training.battedball.physics.simulator import LaunchParams, simulate_batch
from bullpen_training.battedball.physics.spin import (
    PHYSICS_PRIOR_COEFFS,
    PhysicsCalibration,
    SpinCoeffs,
    batted_ball_spin,
)
from bullpen_training.battedball.retrodict._atmospheres import (
    load_weather_observed,
    still_air_atmosphere,
    weather_to_atmosphere,
)

_TRAINING_ROOT = Path(__file__).resolve().parents[1]
_DEFAULT_OUT = _TRAINING_ROOT / "artifacts" / "physics_calibration.json"
_FIXTURES = _TRAINING_ROOT / "data" / "physics_validation_fixtures.json"
_HOLDOUT_YEAR = 2026


def _run_ch(query: str, *, container: str) -> list[list[str]]:
    out = subprocess.run(
        ["docker", "exec", container, "clickhouse-client", "--query", query],
        check=True,
        capture_output=True,
        text=True,
    )
    return [ln.split("\t") for ln in out.stdout.strip().split("\n") if ln]


def _fixture_game_ids() -> set[int]:
    if not _FIXTURES.exists():
        return set()
    return {int(fx.get("game_id", 0)) for fx in json.loads(_FIXTURES.read_text())["fixtures"]}


def load_hr_sample(*, sf: int, st: int, sample: int, container: str) -> dict[str, np.ndarray]:
    """HR sample for calibration: arrays of ev, la, spray, observed, game_id, park_id."""
    rows = _run_ch(
        "SELECT toString(game_id), toString(launch_speed_mph), toString(launch_angle_deg), "
        "toString(hc_x), toString(hc_y), toString(hit_distance_ft), park_id "
        "FROM pitches "
        "WHERE events='home_run' AND description='in_play' "
        f"AND toYear(game_date) BETWEEN {sf} AND {st} "
        "AND launch_speed_mph IS NOT NULL AND launch_angle_deg IS NOT NULL "
        "AND hc_x IS NOT NULL AND hc_y IS NOT NULL AND hit_distance_ft IS NOT NULL "
        "AND hit_distance_ft BETWEEN 350 AND 500 "
        f"ORDER BY cityHash64(game_id, at_bat_index, pitch_number) LIMIT {sample * 2} FORMAT TSV",
        container=container,
    )
    held_out = _fixture_game_ids()
    ev, la, spray, obs, gid, pid = [], [], [], [], [], []
    for r in rows:
        g = int(r[0])
        if g in held_out:
            continue
        ev.append(float(r[1]))
        la.append(float(r[2]))
        spray.append(hc_to_spray_deg(float(r[3]), float(r[4])))
        obs.append(float(r[5]))
        gid.append(g)
        pid.append(r[6])
        if len(ev) >= sample:
            break
    return {
        "ev": np.array(ev),
        "la": np.array(la),
        "spray": np.array(spray),
        "obs": np.array(obs),
        "game_id": np.array(gid, dtype=np.int64),
        "park_id": np.array(pid),
    }


def _atmospheres(data: dict[str, np.ndarray], weather_by_game: dict) -> list:
    out = []
    for g, p in zip(data["game_id"], data["park_id"], strict=True):
        park = load_park_geometry(str(p))
        w = weather_by_game.get(int(g))
        out.append(weather_to_atmosphere(w, park) if w is not None else still_air_atmosphere(park))
    return out


def _carry_mae_bias(
    data: dict[str, np.ndarray], atmos: list, coeffs: SpinCoeffs, cd_scale: float
) -> tuple[float, float]:
    rate, tilt = batted_ball_spin(data["ev"], data["la"], data["spray"], coeffs)
    rate, tilt = np.asarray(rate), np.asarray(tilt)
    launches = [
        LaunchParams(
            launch_speed_mph=float(data["ev"][i]),
            launch_angle_deg=float(data["la"][i]),
            spray_angle_deg=float(data["spray"][i]),
            spin_rate_rpm=float(rate[i]),
            spin_axis_tilt_deg=float(tilt[i]),
        )
        for i in range(len(data["ev"]))
    ]
    trajs = simulate_batch(launches, atmos, cd_scale=cd_scale)
    pred = np.array([t.distance_ft if t.landed else np.nan for t in trajs])
    err = pred - data["obs"]
    err = err[np.isfinite(err)]
    return float(np.mean(np.abs(err))), float(np.mean(err))


def main() -> None:
    ap = argparse.ArgumentParser(description="Calibrate the batted-ball spin model (Phase 1).")
    ap.add_argument("--season-from", type=int, default=2015)
    ap.add_argument("--season-to", type=int, default=2025)
    ap.add_argument("--sample", type=int, default=4000, help="HR sample size for the fit.")
    ap.add_argument("--out", type=Path, default=_DEFAULT_OUT)
    ap.add_argument("--container", default="bullpen-clickhouse")
    args = ap.parse_args()

    st = args.season_to
    if st >= _HOLDOUT_YEAR:
        print(f"WARNING: clamping season-to {st} -> 2025 (rule 13).")
        st = _HOLDOUT_YEAR - 1

    print(f"loading HR sample ({args.season_from}-{st}, target {args.sample}) ...")
    data = load_hr_sample(sf=args.season_from, st=st, sample=args.sample, container=args.container)
    weather_by_game = load_weather_observed(args.season_from, st, container=args.container)
    atmos = _atmospheres(data, weather_by_game)
    print(f"calibrating on {len(data['ev'])} HRs under real weather ...")

    # Spin is FIXED at the physics prior — HR carry can't identify spin separately
    # from drag (both scale carry; two prior fits collapsed spin to its clamp).
    # We calibrate ONLY the global drag scale cd_scale (the single identifiable
    # carry knob); the physical spin supplies the EV/LA/spray shape.
    spin = PHYSICS_PRIOR_COEFFS
    base_mae, base_bias = _carry_mae_bias(data, atmos, spin, 1.0)
    print(
        f"  baseline (physics-prior spin, cd=1.00): MAE={base_mae:.2f} ft  bias={base_bias:+.2f} ft"
    )

    def loss(cd: float) -> float:
        mae, _ = _carry_mae_bias(data, atmos, spin, cd)
        return mae

    res = minimize_scalar(loss, bounds=(0.85, 1.20), method="bounded")
    cd_scale = float(res.x)  # type: ignore[attr-defined]  # OptimizeResult stubbed as object
    fit_mae, fit_bias = _carry_mae_bias(data, atmos, spin, cd_scale)
    calib = PhysicsCalibration(spin=spin, cd_scale=cd_scale)
    print(
        f"  fitted (cd_scale only):                 MAE={fit_mae:.2f} ft  bias={fit_bias:+.2f} ft"
    )
    print(f"  cd_scale={cd_scale:.4f}  spin(fixed)={spin.to_dict()}")

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(calib.to_dict(), indent=2) + "\n")
    print(f"wrote {args.out}")
    print(
        "Next: re-run `validate.py --weather` (gate) and wire load_physics_calibration "
        "into the fixtures + retrodiction, then re-retrodict."
    )


if __name__ == "__main__":
    main()
