"""Empirically estimate high-resolution outfield fence geometry from Statcast.

The fixed 5-point park polylines (-45/-22.5/0/+22.5/+45) render quirky fences too
coarsely: NYY's RF porch is ~314 ft at the foul pole but ramps linearly to 385 ft
by -22.5, so the simulator reads ~345 ft across the porch when the real wall stays
~320 — suppressing short-porch HRs. The 2c.7 diagnostic confirmed the under-ranked
bandboxes (CIN/NYY/MIL/PHI) are exactly this geometry miss.

Rather than hand-type higher-res dimensions (injecting recall error into the very
signal we validate), we estimate the fence DISTANCE per spray-angle bin from the
data itself, exploiting decision [131]'s observation that **Statcast's
``hit_distance_ft`` for non-HRs is the first-contact location** — a ball that hits
the wall lands in the data *at the wall distance*. So the deep tail of non-HR
fly-ball distances in a spray bin marks the wall.

Estimator (per park, per spray bin):
  fence_dist = high percentile (default 90th) of ``hit_distance_ft`` over non-HR
  FLY balls (launch_angle in [15,45], EV >= 90) in the bin — the wall-bangers
  (doubles off the wall + flies robbed at the wall) cluster there.
Guards (a bad estimate is worse than the honest 5-point):
  - min sample per bin, else fall back to the documented polyline (interpolated);
  - clamp to documented +/- tolerance and to an absolute [300,440] ft sanity band;
  - HEIGHTS are not recoverable from landing data -> overlay the documented height
    profile (interpolated onto the new bin angles), preserving Monster/porch walls.

Output: high-resolution ``fence_polyline`` JSONs written to a STAGING dir
(``infra/park_geometry_estimated/`` by default) so the 2c.7 measurement tool can
confirm per-park residuals improved BEFORE ``--apply`` overwrites prod geometry.
Season-bounded to 2015-2025 (2026 holdout, rule 13).

Read-only on the data; author on the Mac (ADR-0006), run on the desktop from
training/:

    uv run python scripts/estimate_park_fences.py                 # -> staging dir
    uv run python scripts/estimate_park_fences.py --park NYY --dry-run   # inspect one park
    uv run python scripts/estimate_park_fences.py --apply         # overwrite prod (after review)
"""

from __future__ import annotations

import argparse
import json
import subprocess
from pathlib import Path

import numpy as np

_TRAINING_ROOT = Path(__file__).resolve().parents[1]
_GEOM_DIR = _TRAINING_ROOT.parents[0] / "infra" / "park_geometry"
_STAGING_DIR = _TRAINING_ROOT.parents[0] / "infra" / "park_geometry_estimated"
_HOLDOUT_YEAR = 2026

# Estimation knobs.
_BIN_WIDTH_DEG = 5.0  # -45..+45 -> 19 points
_MIN_BIN_SAMPLES = 40
_PERCENTILE = 90.0
_ABS_MIN_FT, _ABS_MAX_FT = 300.0, 440.0
_DOC_TOLERANCE_FT = 45.0  # reject estimates more than this from the documented fence


def _run_ch(query: str, *, container: str = "bullpen-clickhouse") -> str:
    res = subprocess.run(
        ["docker", "exec", container, "clickhouse-client", "--query", query],
        check=True,
        capture_output=True,
        text=True,
    )
    return res.stdout


def _spray_deg(hc_x: float, hc_y: float) -> float:
    """Match features_shared.hc_to_spray_deg (+ = 3B/LF)."""
    import math

    return math.degrees(math.atan2(125.42 - hc_x, 198.27 - hc_y))


def _doc_interp(points: list[dict[str, float]], angle: float, key: str) -> float:
    """Linear-interpolate a documented polyline value (distance/height) at an angle."""
    pts = sorted(points, key=lambda p: p["angle_from_centerline_deg"])
    angs = [p["angle_from_centerline_deg"] for p in pts]
    if angle <= angs[0]:
        return float(pts[0][key])
    if angle >= angs[-1]:
        return float(pts[-1][key])
    for i in range(1, len(pts)):
        if angle <= angs[i]:
            a0, a1 = angs[i - 1], angs[i]
            v0, v1 = float(pts[i - 1][key]), float(pts[i][key])
            return v0 + (angle - a0) / (a1 - a0) * (v1 - v0)
    return float(pts[-1][key])


def estimate_fence_distances(
    spray: np.ndarray,
    hit_distance: np.ndarray,
    is_hr: np.ndarray,
    *,
    bin_centers: np.ndarray,
    bin_width: float = _BIN_WIDTH_DEG,
    percentile: float = _PERCENTILE,
    min_samples: int = _MIN_BIN_SAMPLES,
) -> tuple[np.ndarray, np.ndarray]:
    """Pure estimator: per spray-bin fence distance from non-HR wall-ball distances.

    Returns (dist, n_samples) per bin; dist is NaN where the bin is too sparse.
    ``is_hr`` is a bool mask; non-HR balls' hit_distance marks first contact (the
    wall for deep flies), so the upper percentile of non-HR distance ~= the fence.
    """
    dist = np.full(len(bin_centers), np.nan)
    counts = np.zeros(len(bin_centers), dtype=int)
    nonhr = ~is_hr
    for i, c in enumerate(bin_centers):
        in_bin = (spray >= c - bin_width / 2) & (spray < c + bin_width / 2) & nonhr
        d = hit_distance[in_bin]
        d = d[np.isfinite(d)]
        counts[i] = d.size
        if d.size >= min_samples:
            dist[i] = float(np.percentile(d, percentile))
    return dist, counts


