"""Four-way model comparison: multi-output MLP vs per-park MLPs vs LightGBM vs per-park LightGBM.

Loads all four model types, evaluates each on the same per-park BIPs,
and produces matplotlib bar charts comparing Brier score, ECE, and
accuracy side by side for every park.

Usage:

  uv run python training/scripts/compare_all_models.py \
      --mlp-dir training/artifacts/battedball_mlp_v1 \
      --per-park-dir training/artifacts/battedball_mlp_per_park_v1 \
      --lgbm-dir training/artifacts/batted_ball_lgbm_baseline/v1 \
      --lgbm-per-park-dir training/artifacts/battedball_lgbm_per_park_v1 \
      --season-from 2024 --season-to 2024 \
      --out-dir training/data/eval

Outputs:
  - comparison_brier.png   — per-park Brier score grouped bar chart
  - comparison_ece.png     — per-park ECE grouped bar chart
  - comparison_accuracy.png — per-park accuracy grouped bar chart
  - comparison_summary.png — aggregate side-by-side + winner highlights
  - comparison_all.json    — raw metrics for all four models
"""

from __future__ import annotations

import argparse
import json
from dataclasses import dataclass
from pathlib import Path

# Import lightgbm before torch to avoid macOS dual-libomp segfault.
import lightgbm  # noqa: F401
import matplotlib.pyplot as plt
import numpy as np
import torch
import torch.nn.functional as F

from bullpen_training.battedball.eval.comparison import (
    per_park_metrics,
)
from bullpen_training.battedball.features_shared import OUTCOME_NAMES
from bullpen_training.battedball.lgbm_baseline import load_baseline
from bullpen_training.battedball.lgbm_baseline import predict_proba_calibrated as lgbm_predict
from bullpen_training.battedball.lgbm_baseline.dataset import load_lgbm_dataset
from bullpen_training.battedball.lgbm_per_park import load_per_park_bundle
from bullpen_training.battedball.lgbm_per_park import predict_proba_calibrated as lgbm_pp_predict
from bullpen_training.battedball.lgbm_per_park.dataset import load_park_lgbm_dataset
from bullpen_training.battedball.mlp.architecture import build_model
from bullpen_training.battedball.mlp.dataset import FeatureScaler, load_arrays
from bullpen_training.battedball.mlp_per_park.architecture import build_per_park_model
from bullpen_training.battedball.mlp_per_park.dataset import load_park_arrays

COLOR_MLP = "#2563eb"
COLOR_PER_PARK = "#d97706"
COLOR_LGBM = "#059669"
COLOR_LGBM_PP = "#dc2626"


@dataclass
class PerParkResult:
    park_id: str
    brier: float
    ece: float
    accuracy: float
    n_samples: int


def _load_mlp_results(
    mlp_dir: Path,
    park_order: tuple[str, ...],
    season_from: int,
    season_to: int,
) -> list[PerParkResult]:
    md = json.loads((mlp_dir / "metadata.json").read_text())
    scaler = FeatureScaler(
        means=np.array(md["feature_scaler"]["means"], dtype=np.float32),
        stds=np.array(md["feature_scaler"]["stds"], dtype=np.float32),
        is_continuous=np.array(md["feature_scaler"]["is_continuous"], dtype=bool),
    )
    model = build_model(n_parks=len(park_order))
    model.load_state_dict(torch.load(mlp_dir / "model.pt", weights_only=True))
    model.eval()

    feat, lab = load_arrays(season_from=season_from, season_to=season_to, park_order=park_order)
    xs = scaler.transform(feat)
    with torch.no_grad():
        probs = F.softmax(model(torch.from_numpy(xs)), dim=-1).numpy()

    flat_pred = probs.reshape(-1, probs.shape[-1])
    flat_labels = lab.reshape(-1, lab.shape[-1])
    flat_park_ids: list[str] = []
    for _ in range(probs.shape[0]):
        flat_park_ids.extend(park_order)

    metrics = per_park_metrics(
        pred_probs=flat_pred.astype(np.float64),
        label_distributions=flat_labels.astype(np.float64),
        park_ids=flat_park_ids,
        park_order=park_order,
        model="mlp",
    )
    return [PerParkResult(m.park_id, m.brier, m.ece, m.accuracy, m.n_samples) for m in metrics]


