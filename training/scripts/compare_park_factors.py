"""Park-factor comparison: which "park factor" are we even gating against? (2c.7)

The 2c.7 gate (decision [52]) scores the physics/MLP per-park P(HR) against an
external file (``published_hr_factors.json``, Savant 2024 single-season). The
diagnostic showed the physics counterfactual tops out at ~0.40 Spearman vs that
file, and *raw* observed HR rates only hit ~0.58 vs it — but raw rates are
roster/era-contaminated, so 0.58 doesn't prove the target is bad, it proves
raw != normalized. This tool computes the comparison properly.

It builds four same-shape per-park HR-propensity vectors, all season-bounded to
2015-2025 (2026 is holdout — rule 13), and prints every pairwise Spearman + a
per-park table:

  observed_raw   — HR / BIP in games at the park. Roster + era contaminated.
  observed_norm  — STANDARD park factor: (HR-rate in games at park X) /
                   (HR-rate in X's home-team's ROAD games). Roster-controlled,
                   the same shape as a published factor. Pooled multi-year.
  physics        — mean retrodiction prob_hr per park (the labels; "same balls
                   at every park" -> a normalized counterfactual park effect).
  published      — the external Savant file (kept as a secondary check).

It also reports the SPLIT-HALF RELIABILITY of observed_norm (Spearman between
two game-disjoint halves) — the achievable ceiling for *any* model against this
target, which is what a re-aimed gate threshold should be derived from, not 0.80
picked a priori.

Decisions this informs (D2 in the 2c.7 work):
  - observed_norm vs published HIGH  -> published is a fine target; the 0.40 is a
    real physics deficiency (geometry). Don't re-aim, fix physics.
  - observed_norm vs published LOW   -> the single-year published file is noisy;
    re-aim the gate at observed_norm (multi-year, self-consistent) and set the
    threshold from its split-half reliability.
  - physics vs observed_norm         -> the TRUE physics-fidelity number (higher
    than physics-vs-raw 0.21 once roster noise is removed) = the headroom the
    fence-geometry fix can recover.

Read-only, lightweight GROUP BYs only (no pitches-x-labels join -> no OOM). Author
on the Mac (ADR-0006); run on the desktop from training/:

    uv run python scripts/compare_park_factors.py
    uv run python scripts/compare_park_factors.py --report data/park_factors.json
"""

from __future__ import annotations

import argparse
import json
import subprocess
from datetime import UTC, datetime
from itertools import combinations
from pathlib import Path

import numpy as np
from scipy.stats import spearmanr

_TRAINING_ROOT = Path(__file__).resolve().parents[1]
_DEFAULT_FACTORS = _TRAINING_ROOT / "data" / "published_hr_factors.json"
_HOLDOUT_YEAR = 2026  # rule 13: never include in any train/validation/analysis split


def _run_ch(query: str, *, container: str = "bullpen-clickhouse") -> str:
    res = subprocess.run(
        ["docker", "exec", container, "clickhouse-client", "--query", query],
        check=True,
        capture_output=True,
        text=True,
    )
    return res.stdout


def _rows(tsv: str) -> list[list[str]]:
    return [ln.split("\t") for ln in tsv.strip().split("\n") if ln]


def _bounds(season_from: int, season_to: int) -> str:
    return f"toYear(game_date) BETWEEN {season_from} AND {season_to}"


# --- the four factor vectors ----------------------------------------------


