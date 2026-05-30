"""Eight-way pitch model comparison: 4 baselines + 4 advanced.

Trains and evaluates all models on the same temporal split, producing
a unified comparison table and matplotlib charts.

Usage:
  uv run python training/scripts/compare_all_pitch_models.py
  uv run python training/scripts/compare_all_pitch_models.py --limit 50000
"""

from __future__ import annotations

import argparse
import gc
import json
from pathlib import Path

import lightgbm  # noqa: F401 — import order guard (macOS libomp)
import matplotlib.pyplot as plt
import numpy as np
import pandas as pd

from bullpen_training.pitch_comparison.config import ExperimentConfig
from bullpen_training.pitch_comparison.data import (
    FEATURE_COLS,
    PITCH_TYPE_CLASSES,
    load_pitch_data,
    prepare_datasets,
)
from bullpen_training.pitch_comparison.embedding_model import (
    predict_embedding_model,
    train_embedding_model,
)
from bullpen_training.pitch_comparison.hierarchical_model import (
    predict_hierarchical,
    train_hierarchical,
)
from bullpen_training.pitch_comparison.hybrid_model import (
    predict_hybrid,
    train_hybrid,
)
from bullpen_training.pitch_comparison.metrics import (
    PitchTypeMetrics,
    compute_pitch_type_metrics,
)
from bullpen_training.pitch_comparison.models import (
    predict_baseline,
    predict_lgbm,
    predict_mlp,
    predict_xgb,
    train_baseline,
    train_lgbm,
    train_mlp,
    train_xgb,
)
from bullpen_training.pitch_comparison.transformer_model import (
    predict_transformer,
    train_transformer,
)

COLORS = {
    "LightGBM": "#2563eb",
    "Multi-task MLP": "#d97706",
    "XGBoost": "#059669",
    "Logistic/Ridge": "#dc2626",
    "Transformer": "#7c3aed",
    "Hybrid (T+LGBM)": "#0891b2",
    "Hierarchical": "#be185d",
    "Pitcher Embed": "#ca8a04",
}


def _plot_summary(
    results: list[PitchTypeMetrics],
    out_dir: Path,
) -> None:
    names = [r.name for r in results]
    colors = [COLORS.get(n, "#888") for n in names]

    fig, axes = plt.subplots(2, 3, figsize=(20, 10))
    axes_flat = axes.flatten()

    chart_defs = [
        ("Top-1 Accuracy", [r.accuracy for r in results], True),
        ("Top-2 Accuracy", [r.top2_accuracy for r in results], True),
        ("Log Loss", [r.logloss for r in results], False),
        ("Calibration ECE", [r.calibration_ece for r in results], False),
        ("Training Time (s)", [r.train_time_sec for r in results], False),
    ]

    for idx, (title, vals, higher_better) in enumerate(chart_defs):
        ax = axes_flat[idx]
        bars = ax.bar(names, vals, color=colors, alpha=0.85)
        ax.set_title(title, fontsize=11)
        ax.grid(axis="y", alpha=0.3)
        ax.tick_params(axis="x", rotation=35, labelsize=7)

        best = int(np.argmax(vals)) if higher_better else int(np.argmin(vals))
        bars[best].set_edgecolor("black")
        bars[best].set_linewidth(2)
        for i, v in enumerate(vals):
            fmt = f"{v:.4f}" if v < 10 else f"{v:.0f}"
            ax.text(i, v, fmt, ha="center", va="bottom", fontsize=7)

    # Hide unused subplot.
    axes_flat[5].axis("off")

    fig.suptitle(
        "8-Model Pitch Type Comparison (2025 holdout)",
        fontsize=14,
        fontweight="bold",
    )
    fig.tight_layout()
    fig.savefig(out_dir / "pitch_all_summary.png", dpi=150)
    plt.close(fig)
    print(f"  wrote {out_dir / 'pitch_all_summary.png'}")


def _plot_per_class(
    results: list[PitchTypeMetrics],
    out_dir: Path,
) -> None:
    n_models = len(results)
    classes = list(PITCH_TYPE_CLASSES)
    n_classes = len(classes)
    x = np.arange(n_classes)
    width = 0.8 / n_models
    colors = [COLORS.get(r.name, "#888") for r in results]

    fig, ax = plt.subplots(figsize=(16, 7))
    for idx, r in enumerate(results):
        vals = [r.per_class_accuracy.get(c, 0.0) for c in classes]
        offset = (idx - n_models / 2 + 0.5) * width
        ax.bar(x + offset, vals, width, label=r.name, color=colors[idx], alpha=0.85)

    ax.set_xlabel("Pitch Type")
    ax.set_ylabel("Accuracy")
    ax.set_title("Per-Class Accuracy by Model")
    ax.set_xticks(x)
    ax.set_xticklabels(classes)
    ax.legend(loc="upper right", fontsize=7)
    ax.grid(axis="y", alpha=0.3)
    fig.tight_layout()
    fig.savefig(out_dir / "pitch_per_class_accuracy.png", dpi=150)
    plt.close(fig)
    print(f"  wrote {out_dir / 'pitch_per_class_accuracy.png'}")


