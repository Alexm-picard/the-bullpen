"""Four-way pitch model comparison: LightGBM vs MLP vs XGBoost vs Logistic/Ridge.

Trains all 4 architectures on 2015-2023 data, validates on 2024,
tests on 2025, evaluates all 4 prediction targets, and produces
matplotlib charts + a results table.

Usage:
  uv run python training/scripts/compare_pitch_models.py
  uv run python training/scripts/compare_pitch_models.py --limit 100000
"""

from __future__ import annotations

import argparse
import json
from dataclasses import dataclass
from pathlib import Path

import matplotlib.pyplot as plt
import numpy as np
import pandas as pd
from sklearn.metrics import accuracy_score, log_loss, mean_absolute_error

from bullpen_training.pitch_comparison.data import (
    FEATURE_COLS,
    OUTCOME_CLASSES,
    PITCH_TYPE_CLASSES,
    load_pitch_data,
    prepare_datasets,
)
from bullpen_training.pitch_comparison.models import (
    PredictionBundle,
    predict_baseline,
    predict_lgbm,
    predict_mlp,
    predict_xgb,
    train_baseline,
    train_lgbm,
    train_mlp,
    train_xgb,
)

COLORS = {
    "LightGBM": "#2563eb",
    "Multi-task MLP": "#d97706",
    "XGBoost": "#059669",
    "Logistic/Ridge": "#dc2626",
}


@dataclass
class ModelMetrics:
    name: str
    pt_accuracy: float
    pt_logloss: float
    velo_mae: float
    velo_rmse: float
    outcome_accuracy: float
    outcome_logloss: float
    ab_mae: float
    ab_rmse: float
    train_time_sec: float


def _evaluate(
    name: str,
    preds: PredictionBundle,
    df: pd.DataFrame,
    train_time: float,
) -> ModelMetrics:
    y_pt = df["pitch_type_int"].values
    y_velo = df["release_speed_mph"].values.astype(np.float32)
    y_out = df["outcome_int"].values
    y_ab = df["ab_total_pitches"].values.astype(np.float32)

    pt_pred_class = preds.pitch_type_proba.argmax(axis=1)
    out_pred_class = preds.outcome_proba.argmax(axis=1)

    return ModelMetrics(
        name=name,
        pt_accuracy=float(accuracy_score(y_pt, pt_pred_class)),
        pt_logloss=float(log_loss(y_pt, preds.pitch_type_proba,
                                  labels=list(range(len(PITCH_TYPE_CLASSES))))),
        velo_mae=float(mean_absolute_error(y_velo, preds.velocity)),
        velo_rmse=float(np.sqrt(np.mean((y_velo - preds.velocity) ** 2))),
        outcome_accuracy=float(accuracy_score(y_out, out_pred_class)),
        outcome_logloss=float(log_loss(y_out, preds.outcome_proba,
                                       labels=list(range(len(OUTCOME_CLASSES))))),
        ab_mae=float(mean_absolute_error(y_ab, preds.ab_pitch_count)),
        ab_rmse=float(np.sqrt(np.mean((y_ab - preds.ab_pitch_count) ** 2))),
        train_time_sec=train_time,
    )


def _plot_comparison(
    metrics_list: list[ModelMetrics],
    out_dir: Path,
) -> None:
    names = [m.name for m in metrics_list]
    colors = [COLORS.get(n, "#888888") for n in names]

    metric_groups = [
        ("Pitch Type\nAccuracy", [m.pt_accuracy for m in metrics_list],
         "higher", "pitch_type_accuracy"),
        ("Pitch Type\nLog Loss", [m.pt_logloss for m in metrics_list],
         "lower", "pitch_type_logloss"),
        ("Velocity\nMAE (mph)", [m.velo_mae for m in metrics_list],
         "lower", "velocity_mae"),
        ("Velocity\nRMSE (mph)", [m.velo_rmse for m in metrics_list],
         "lower", "velocity_rmse"),
        ("Outcome\nAccuracy", [m.outcome_accuracy for m in metrics_list],
         "higher", "outcome_accuracy"),
        ("Outcome\nLog Loss", [m.outcome_logloss for m in metrics_list],
         "lower", "outcome_logloss"),
        ("AB Pitch Count\nMAE", [m.ab_mae for m in metrics_list],
         "lower", "ab_count_mae"),
        ("Training\nTime (sec)", [m.train_time_sec for m in metrics_list],
         "lower", "train_time"),
    ]

    fig, axes = plt.subplots(2, 4, figsize=(24, 10))
    axes_flat = axes.flatten()

    for idx, (title, vals, direction, _key) in enumerate(metric_groups):
        ax = axes_flat[idx]
        bars = ax.bar(names, vals, color=colors, alpha=0.85)
        ax.set_title(title, fontsize=11)
        ax.grid(axis="y", alpha=0.3)
        ax.tick_params(axis="x", rotation=20, labelsize=8)

        best_idx = (
            int(np.argmax(vals)) if direction == "higher"
            else int(np.argmin(vals))
        )
        bars[best_idx].set_edgecolor("black")
        bars[best_idx].set_linewidth(2)

        for i, v in enumerate(vals):
            fmt = f"{v:.3f}" if v < 100 else f"{v:.0f}"
            ax.text(i, v, fmt, ha="center", va="bottom", fontsize=8)

    fig.suptitle(
        "Four-Way Pitch Model Comparison (test on 2025 holdout)",
        fontsize=15, fontweight="bold",
    )
    fig.tight_layout()
    fig.savefig(out_dir / "pitch_comparison_summary.png", dpi=150)
    plt.close(fig)
    print(f"  wrote {out_dir / 'pitch_comparison_summary.png'}")