def _load_per_park_mlp_results(
    per_park_dir: Path,
    park_order: tuple[str, ...],
    season_from: int,
    season_to: int,
) -> list[PerParkResult]:
    results: list[PerParkResult] = []
    for park_id in park_order:
        park_dir = per_park_dir / park_id
        if not (park_dir / "model.pt").exists():
            print(f"  SKIP {park_id}: no per-park MLP model found")
            continue

        md = json.loads((park_dir / "metadata.json").read_text())
        scaler = FeatureScaler(
            means=np.array(md["feature_scaler"]["means"], dtype=np.float32),
            stds=np.array(md["feature_scaler"]["stds"], dtype=np.float32),
            is_continuous=np.array(md["feature_scaler"]["is_continuous"], dtype=bool),
        )
        model = build_per_park_model()
        model.load_state_dict(torch.load(park_dir / "model.pt", weights_only=True))
        model.eval()

        feat, lab = load_park_arrays(
            park_id=park_id, season_from=season_from, season_to=season_to,
        )
        if feat.shape[0] == 0:
            continue
        xs = scaler.transform(feat)
        with torch.no_grad():
            probs = F.softmax(model(torch.from_numpy(xs)), dim=-1).numpy()

        metrics = per_park_metrics(
            pred_probs=probs.astype(np.float64),
            label_distributions=lab.astype(np.float64),
            park_ids=[park_id] * probs.shape[0],
            park_order=[park_id],
            model="per_park_mlp",
        )
        m = metrics[0]
        results.append(PerParkResult(m.park_id, m.brier, m.ece, m.accuracy, m.n_samples))
    return results


def _load_lgbm_results(
    lgbm_dir: Path,
    park_order: tuple[str, ...],
    season_from: int,
    season_to: int,
) -> list[PerParkResult]:
    bundle = load_baseline(lgbm_dir)
    df = load_lgbm_dataset(season_from=season_from, season_to=season_to)
    park_ids = df["park_id"].astype(str).tolist()
    pred = lgbm_predict(bundle, df).astype(np.float64)

    n_outcomes = len(OUTCOME_NAMES)
    labels_onehot = np.zeros((len(df), n_outcomes), dtype=np.float64)
    label_col = df["label"].values.astype(int)
    labels_onehot[np.arange(len(df)), label_col] = 1.0

    metrics = per_park_metrics(
        pred_probs=pred,
        label_distributions=labels_onehot,
        park_ids=park_ids,
        park_order=park_order,
        model="lgbm",
    )
    return [PerParkResult(m.park_id, m.brier, m.ece, m.accuracy, m.n_samples) for m in metrics]


def _load_lgbm_per_park_results(
    lgbm_pp_dir: Path,
    park_order: tuple[str, ...],
    season_from: int,
    season_to: int,
) -> list[PerParkResult]:
    results: list[PerParkResult] = []
    for park_id in park_order:
        park_dir = lgbm_pp_dir / park_id
        if not (park_dir / "model.txt").exists():
            print(f"  SKIP {park_id}: no per-park LightGBM model found")
            continue

        bundle = load_per_park_bundle(park_dir)
        df = load_park_lgbm_dataset(
            park_id=park_id, season_from=season_from, season_to=season_to,
        )
        if df.empty:
            continue
        pred = lgbm_pp_predict(bundle, df).astype(np.float64)

        n_outcomes = len(OUTCOME_NAMES)
        labels_onehot = np.zeros((len(df), n_outcomes), dtype=np.float64)
        label_col = df["label"].values.astype(int)
        labels_onehot[np.arange(len(df)), label_col] = 1.0

        metrics = per_park_metrics(
            pred_probs=pred,
            label_distributions=labels_onehot,
            park_ids=[park_id] * len(df),
            park_order=[park_id],
            model="lgbm_per_park",
        )
        m = metrics[0]
        results.append(PerParkResult(m.park_id, m.brier, m.ece, m.accuracy, m.n_samples))
    return results


def _plot_grouped_bars(
    park_ids: list[str],
    all_vals: list[tuple[str, list[float], str]],
    *,
    ylabel: str,
    title: str,
    out_path: Path,
    lower_is_better: bool = True,
) -> None:
    n = len(park_ids)
    n_models = len(all_vals)
    x = np.arange(n)
    width = 0.8 / n_models

    fig, ax = plt.subplots(figsize=(max(18, n * 0.7), 7))
    offsets = [(-1.5 + i) * width for i in range(n_models)]

    for idx, (label, vals, color) in enumerate(all_vals):
        ax.bar(x + offsets[idx], vals, width, label=label, color=color, alpha=0.85)

    for i in range(n):
        park_vals = [v[1][i] for v in all_vals]
        best = min(park_vals) if lower_is_better else max(park_vals)
        for idx, (_, vals, color) in enumerate(all_vals):
            if vals[i] == best:
                ax.plot(
                    x[i] + offsets[idx], vals[i], marker="*", color=color,
                    markersize=10, markeredgecolor="black", markeredgewidth=0.5,
                    zorder=5,
                )

    ax.set_xlabel("Park")
    ax.set_ylabel(ylabel)
    ax.set_title(title)
    ax.set_xticks(x)
    ax.set_xticklabels(park_ids, rotation=45, ha="right", fontsize=8)
    ax.legend(loc="upper right")
    ax.grid(axis="y", alpha=0.3)
    fig.tight_layout()
    fig.savefig(out_path, dpi=150)
    plt.close(fig)
    print(f"  wrote {out_path}")