def _print_table(results: list[PitchTypeMetrics]) -> None:
    hdr = f"{'Model':<20s} | {'Acc':>7s} {'Top2':>7s} {'LogL':>7s} {'ECE':>7s} | {'Time':>7s}"
    print("\n" + "=" * len(hdr))
    print(hdr)
    print("-" * len(hdr))
    for r in results:
        print(
            f"{r.name:<20s} | "
            f"{r.accuracy:>7.4f} {r.top2_accuracy:>7.4f} "
            f"{r.logloss:>7.4f} {r.calibration_ece:>7.4f} | "
            f"{r.train_time_sec:>6.1f}s"
        )
    print("=" * len(hdr))


def main() -> None:
    parser = argparse.ArgumentParser(
        description="8-model pitch type comparison.",
    )
    parser.add_argument("--limit", type=int, default=None)
    parser.add_argument(
        "--out-dir",
        type=Path,
        default=Path("data/eval/pitch_advanced"),
    )
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument(
        "--skip-baselines",
        action="store_true",
        help="Skip the 4 baseline models (run only advanced).",
    )
    parser.add_argument("--season-from", type=int, default=2015)
    parser.add_argument("--season-to", type=int, default=2025)
    parser.add_argument(
        "--train-years",
        nargs="*",
        type=int,
        default=None,
    )
    parser.add_argument("--val-year", type=int, default=2024)
    parser.add_argument("--test-year", type=int, default=2025)
    args = parser.parse_args()

    train_years = (
        tuple(args.train_years)
        if args.train_years
        else tuple(
            y
            for y in range(args.season_from, args.season_to + 1)
            if y != args.val_year and y != args.test_year
        )
    )
    cfg = ExperimentConfig(
        seed=args.seed,
        limit=args.limit,
        out_dir=args.out_dir,
        season_from=args.season_from,
        season_to=args.season_to,
        train_years=train_years,
        val_years=(args.val_year,),
        test_years=(args.test_year,),
    )

    print("loading pitch data from ClickHouse...")
    raw_df = load_pitch_data(
        season_from=cfg.season_from,
        season_to=cfg.season_to,
        limit=cfg.limit,
    )
    print(f"  {len(raw_df)} rows loaded")

    print("preparing train/val/test splits...")
    train_df, val_df, test_df = prepare_datasets(
        raw_df,
        train_years=cfg.train_years,
        val_years=cfg.val_years,
        test_years=cfg.test_years,
    )
    print(f"  train: {len(train_df)}  val: {len(val_df)}  test: {len(test_df)}")
    del raw_df
    gc.collect()

    if len(test_df) == 0:
        print("ERROR: test set empty. Need data through 2025.")
        return

    # Ensure numeric types.
    for df in [train_df, val_df, test_df]:
        for col in FEATURE_COLS:
            df[col] = pd.to_numeric(df[col], errors="coerce")

    y_test = test_df["pitch_type_int"].values.astype(int)
    all_results: list[PitchTypeMetrics] = []

    # ---- BASELINE MODELS (1-4) ----
    if not args.skip_baselines:
        print("\n[1/8] LightGBM...")
        lgbm_b, lgbm_t = train_lgbm(train_df, val_df, seed=cfg.seed)
        lgbm_p = predict_lgbm(lgbm_b, test_df)
        all_results.append(
            compute_pitch_type_metrics(
                "LightGBM",
                y_test,
                lgbm_p.pitch_type_proba,
                lgbm_t,
            )
        )
        print(f"  done in {lgbm_t:.1f}s")
        del lgbm_b, lgbm_p
        gc.collect()

        print("\n[2/8] Multi-task MLP...")
        mlp_m, mlp_s, mlp_t = train_mlp(
            train_df,
            val_df,
            seed=cfg.seed,
        )
        mlp_p = predict_mlp(mlp_m, mlp_s, test_df)
        all_results.append(
            compute_pitch_type_metrics(
                "Multi-task MLP",
                y_test,
                mlp_p.pitch_type_proba,
                mlp_t,
            )
        )
        print(f"  done in {mlp_t:.1f}s")
        del mlp_m, mlp_s, mlp_p
        gc.collect()

        print("\n[3/8] XGBoost...")
        xgb_b, xgb_t = train_xgb(train_df, val_df, seed=cfg.seed)
        xgb_p = predict_xgb(xgb_b, test_df)
        all_results.append(
            compute_pitch_type_metrics(
                "XGBoost",
                y_test,
                xgb_p.pitch_type_proba,
                xgb_t,
            )
        )
        print(f"  done in {xgb_t:.1f}s")
        del xgb_b, xgb_p
        gc.collect()

        print("\n[4/8] Logistic/Ridge...")
        bl_b, bl_s, bl_t = train_baseline(
            train_df,
            val_df,
            seed=cfg.seed,
        )
        bl_p = predict_baseline(bl_b, bl_s, test_df)
        all_results.append(
            compute_pitch_type_metrics(
                "Logistic/Ridge",
                y_test,
                bl_p.pitch_type_proba,
                bl_t,
            )
        )
        print(f"  done in {bl_t:.1f}s")
        del bl_b, bl_s, bl_p
        gc.collect()

    # ---- ADVANCED MODELS (5-8) ----

    # Reconstruct full_df for sequence index.
    full_df = pd.concat(
        [train_df, val_df, test_df],
        ignore_index=True,
    )

    print("\n[5/8] Transformer Sequence Model...")
    tf_model, tf_index, tf_t = train_transformer(
        train_df,
        val_df,
        full_df,
        cfg,
    )
    tf_p = predict_transformer(
        tf_model,
        tf_index,
        test_df,
        full_df,
        cfg,
    )
    all_results.append(
        compute_pitch_type_metrics(
            "Transformer",
            y_test,
            tf_p.pitch_type_proba,
            tf_t,
        )
    )
    print(f"  done in {tf_t:.1f}s")
    del tf_p
    gc.collect()

    print("\n[6/8] Hybrid (Transformer + LightGBM)...")
    hy_booster, hy_t = train_hybrid(
        train_df,
        val_df,
        full_df,
        tf_model,
        tf_index,
        cfg,
    )
    hy_p = predict_hybrid(
        hy_booster,
        tf_model,
        tf_index,
        test_df,
        full_df,
        cfg,
    )
    all_results.append(
        compute_pitch_type_metrics(
            "Hybrid (T+LGBM)",
            y_test,
            hy_p.pitch_type_proba,
            hy_t,
        )
    )
    print(f"  done in {hy_t:.1f}s")
    del hy_booster, hy_p, tf_model, tf_index
    gc.collect()

    print("\n[7/8] Hierarchical Classifier...")
    hi_b, hi_t = train_hierarchical(train_df, val_df, cfg)
    hi_p = predict_hierarchical(hi_b, test_df, cfg)
    all_results.append(
        compute_pitch_type_metrics(
            "Hierarchical",
            y_test,
            hi_p.pitch_type_proba,
            hi_t,
        )
    )
    print(f"  done in {hi_t:.1f}s")
    del hi_b, hi_p
    gc.collect()

    print("\n[8/8] Pitcher Embedding Model...")
    em_m, em_pm, em_bm, em_t = train_embedding_model(
        train_df,
        val_df,
        cfg,
    )
    em_p = predict_embedding_model(
        em_m,
        em_pm,
        em_bm,
        test_df,
        cfg,
    )
    all_results.append(
        compute_pitch_type_metrics(
            "Pitcher Embed",
            y_test,
            em_p.pitch_type_proba,
            em_t,
        )
    )
    print(f"  done in {em_t:.1f}s")
    del em_m, em_p
    gc.collect()

    # ---- RESULTS ----
    _print_table(all_results)

    out_dir = cfg.out_dir
    out_dir.mkdir(parents=True, exist_ok=True)
    _plot_summary(all_results, out_dir)
    _plot_per_class(all_results, out_dir)

    # Save JSON.
    json_out = {
        "schema_version": 1,
        "artifact_name": "pitch_8model_comparison",
        "train_years": list(cfg.train_years),
        "val_years": list(cfg.val_years),
        "test_years": list(cfg.test_years),
        "pitch_type_classes": list(PITCH_TYPE_CLASSES),
        "models": [
            {
                "name": r.name,
                "accuracy": r.accuracy,
                "top2_accuracy": r.top2_accuracy,
                "logloss": r.logloss,
                "calibration_ece": r.calibration_ece,
                "per_class_accuracy": r.per_class_accuracy,
                "confusion_matrix": r.confusion,
                "train_time_sec": r.train_time_sec,
            }
            for r in all_results
        ],
    }
    json_path = out_dir / "pitch_8model_comparison.json"
    json_path.write_text(json.dumps(json_out, indent=2))
    print(f"  wrote {json_path}")


if __name__ == "__main__":
    main()
