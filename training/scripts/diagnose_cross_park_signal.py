"""Cross-park signal isolation diagnostic (2c.7 gate failure triage).

The 2c.7 cross-park sanity gate (decision [52]) is failing: Spearman rho of
per-park predicted P(HR) vs published HR factors must be > 0.80, last run gave
0.493. The two-directional mid-pack scramble (NYY under, LAD/ATH over) points at
a broad per-park-signal problem, not one bug — so before touching anything we
need to know *which layer* loses the signal.

This script computes Spearman vs ``published_hr_factors.json`` for THREE
quantities, side by side, and prints a per-park rank table with rank deltas. All
three are the same shape — a per-park HR-propensity vector — so they are directly
comparable to the published target:

  (a) RAW-RETRO  — mean ``prob_hr`` of the retrodiction labels per park, averaged
                   over the real BIP distribution (straight from
                   ``bbip_retrodicted_labels``). This is the physics + geometry +
                   weather layer's own answer, before the MLP touches anything.
  (b) MLP-REAL   — the trained MLP scored on the *real* per-park BIP feature
                   distribution: average head P(HR) over every BIP. This is what
                   the per-park heads actually learned to reproduce.
  (c) MLP-PROBE  — ``cross_park_p_hr`` over the gate's CANONICAL_INPUTS — i.e. the
                   gate exactly as it runs today.

Reading the result (decision tree the user asked for):

  * (a) already decorrelates from published  -> the signal is lost in
        physics / geometry / weather (the labels themselves don't track park
        factors). Fixing the probe or retraining won't help.
  * (a) tracks but (b) doesn't                -> the MLP heads underfit the
        per-park signal (more epochs / capacity / loss weighting).
  * (a) and (b) both track but (c) doesn't    -> it's the PROBE or the target:
        CANONICAL_INPUTS samples a region the model barely sees (Patch B), or the
        published factors are roster-contaminated (menu item 3).

Read-only: it reads ClickHouse (via ``dataset.load_arrays``) and the trained
artifact. Author on the Mac (ADR-0006), push, run on the desktop from training/:

    uv run python scripts/diagnose_cross_park_signal.py                  # val 2025, full
    uv run python scripts/diagnose_cross_park_signal.py --limit 20000    # quick subsample
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np
import torch
import torch.nn.functional as F
from scipy.stats import spearmanr

from bullpen_training.battedball.mlp.architecture import BattedBallMLP, build_model
from bullpen_training.battedball.mlp.dataset import FeatureScaler, load_arrays
from bullpen_training.battedball.mlp.sanity import (
    SPEARMAN_GATE,
    cross_park_p_hr,
    load_published_factors,
)

_DEFAULT_MODEL_DIR = Path("artifacts/battedball_mlp_v1")
_DEFAULT_FACTORS = Path("training/data/published_hr_factors.json")


def _ranks(values: dict[str, float]) -> dict[str, int]:
    """1-based rank (1 = highest value)."""
    ordered = sorted(values, key=lambda k: -values[k])
    return {pid: i + 1 for i, pid in enumerate(ordered)}


def _spearman(pred: dict[str, float], published: dict[str, float]) -> float:
    parks = sorted(published)
    res = spearmanr([pred[p] for p in parks], [published[p] for p in parks])
    rho = float(res.statistic)  # type: ignore[union-attr]
    return 0.0 if np.isnan(rho) else rho


def _load_model(model_dir: Path) -> tuple[BattedBallMLP, FeatureScaler, tuple[str, ...]]:
    metadata = json.loads((model_dir / "metadata.json").read_text())
    park_order = tuple(metadata["park_order"])
    sc = metadata["feature_scaler"]
    scaler = FeatureScaler(
        means=np.array(sc["means"], dtype=np.float32),
        stds=np.array(sc["stds"], dtype=np.float32),
        is_continuous=np.array(sc["is_continuous"], dtype=bool),
    )
    model = build_model(n_parks=len(park_order))
    model.load_state_dict(torch.load(model_dir / "model.pt", weights_only=True))
    model.eval()
    return model, scaler, park_order


def raw_retro_p_hr(labels: np.ndarray, park_order: tuple[str, ...]) -> dict[str, float]:
    """(a) Mean retrodiction prob_hr per park over the real BIP distribution.

    ``labels`` is (N, n_parks, 5) from ``load_arrays``; column 4 is prob_hr.
    """
    mean_hr = labels[:, :, 4].mean(axis=0)  # (n_parks,)
    return {pid: float(mean_hr[i]) for i, pid in enumerate(park_order)}


def mlp_real_p_hr(
    model: BattedBallMLP,
    scaler: FeatureScaler,
    features: np.ndarray,
    park_order: tuple[str, ...],
    *,
    batch: int = 8192,
) -> dict[str, float]:
    """(b) Mean MLP head P(HR) per park over the real BIP feature distribution.

    Every BIP is scored through every park head (the counterfactual the model
    encodes); we average each head's P(HR) over all BIPs. Batched to bound memory.
    """
    x = scaler.transform(features)
    n_parks = len(park_order)
    acc = np.zeros(n_parks, dtype=np.float64)
    with torch.no_grad():
        for i in range(0, len(x), batch):
            logits = model(torch.from_numpy(x[i : i + batch]))  # (b, n_parks, 5)
            p_hr = F.softmax(logits, dim=-1).numpy()[:, :, 4]  # (b, n_parks)
            acc += p_hr.sum(axis=0)
    mean = acc / max(len(x), 1)
    return {pid: float(mean[i]) for i, pid in enumerate(park_order)}


def _print_table(
    published: dict[str, float],
    layers: dict[str, dict[str, float]],
) -> None:
    pub_rank = _ranks(published)
    layer_ranks = {name: _ranks(vals) for name, vals in layers.items()}
    names = list(layers)
    # Header.
    cols = "  ".join(f"{n:>22}" for n in names)
    print(f"\n{'PARK':<5} {'PUB(f/rk)':>12}   {cols}")
    print("-" * (5 + 13 + 3 + len(names) * 24))
    for pid in sorted(published, key=lambda k: pub_rank[k]):  # by published rank
        cells = []
        for n in names:
            v = layers[n][pid]
            r = layer_ranks[n][pid]
            d = r - pub_rank[pid]
            cells.append(f"{v:>8.4f} rk{r:>2} d{d:>+3}")
        print(f"{pid:<5} {published[pid]:>6.2f}/{pub_rank[pid]:>2}   " + "  ".join(cells))


def main() -> None:
    ap = argparse.ArgumentParser(description="Cross-park signal isolation diagnostic (2c.7).")
    ap.add_argument("--season-from", type=int, default=2025)
    ap.add_argument("--season-to", type=int, default=2025)
    ap.add_argument("--limit", type=int, default=None, help="Cap BIPs (quick run).")
    ap.add_argument("--model-dir", type=Path, default=_DEFAULT_MODEL_DIR)
    ap.add_argument("--factors", type=Path, default=_DEFAULT_FACTORS)
    ap.add_argument("--container", default="bullpen-clickhouse")
    ap.add_argument("--report", type=Path, default=None, help="Optional JSON output path.")
    args = ap.parse_args()

    published = load_published_factors(args.factors)
    model, scaler, park_order = _load_model(args.model_dir)
    if set(park_order) != set(published):
        raise SystemExit(
            f"park set mismatch: model={sorted(set(park_order) - set(published))} "
            f"published={sorted(set(published) - set(park_order))}"
        )

    print(
        f"Loading BIPs {args.season_from}-{args.season_to}"
        + (f" (limit {args.limit})" if args.limit else "")
        + " ..."
    )
    features, labels = load_arrays(
        season_from=args.season_from,
        season_to=args.season_to,
        park_order=park_order,
        limit=args.limit,
        container=args.container,
    )
    print(f"Scored {len(features)} BIPs x {len(park_order)} parks.")

    layers = {
        "RAW-RETRO": raw_retro_p_hr(labels, park_order),
        "MLP-REAL": mlp_real_p_hr(model, scaler, features, park_order),
        "MLP-PROBE": cross_park_p_hr(model, scaler, park_order),
    }
    rhos = {name: _spearman(vals, published) for name, vals in layers.items()}

    print(f"\n=== Spearman rho vs published HR factors (gate > {SPEARMAN_GATE:.2f}) ===")
    for name, rho in rhos.items():
        flag = "PASS" if rho > SPEARMAN_GATE else "FAIL"
        print(f"  {name:<10} rho = {rho:+.3f}   [{flag}]")

    _print_table(published, layers)

    # Interpretation, per the isolation decision tree.
    a, b, c = rhos["RAW-RETRO"], rhos["MLP-REAL"], rhos["MLP-PROBE"]
    print("\n=== Interpretation ===")
    if a <= SPEARMAN_GATE:
        print(
            "  RAW-RETRO already decorrelates -> the per-park signal is lost in "
            "PHYSICS / GEOMETRY / WEATHER, before the MLP. Probe/retrain fixes won't help; "
            "look at landing-distance MAE (menu 2), park geometry (menu 1), wind mapping (menu 5)."
        )
    elif b <= SPEARMAN_GATE:
        print(
            "  RAW-RETRO tracks but MLP-REAL doesn't -> the MLP HEADS UNDERFIT the per-park "
            "signal (more epochs / capacity / per-park loss weighting, or explicit park "
            "covariates — menu 4)."
        )
    elif c <= SPEARMAN_GATE:
        print(
            "  RAW-RETRO and MLP-REAL both track but MLP-PROBE doesn't -> it's the PROBE or "
            "the TARGET: CANONICAL_INPUTS samples a region the model barely sees (Patch B fixes "
            "this gate-side), or the published factors are roster-contaminated (menu 3)."
        )
    else:
        print("  All three track published — gate should pass; re-check the model artifact loaded.")

    if args.report is not None:
        payload = {
            "season_from": args.season_from,
            "season_to": args.season_to,
            "n_bips": len(features),
            "spearman": rhos,
            "layers": layers,
            "published": published,
        }
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(payload, indent=2))
        print(f"\nwrote {args.report}")


if __name__ == "__main__":
    main()