def observed_rates(
    *, season_from: int, season_to: int, container: str, game_parity: int | None = None
) -> tuple[dict[str, float], dict[str, float]]:
    """Return (at_park_hr_rate, team_road_hr_rate) keyed by park/team abbrev.

    ``at_park_hr_rate[X]``  = HR/BIP in games at park X.
    ``team_road_hr_rate[X]``= HR/BIP in games where team X is the visitor.
    ``game_parity`` (0/1) restricts to a game-disjoint half for reliability.
    """
    parity = ""
    if game_parity is not None:
        parity = f" AND cityHash64(game_id) % 2 = {game_parity}"
    base = f"description = 'in_play' AND {_bounds(season_from, season_to)}{parity}"
    # FINAL: pitches + bbip_retrodicted_labels are ReplacingMergeTree. The MLP
    # training loader (mlp/dataset.py) reads both with FINAL, so this comparison
    # MUST too — otherwise it averages stale + current rows from earlier retrodict
    # runs and reports a blend the model never trained on.
    home = _rows(
        _run_ch(
            f"SELECT park_id, countIf(events='home_run') AS hr, count() AS n "
            f"FROM pitches FINAL WHERE {base} GROUP BY park_id FORMAT TSV",
            container=container,
        )
    )
    road = _rows(
        _run_ch(
            f"SELECT away_team AS team, countIf(events='home_run') AS hr, count() AS n "
            f"FROM pitches FINAL WHERE {base} GROUP BY away_team FORMAT TSV",
            container=container,
        )
    )
    at_park = {r[0]: int(r[1]) / int(r[2]) for r in home if int(r[2]) > 0}
    team_road = {r[0]: int(r[1]) / int(r[2]) for r in road if int(r[2]) > 0}
    return at_park, team_road


def normalized_factor(at_park: dict[str, float], team_road: dict[str, float]) -> dict[str, float]:
    """Standard park factor = at-park rate / same-team road rate, mean-centered to 1.0."""
    raw = {x: at_park[x] / team_road[x] for x in at_park if x in team_road and team_road[x] > 0}
    mean = float(np.mean(list(raw.values()))) if raw else 1.0
    return {x: v / mean for x, v in raw.items()} if mean > 0 else raw


def physics_counterfactual(*, season_from: int, season_to: int, container: str) -> dict[str, float]:
    """Mean retrodiction prob_hr per park (the labels), season-bounded."""
    rows = _rows(
        _run_ch(
            f"SELECT park_id, avg(prob_hr) AS p, count() AS n FROM bbip_retrodicted_labels FINAL "
            f"WHERE {_bounds(season_from, season_to)} GROUP BY park_id FORMAT TSV",
            container=container,
        )
    )
    return {r[0]: float(r[1]) for r in rows if int(r[2]) > 0}


# --- correlation + reporting ----------------------------------------------


def _spearman(a: dict[str, float], b: dict[str, float]) -> tuple[float, int]:
    keys = sorted(set(a) & set(b))
    if len(keys) < 3:
        return float("nan"), len(keys)
    res = spearmanr([a[k] for k in keys], [b[k] for k in keys])
    rho = float(res.statistic)  # type: ignore[union-attr]
    return (0.0 if np.isnan(rho) else rho), len(keys)


def _ranks(values: dict[str, float]) -> dict[str, int]:
    ordered = sorted(values, key=lambda k: -values[k])
    return {pid: i + 1 for i, pid in enumerate(ordered)}


def _leave_one_out_spearman(
    a: dict[str, float], b: dict[str, float]
) -> list[tuple[str, float, float]]:
    """Per-park leave-one-out Spearman of ``a`` vs ``b``.

    Returns ``(park, rho_without_park, delta_vs_full)`` sorted by delta descending
    — the park at the top is the one whose removal raises rho the most, i.e. the
    biggest drag on the cross-park correlation. Built to test whether a single
    mismatched park (e.g. ATH after the 2025 Oakland->Sacramento move, modelled
    only as Oakland) is capping the headline rho at n=30.
    """
    keys = sorted(set(a) & set(b))
    full, _ = _spearman(a, b)
    out: list[tuple[str, float, float]] = []
    for k in keys:
        sub_a = {x: a[x] for x in keys if x != k}
        sub_b = {x: b[x] for x in keys if x != k}
        rho_without, _ = _spearman(sub_a, sub_b)
        out.append((k, rho_without, rho_without - full))
    out.sort(key=lambda t: -t[2])
    return out