def _plot_summary(
    all_results: list[tuple[str, list[PerParkResult], str]],
    out_path: Path,
) -> None:
    common_parks = None
    for _, results, _ in all_results:
        parks = {r.park_id for r in results}
        common_parks = parks if common_parks is None else common_parks & parks
    assert common_parks is not None
    common = sorted(common_parks)

    by_park: list[dict[str, PerParkResult]] = []
    models: list[str] = []
    colors: list[str] = []
    for label, results, color in all_results:
        by_park.append({r.park_id: r for r in results})
        models.append(label)
        colors.append(color)

    mean_brier = [float(np.mean([bp[p].brier for p in common])) for bp in by_park]
    mean_ece = [float(np.mean([bp[p].ece for p in common])) for bp in by_park]
    mean_acc = [float(np.mean([bp[p].accuracy for p in common])) for bp in by_park]

    wins = [0] * len(models)
    for p in common:
        briers = [bp[p].brier for bp in by_park]
        wins[int(np.argmin(briers))] += 1

    fig, axes = plt.subplots(1, 4, figsize=(22, 5))

    axes[0].bar(models, mean_brier, color=colors, alpha=0.85)
    axes[0].set_title("Mean Brier Score\n(lower = better)")
    axes[0].set_ylabel("Brier")
    axes[0].grid(axis="y", alpha=0.3)
    for i, v in enumerate(mean_brier):
        axes[0].text(i, v + 0.001, f"{v:.4f}", ha="center", fontsize=8)

    axes[1].bar(models, mean_ece, color=colors, alpha=0.85)
    axes[1].set_title("Mean ECE\n(lower = better)")
    axes[1].set_ylabel("ECE")
    axes[1].grid(axis="y", alpha=0.3)
    for i, v in enumerate(mean_ece):
        axes[1].text(i, v + 0.001, f"{v:.4f}", ha="center", fontsize=8)

    axes[2].bar(models, mean_acc, color=colors, alpha=0.85)
    axes[2].set_title("Mean Accuracy\n(higher = better)")
    axes[2].set_ylabel("Accuracy")
    axes[2].grid(axis="y", alpha=0.3)
    for i, v in enumerate(mean_acc):
        axes[2].text(i, v + 0.002, f"{v:.3f}", ha="center", fontsize=8)

    axes[3].bar(models, wins, color=colors, alpha=0.85)
    axes[3].set_title(f"Parks Won\n(by Brier, {len(common)} parks)")
    axes[3].set_ylabel("# Parks")
    axes[3].grid(axis="y", alpha=0.3)
    for i, v in enumerate(wins):
        axes[3].text(i, v + 0.3, str(v), ha="center", fontsize=11, fontweight="bold")

    for ax in axes:
        ax.tick_params(axis="x", rotation=20, labelsize=8)

    fig.suptitle("Four-Way Model Comparison: Aggregate Metrics", fontsize=14, fontweight="bold")
    fig.tight_layout()
    fig.savefig(out_path, dpi=150)
    plt.close(fig)
    print(f"  wrote {out_path}")


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Four-way comparison: MLP vs per-park MLP vs LightGBM vs per-park LightGBM."
    )
    parser.add_argument("--mlp-dir", type=Path, default=Path("artifacts/battedball_mlp_v1"))
    parser.add_argument(
        "--per-park-dir", type=Path, default=Path("artifacts/battedball_mlp_per_park_v1")
    )
    parser.add_argument(
        "--lgbm-dir", type=Path, default=Path("artifacts/batted_ball_lgbm_baseline/v1")
    )
    parser.add_argument(
        "--lgbm-per-park-dir", type=Path, default=Path("artifacts/battedball_lgbm_per_park_v1")
    )
    parser.add_argument("--season-from", type=int, default=2024)
    parser.add_argument("--season-to", type=int, default=2024)
    parser.add_argument("--out-dir", type=Path, default=Path("data/eval"))
    args = parser.parse_args()

    mlp_md = json.loads((args.mlp_dir / "metadata.json").read_text())
    park_order = tuple(mlp_md["park_order"])
    print(f"comparing 4 models on seasons {args.season_from}-{args.season_to}")
    print(f"  parks: {len(park_order)}")

    print("\n1. loading multi-output MLP predictions...")
    mlp_results = _load_mlp_results(args.mlp_dir, park_order, args.season_from, args.season_to)
    print(f"   {len(mlp_results)} parks evaluated")

    print("\n2. loading per-park MLP predictions...")
    pp_mlp_results = _load_per_park_mlp_results(
        args.per_park_dir, park_order, args.season_from, args.season_to
    )
    print(f"   {len(pp_mlp_results)} parks evaluated")

    print("\n3. loading LightGBM predictions...")
    lgbm_results = _load_lgbm_results(args.lgbm_dir, park_order, args.season_from, args.season_to)
    print(f"   {len(lgbm_results)} parks evaluated")

    print("\n4. loading per-park LightGBM predictions...")
    lgbm_pp_results = _load_lgbm_per_park_results(
        args.lgbm_per_park_dir, park_order, args.season_from, args.season_to
    )
    print(f"   {len(lgbm_pp_results)} parks evaluated")

    # Align to common parks.
    all_park_sets = [
        {r.park_id for r in mlp_results},
        {r.park_id for r in pp_mlp_results},
        {r.park_id for r in lgbm_results},
        {r.park_id for r in lgbm_pp_results},
    ]
    common_set = all_park_sets[0]
    for s in all_park_sets[1:]:
        common_set &= s
    common_parks = [p for p in park_order if p in common_set]
    print(f"\n{len(common_parks)} parks in common across all 4 models")

    mlp_by_park = {r.park_id: r for r in mlp_results}
    pp_mlp_by_park = {r.park_id: r for r in pp_mlp_results}
    lgbm_by_park = {r.park_id: r for r in lgbm_results}
    lgbm_pp_by_park = {r.park_id: r for r in lgbm_pp_results}

    out_dir = args.out_dir
    out_dir.mkdir(parents=True, exist_ok=True)

    model_series = [
        ("Multi-output MLP", [mlp_by_park[p] for p in common_parks], COLOR_MLP),
        ("Per-park MLP", [pp_mlp_by_park[p] for p in common_parks], COLOR_PER_PARK),
        ("LightGBM", [lgbm_by_park[p] for p in common_parks], COLOR_LGBM),
        ("Per-park LGBM", [lgbm_pp_by_park[p] for p in common_parks], COLOR_LGBM_PP),
    ]

    chart_defs = [
        ("brier", "Brier Score",
         "Per-Park Brier Score (lower = better)",
         True, "comparison_brier.png"),
        ("ece", "ECE",
         "Per-Park ECE (lower = better)",
         True, "comparison_ece.png"),
        ("accuracy", "Accuracy",
         "Per-Park Argmax Accuracy (higher = better)",
         False, "comparison_accuracy.png"),
    ]
    for metric, ylabel, title, lower, fname in chart_defs:
        bar_data = [
            (label, [getattr(r, metric) for r in results], color)
            for label, results, color in model_series
        ]
        _plot_grouped_bars(
            common_parks, bar_data,
            ylabel=ylabel, title=title,
            out_path=out_dir / fname, lower_is_better=lower,
        )

    _plot_summary(model_series, out_dir / "comparison_summary.png")

    json_out: dict[str, object] = {
        "schema_version": 1,
        "artifact_name": "four_way_comparison",
        "season_from": args.season_from,
        "season_to": args.season_to,
        "park_order": common_parks,
        "models": {},
    }
    model_keys = ["mlp", "per_park_mlp", "lgbm", "lgbm_per_park"]
    for key, (_, results, _) in zip(model_keys, model_series, strict=True):
        json_out["models"][key] = [  # type: ignore[union-attr]
            {"park_id": r.park_id, "brier": r.brier, "ece": r.ece,
             "accuracy": r.accuracy, "n_samples": r.n_samples}
            for r in results
        ]
    json_path = out_dir / "comparison_all.json"
    json_path.write_text(json.dumps(json_out, indent=2))
    print(f"  wrote {json_path}")

    print("\n== aggregate (mean across common parks) ==")
    for label, results, _ in model_series:
        mb = float(np.mean([r.brier for r in results]))
        me = float(np.mean([r.ece for r in results]))
        ma = float(np.mean([r.accuracy for r in results]))
        print(f"  {label:<18s}: Brier={mb:.4f}  ECE={me:.4f}  Acc={ma:.3f}")


if __name__ == "__main__":
    main()