def _plot_per_target_bars(
    metrics_list: list[ModelMetrics],
    out_dir: Path,
) -> None:
    """One chart per prediction target with primary + secondary metric."""
    names = [m.name for m in metrics_list]
    colors = [COLORS.get(n, "#888") for n in names]

    targets = [
        ("Pitch Type Prediction",
         "Accuracy", [m.pt_accuracy for m in metrics_list], True,
         "Log Loss", [m.pt_logloss for m in metrics_list], False,
         "pitch_type_detail.png"),
        ("Velocity Prediction",
         "MAE (mph)", [m.velo_mae for m in metrics_list], False,
         "RMSE (mph)", [m.velo_rmse for m in metrics_list], False,
         "velocity_detail.png"),
        ("Pitch Outcome Prediction",
         "Accuracy", [m.outcome_accuracy for m in metrics_list], True,
         "Log Loss", [m.outcome_logloss for m in metrics_list], False,
         "outcome_detail.png"),
        ("AB Pitch Count Prediction",
         "MAE (pitches)", [m.ab_mae for m in metrics_list], False,
         "RMSE (pitches)", [m.ab_rmse for m in metrics_list], False,
         "ab_count_detail.png"),
    ]

    for title, m1_label, m1_vals, m1_higher, m2_label, m2_vals, m2_higher, fname in targets:
        fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(12, 5))

        ax1.bar(names, m1_vals, color=colors, alpha=0.85)
        ax1.set_title(m1_label)
        ax1.grid(axis="y", alpha=0.3)
        ax1.tick_params(axis="x", rotation=15, labelsize=9)
        best1 = int(np.argmax(m1_vals)) if m1_higher else int(np.argmin(m1_vals))
        for i, v in enumerate(m1_vals):
            weight = "bold" if i == best1 else "normal"
            ax1.text(i, v, f"{v:.4f}", ha="center", va="bottom",
                     fontsize=9, fontweight=weight)

        ax2.bar(names, m2_vals, color=colors, alpha=0.85)
        ax2.set_title(m2_label)
        ax2.grid(axis="y", alpha=0.3)
        ax2.tick_params(axis="x", rotation=15, labelsize=9)
        best2 = int(np.argmax(m2_vals)) if m2_higher else int(np.argmin(m2_vals))
        for i, v in enumerate(m2_vals):
            weight = "bold" if i == best2 else "normal"
            ax2.text(i, v, f"{v:.4f}", ha="center", va="bottom",
                     fontsize=9, fontweight=weight)

        fig.suptitle(title, fontsize=13, fontweight="bold")
        fig.tight_layout()
        fig.savefig(out_dir / fname, dpi=150)
        plt.close(fig)
        print(f"  wrote {out_dir / fname}")