def emit_anchor(
    *, season_from: int, season_to: int, container: str, out_path: Path
) -> dict[str, float]:
    """Compute the frozen observed_norm anchor + its split-half reliability and
    write it to ``out_path`` — the 2c.7 gate reference (decision [140] amends [52]).

    Needs only ``pitches`` (observed rates), NOT the retrodiction labels, so it can
    be emitted before the overnight relabel runs. Run on the desktop (ADR-0006):

        uv run python scripts/compare_park_factors.py \\
            --emit-anchor data/observed_norm_factors.json

    Then bring the file back to the Mac to commit (no commits from the prod box).
    """
    at_park, team_road = observed_rates(
        season_from=season_from, season_to=season_to, container=container
    )
    observed_norm = normalized_factor(at_park, team_road)

    # Split-half reliability (game-disjoint halves) — the achievable ceiling the
    # 0.65 gate sits below ([139]/[140]); recorded in the anchor for provenance.
    ah0, ar0 = observed_rates(
        season_from=season_from, season_to=season_to, container=container, game_parity=0
    )
    ah1, ar1 = observed_rates(
        season_from=season_from, season_to=season_to, container=container, game_parity=1
    )
    reliability, _ = _spearman(normalized_factor(ah0, ar0), normalized_factor(ah1, ar1))

    payload = {
        "schema_version": 1,
        "reference": "observed_norm",
        "observed_norm_factors": {k: round(v, 6) for k, v in sorted(observed_norm.items())},
        "metadata": {
            "seasons": f"{season_from}-{season_to}",
            "split_half_reliability": round(reliability, 4),
            "n_parks": len(observed_norm),
            "holdout_excluded": _HOLDOUT_YEAR,
            "source": "scripts/compare_park_factors.py --emit-anchor",
            "generated_at": datetime.now(UTC).isoformat(timespec="seconds"),
        },
    }
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(payload, indent=2) + "\n")
    print(
        f"wrote observed_norm anchor: {len(observed_norm)} parks, seasons "
        f"{season_from}-{season_to}, split-half reliability rho={reliability:+.3f} -> {out_path}"
    )
    return observed_norm