def _load_park_balls(park_id: str, sf: int, st: int, container: str) -> tuple[np.ndarray, ...]:
    """Deep fly balls hit at a park: (spray_deg, hit_distance_ft, is_hr)."""
    q = (
        "SELECT toString(hc_x), toString(hc_y), toString(hit_distance_ft), "
        "if(events='home_run',1,0) AS hr "
        "FROM pitches "
        f"WHERE park_id = '{park_id}' AND description='in_play' "
        f"AND toYear(game_date) BETWEEN {sf} AND {st} "
        "AND hc_x IS NOT NULL AND hc_y IS NOT NULL AND hit_distance_ft IS NOT NULL "
        "AND launch_angle_deg BETWEEN 15 AND 45 AND launch_speed_mph >= 90 "
        "FORMAT TSV"
    )
    rows = [ln.split("\t") for ln in _run_ch(q, container=container).strip().split("\n") if ln]
    if not rows:
        return np.array([]), np.array([]), np.array([], dtype=bool)
    spray = np.array([_spray_deg(float(r[0]), float(r[1])) for r in rows])
    hit = np.array([float(r[2]) for r in rows])
    hr = np.array([r[3] == "1" for r in rows], dtype=bool)
    return spray, hit, hr


def build_park_polyline(
    park_id: str, documented: list[dict[str, float]], sf: int, st: int, container: str
) -> tuple[list[dict[str, float]], list[str]]:
    """Build a high-res polyline for one park; returns (points, per-bin notes)."""
    bin_centers = np.arange(-45.0, 45.0 + _BIN_WIDTH_DEG, _BIN_WIDTH_DEG)
    spray, hit, hr = _load_park_balls(park_id, sf, st, container)
    est, counts = (
        estimate_fence_distances(spray, hit, hr, bin_centers=bin_centers)
        if spray.size
        else (np.full(len(bin_centers), np.nan), np.zeros(len(bin_centers), dtype=int))
    )

    points: list[dict[str, float]] = []
    notes: list[str] = []
    for c, e, n in zip(bin_centers, est, counts, strict=True):
        doc_d = _doc_interp(documented, float(c), "distance_ft")
        height = _doc_interp(documented, float(c), "height_ft")
        if np.isnan(e):
            d, src = doc_d, f"doc(n={n})"
        elif abs(e - doc_d) > _DOC_TOLERANCE_FT or not (_ABS_MIN_FT <= e <= _ABS_MAX_FT):
            d, src = doc_d, f"doc(reject est {e:.0f}, n={n})"
        else:
            d, src = e, f"est {e:.0f} (n={n})"
        points.append(
            {
                "angle_from_centerline_deg": round(float(c), 1),
                "distance_ft": round(float(d), 1),
                "height_ft": round(float(height), 1),
            }
        )
        notes.append(f"{c:+5.1f}: doc={doc_d:.0f} -> {src}")
    return points, notes


def main() -> None:
    ap = argparse.ArgumentParser(description="Empirical high-res park fence estimator (2c.7).")
    ap.add_argument("--season-from", type=int, default=2015)
    ap.add_argument("--season-to", type=int, default=2025)
    ap.add_argument("--park", default=None, help="Estimate a single park (else all 30).")
    ap.add_argument("--geom-dir", type=Path, default=_GEOM_DIR)
    ap.add_argument("--out-dir", type=Path, default=_STAGING_DIR)
    ap.add_argument("--apply", action="store_true", help="Write to prod geom dir, not staging.")
    ap.add_argument("--dry-run", action="store_true", help="Print per-bin notes, write nothing.")
    ap.add_argument("--container", default="bullpen-clickhouse")
    args = ap.parse_args()

    st = args.season_to
    if st >= _HOLDOUT_YEAR:
        print(f"WARNING: clamping season-to {st} -> 2025 (rule 13).")
        st = _HOLDOUT_YEAR - 1
    sf = args.season_from
    out_dir = args.geom_dir if args.apply else args.out_dir

    park_files = (
        [args.geom_dir / f"{args.park}.json"]
        if args.park
        else sorted(p for p in args.geom_dir.glob("*.json") if not p.name.startswith("_"))
    )
    if not args.dry_run:
        out_dir.mkdir(parents=True, exist_ok=True)

    for pf in park_files:
        park = json.loads(pf.read_text())
        pid = park["park_id"]
        points, notes = build_park_polyline(pid, park["fence_polyline"], sf, st, args.container)
        n_est = sum(1 for n in notes if n.split("-> ")[-1].startswith("est"))
        print(f"{pid}: {n_est}/{len(points)} bins data-derived, rest documented fallback")
        if args.dry_run:
            for n in notes:
                print(f"    {n}")
            continue
        park["fence_polyline"] = points
        park["geometry_source"] = (
            f"empirical fence distances (Statcast wall-ball p{_PERCENTILE:.0f}, "
            f"{sf}-{st}); documented fallback + height overlay"
        )
        (out_dir / pf.name).write_text(json.dumps(park, indent=2) + "\n")

    if not args.dry_run:
        where = "PROD" if args.apply else "staging"
        print(f"\nwrote {len(park_files)} park files -> {out_dir} ({where})")
        if not args.apply:
            print(
                "Review, then re-retrodict + retrain pointing the loader here, and run "
                "compare_park_factors.py: physics-vs-observed_norm should climb for CIN/NYY/MIL."
            )


if __name__ == "__main__":
    main()