def _print_table(metrics_list: list[ModelMetrics]) -> None:
    header = (
        f"{'Model':<20s} | {'PT Acc':>7s} {'PT LL':>7s} | "
        f"{'V MAE':>7s} {'V RMSE':>7s} | "
        f"{'Out Acc':>7s} {'Out LL':>7s} | "
        f"{'AB MAE':>7s} {'AB RMSE':>7s} | "
        f"{'Time':>7s}"
    )
    print("\n" + "=" * len(header))
    print(header)
    print("-" * len(header))
    for m in metrics_list:
        print(
            f"{m.name:<20s} | "
            f"{m.pt_accuracy:>7.4f} {m.pt_logloss:>7.4f} | "
            f"{m.velo_mae:>7.2f} {m.velo_rmse:>7.2f} | "
            f"{m.outcome_accuracy:>7.4f} {m.outcome_logloss:>7.4f} | "
            f"{m.ab_mae:>7.3f} {m.ab_rmse:>7.3f} | "
            f"{m.train_time_sec:>6.1f}s"
        )
    print("=" * len(header))


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Four-way pitch model comparison."
    )
    parser.add_argument("--limit", type=int, default=None)
    parser.add_argument("--out-dir", type=Path, default=Path("data/eval/pitch"))
    parser.add_argument("--seed", type=int, default=42)
    args = parser.parse_args()

    print("loading pitch data from ClickHouse...")
    raw_df = load_pitch_data(
        season_from=2015, season_to=2025, limit=args.limit,
    )
    print(f"  {len(raw_df)} rows loaded")

    print("preparing train/val/test splits...")
    train_years = tuple(range(2015, 2024))
    val_years = (2024,)
    test_years = (2025,)
    train_df, val_df, test_df = prepare_datasets(
        raw_df,
        train_years=train_years,
        val_years=val_years,
        test_years=test_years,
    )
    print(f"  train: {len(train_df)}  val: {len(val_df)}  test: {len(test_df)}")
    if len(test_df) == 0:
        print("ERROR: test set is empty. Use a wider season range or "
              "remove --limit so data covers 2025.")
        return
    if len(val_df) == 0:
        print("WARNING: val set is empty — no early stopping for "
              "boosted models.")

    # Ensure numeric types for all feature columns.
    for df in [train_df, val_df, test_df]:
        for col in FEATURE_COLS:
            df[col] = pd.to_numeric(df[col], errors="coerce")

    results: list[ModelMetrics] = []

    # 1. LightGBM
    print("\n[1/4] training LightGBM ensemble...")
    lgbm_bundle, lgbm_time = train_lgbm(train_df, val_df, seed=args.seed)
    lgbm_preds = predict_lgbm(lgbm_bundle, test_df)
    lgbm_metrics = _evaluate("LightGBM", lgbm_preds, test_df, lgbm_time)
    results.append(lgbm_metrics)
    print(f"  done in {lgbm_time:.1f}s")

    # 2. Multi-task MLP
    print("\n[2/4] training Multi-task MLP...")
    mlp_model, mlp_scaler, mlp_time = train_mlp(
        train_df, val_df, seed=args.seed,
    )
    mlp_preds = predict_mlp(mlp_model, mlp_scaler, test_df)
    mlp_metrics = _evaluate("Multi-task MLP", mlp_preds, test_df, mlp_time)
    results.append(mlp_metrics)
    print(f"  done in {mlp_time:.1f}s")

    # 3. XGBoost
    print("\n[3/4] training XGBoost ensemble...")
    xgb_bundle, xgb_time = train_xgb(train_df, val_df, seed=args.seed)
    xgb_preds = predict_xgb(xgb_bundle, test_df)
    xgb_metrics = _evaluate("XGBoost", xgb_preds, test_df, xgb_time)
    results.append(xgb_metrics)
    print(f"  done in {xgb_time:.1f}s")

    # 4. Logistic / Ridge baseline
    print("\n[4/4] training Logistic/Ridge baseline...")
    bl_bundle, bl_scaler, bl_time = train_baseline(
        train_df, val_df, seed=args.seed,
    )
    bl_preds = predict_baseline(bl_bundle, bl_scaler, test_df)
    bl_metrics = _evaluate("Logistic/Ridge", bl_preds, test_df, bl_time)
    results.append(bl_metrics)
    print(f"  done in {bl_time:.1f}s")

    # Print results table.
    _print_table(results)

    # Plot charts.
    out_dir = args.out_dir
    out_dir.mkdir(parents=True, exist_ok=True)
    _plot_comparison(results, out_dir)
    _plot_per_target_bars(results, out_dir)

    # Save JSON.
    json_out = {
        "schema_version": 1,
        "artifact_name": "pitch_model_comparison",
        "train_years": list(train_years),
        "val_years": list(val_years),
        "test_years": list(test_years),
        "n_train": len(train_df),
        "n_val": len(val_df),
        "n_test": len(test_df),
        "models": [
            {
                "name": m.name,
                "pitch_type_accuracy": m.pt_accuracy,
                "pitch_type_logloss": m.pt_logloss,
                "velocity_mae": m.velo_mae,
                "velocity_rmse": m.velo_rmse,
                "outcome_accuracy": m.outcome_accuracy,
                "outcome_logloss": m.outcome_logloss,
                "ab_count_mae": m.ab_mae,
                "ab_count_rmse": m.ab_rmse,
                "train_time_sec": m.train_time_sec,
            }
            for m in results
        ],
    }
    json_path = out_dir / "pitch_comparison.json"
    json_path.write_text(json.dumps(json_out, indent=2))
    print(f"  wrote {json_path}")


if __name__ == "__main__":
    main()