def main() -> None:
    ap = argparse.ArgumentParser(description="Cross-park factor comparison (2c.7).")
    ap.add_argument("--season-from", type=int, default=2015)
    ap.add_argument("--season-to", type=int, default=2025)
    ap.add_argument("--factors", type=Path, default=_DEFAULT_FACTORS)
    ap.add_argument("--container", default="bullpen-clickhouse")
    ap.add_argument("--report", type=Path, default=None)
    ap.add_argument(
        "--emit-anchor",
        type=Path,
        default=None,
        help="Write the frozen observed_norm gate anchor (decision [140]) to this path and exit.",
    )
    args = ap.parse_args()

    season_to = args.season_to
    if season_to >= _HOLDOUT_YEAR:
        print(
            f"WARNING: clamping season-to {season_to} -> 2025 (rule 13: {_HOLDOUT_YEAR} holdout)."
        )
        season_to = _HOLDOUT_YEAR - 1
    sf, st = args.season_from, season_to

    # Emit-only mode: write the frozen gate anchor and stop (no physics needed).
    if args.emit_anchor is not None:
        emit_anchor(
            season_from=sf, season_to=st, container=args.container, out_path=args.emit_anchor
        )
        return

    published = dict(json.loads(args.factors.read_text())["park_hr_factors"])

    at_park, team_road = observed_rates(season_from=sf, season_to=st, container=args.container)
    observed_raw = dict(at_park)
    observed_norm = normalized_factor(at_park, team_road)
    physics = physics_counterfactual(season_from=sf, season_to=st, container=args.container)

    factors: dict[str, dict[str, float]] = {
        "observed_raw": observed_raw,
        "observed_norm": observed_norm,
        "physics": physics,
        "published": published,
    }

    # Split-half reliability of the normalized factor (achievable ceiling).
    ah0, ar0 = observed_rates(season_from=sf, season_to=st, container=args.container, game_parity=0)
    ah1, ar1 = observed_rates(season_from=sf, season_to=st, container=args.container, game_parity=1)
    norm0, norm1 = normalized_factor(ah0, ar0), normalized_factor(ah1, ar1)
    reliability, _ = _spearman(norm0, norm1)

    print(f"\n=== seasons {sf}-{st} (2026 excluded, rule 13) ===")
    print("\nPairwise Spearman rho:")
    for a, b in combinations(factors, 2):
        rho, n = _spearman(factors[a], factors[b])
        print(f"  {a:<14} vs {b:<14} rho={rho:+.3f}  (n={n})")
    print(f"\nobserved_norm split-half reliability (achievable ceiling): rho={reliability:+.3f}")

    # Per-park table, sorted by published rank.
    pubr = _ranks(published)
    rk = {name: _ranks(v) for name, v in factors.items()}

    def cell(name: str, pid: str) -> str:
        v = factors[name].get(pid)
        return f"{v:>7.3f}#{rk[name].get(pid, 0):<2}" if v is not None else f"{'--':>10}"

    print(f"\n{'PARK':<5} {'pub':>6} {'obs_raw':>9} {'obs_norm':>9} {'physics':>9}   (rank)")
    print("-" * 60)
    for pid in sorted(published, key=lambda k: pubr[k]):
        print(
            f"{pid:<5} {published[pid]:>5.2f}#{pubr[pid]:<2} "
            f"{cell('observed_raw', pid)} {cell('observed_norm', pid)} {cell('physics', pid)}"
        )

    # Interpretation.
    on_pub, _ = _spearman(observed_norm, published)
    ph_on, _ = _spearman(physics, observed_norm)
    ph_pub, _ = _spearman(physics, published)
    print("\n=== read ===")
    print(
        f"  observed_norm vs published = {on_pub:+.3f}  -> "
        + (
            "published is a sound target; don't re-aim, fix physics."
            if on_pub >= 0.75
            else "published (single-year) is noisy; re-aiming at observed_norm is justified."
        )
    )
    print(
        f"  physics vs observed_norm   = {ph_on:+.3f}  -> true physics fidelity "
        "(headroom the fence fix can recover; compare to the 0.21-vs-raw figure)."
    )
    print(f"  physics vs published       = {ph_pub:+.3f}  -> the current gate ceiling.")
    print(
        f"  -> a re-aimed gate threshold should sit near the {reliability:+.3f} reliability, "
        "not an a-priori 0.80."
    )

    # Leave-one-out: which single park most drags physics-vs-observed_norm rho?
    # Tests the ATH/Oakland->Sacramento confound (and surfaces DET/CHC/SEA from [139]).
    loo = _leave_one_out_spearman(physics, observed_norm)
    print("\n=== leave-one-out (physics vs observed_norm), top drags ===")
    print(f"  full rho = {ph_on:+.3f} over n={len(set(physics) & set(observed_norm))} parks")
    for pid, rho_without, delta in loo[:5]:
        ph_rank = rk["physics"].get(pid, 0)
        on_rank = rk["observed_norm"].get(pid, 0)
        print(
            f"  drop {pid:<4} -> rho {rho_without:+.3f}  (Δ {delta:+.3f})   "
            f"physics #{ph_rank} vs observed_norm #{on_rank}"
        )

    if args.report is not None:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(
            json.dumps(
                {
                    "season_from": sf,
                    "season_to": st,
                    "factors": factors,
                    "reliability_observed_norm": reliability,
                    "spearman": {
                        f"{a}|{b}": _spearman(factors[a], factors[b])[0]
                        for a, b in combinations(factors, 2)
                    },
                },
                indent=2,
            )
        )
        print(f"\nwrote {args.report}")


if __name__ == "__main__":
    main()
