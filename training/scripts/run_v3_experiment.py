"""TransformerV3 experiment: enriched tokens + live-game features + temporal weighting.

Compares:
  1. V3 standalone (enriched tokens + live features + pitcher embed)
  2. V3 without temporal weighting (ablation)
  3. V3 Hybrid (V3 embeddings + tabular features + LightGBM)

Usage:
  uv run python training/scripts/run_v3_experiment.py
"""

from __future__ import annotations

import argparse
import gc
import json
from pathlib import Path

import lightgbm  # noqa: F401
import lightgbm as lgb
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
from bullpen_training.pitch_comparison.metrics import (
    PitchTypeMetrics,
    compute_pitch_type_metrics,
)
from bullpen_training.pitch_comparison.transformer_v3 import (
    extract_v3_embeddings,
    predict_transformer_v3,
    train_transformer_v3,
)

COLORS = ["#2563eb", "#059669", "#d97706", "#dc2626"]


def _print_table(results: list[PitchTypeMetrics]) -> None:
    hdr = (
        f"{'Model':<30s} | {'Acc':>7s} {'Top2':>7s} "
        f"{'LogL':>7s} {'ECE':>7s} | {'Time':>7s}"
    )
    print("\n" + "=" * len(hdr))
    print(hdr)
    print("-" * len(hdr))
    for r in results:
        print(
            f"{r.name:<30s} | "
            f"{r.accuracy:>7.4f} {r.top2_accuracy:>7.4f} "
            f"{r.logloss:>7.4f} {r.calibration_ece:>7.4f} | "
            f"{r.train_time_sec:>6.1f}s"
        )
    print("=" * len(hdr))


def _plot(results: list[PitchTypeMetrics], out_dir: Path) -> None:
    names = [r.name for r in results]
    colors = COLORS[: len(results)]
    fig, axes = plt.subplots(1, 4, figsize=(22, 6))
    for ax, (title, vals, higher) in zip(
        axes,
        [
            ("Top-1 Accuracy", [r.accuracy for r in results], True),
            ("Top-2 Accuracy", [r.top2_accuracy for r in results], True),
            ("Log Loss", [r.logloss for r in results], False),
            ("ECE", [r.calibration_ece for r in results], False),
        ],
        strict=True,
    ):
        bars = ax.bar(names, vals, color=colors, alpha=0.85)
        ax.set_title(title, fontsize=11)
        ax.grid(axis="y", alpha=0.3)
        ax.tick_params(axis="x", rotation=25, labelsize=7)
        best = int(np.argmax(vals)) if higher else int(np.argmin(vals))
        bars[best].set_edgecolor("black")
        bars[best].set_linewidth(2)
        for i, v in enumerate(vals):
            ax.text(i, v, f"{v:.4f}", ha="center", va="bottom",
                    fontsize=8)
    fig.suptitle(
        "TransformerV3 — Context-Aware Pitch Prediction (2025)",
        fontsize=14, fontweight="bold",
    )
    fig.tight_layout()
    fig.savefig(out_dir / "v3_comparison.png", dpi=150)
    plt.close(fig)
    print(f"  wrote {out_dir / 'v3_comparison.png'}")


def main() -> None:
    parser = argparse.ArgumentParser(
        description="TransformerV3 experiments.",
    )
    parser.add_argument("--limit", type=int, default=None)
    parser.add_argument(
        "--out-dir", type=Path,
        default=Path("data/eval/pitch_v3"),
    )
    parser.add_argument("--seed", type=int, default=42)
    args = parser.parse_args()

    cfg = ExperimentConfig(
        seed=args.seed, limit=args.limit, out_dir=args.out_dir,
    )
    out_dir = cfg.out_dir
    out_dir.mkdir(parents=True, exist_ok=True)

    print("loading pitch data...")
    raw_df = load_pitch_data(
        season_from=cfg.season_from, season_to=cfg.season_to,
        limit=cfg.limit,
    )
    print(f"  {len(raw_df)} rows")
    print("preparing splits...")
    train_df, val_df, test_df = prepare_datasets(
        raw_df,
        train_years=cfg.train_years,
        val_years=cfg.val_years,
        test_years=cfg.test_years,
    )
    del raw_df
    gc.collect()
    print(
        f"  train: {len(train_df)}  val: {len(val_df)}  "
        f"test: {len(test_df)}"
    )
    if len(test_df) == 0:
        print("ERROR: empty test set.")
        return

    for df in [train_df, val_df, test_df]:
        for col in FEATURE_COLS:
            df[col] = pd.to_numeric(df[col], errors="coerce")

    full_df = pd.concat(
        [train_df, val_df, test_df], ignore_index=True,
    )
    y_test = test_df["pitch_type_int"].values.astype(int)
    results: list[PitchTypeMetrics] = []

    # === 1. V3 with temporal weighting ===
    print("\n[1/3] TransformerV3 (enriched + temporal)...")
    v3_model, v3_index, v3_pm, v3_time = train_transformer_v3(
        train_df, val_df, full_df, cfg,
        variant_name="V3",
        use_temporal_weights=True,
    )
    v3_preds = predict_transformer_v3(
        v3_model, v3_index, v3_pm, full_df, cfg,
    )
    m = compute_pitch_type_metrics(
        "V3 Enriched+Temporal", y_test,
        v3_preds.pitch_type_proba, v3_time,
    )
    results.append(m)
    print(f"  acc={m.accuracy:.4f}  top2={m.top2_accuracy:.4f}")

    # === 2. V3 without temporal weighting (ablation) ===
    print("\n[2/3] TransformerV3 (enriched, no temporal)...")
    v3b_model, v3b_index, v3b_pm, v3b_time = train_transformer_v3(
        train_df, val_df, full_df, cfg,
        variant_name="V3-noTW",
        use_temporal_weights=False,
    )
    v3b_preds = predict_transformer_v3(
        v3b_model, v3b_index, v3b_pm, full_df, cfg,
    )
    m = compute_pitch_type_metrics(
        "V3 Enriched (no TW)", y_test,
        v3b_preds.pitch_type_proba, v3b_time,
    )
    results.append(m)
    print(f"  acc={m.accuracy:.4f}  top2={m.top2_accuracy:.4f}")
    del v3b_model, v3b_index, v3b_preds
    gc.collect()

    # === 3. V3 Hybrid (V3 + LightGBM) ===
    print("\n[3/3] V3 Hybrid (V3 embeddings + LightGBM)...")
    import time as time_mod

    t0 = time_mod.perf_counter()
    train_mask = full_df["season"].isin(cfg.train_years).values
    val_mask = full_df["season"].isin(cfg.val_years).values
    test_mask = full_df["season"].isin(cfg.test_years).values
    train_idx = np.where(train_mask)[0].astype(np.int32)
    val_idx = np.where(val_mask)[0].astype(np.int32)
    test_idx = np.where(test_mask)[0].astype(np.int32)

    print("  extracting V3 train embeddings...", flush=True)
    train_emb = extract_v3_embeddings(
        v3_model, v3_index, v3_pm, full_df, train_idx, cfg,
    )
    print("  extracting V3 val embeddings...", flush=True)
    val_emb = extract_v3_embeddings(
        v3_model, v3_index, v3_pm, full_df, val_idx, cfg,
    )
    print("  extracting V3 test embeddings...", flush=True)
    test_emb = extract_v3_embeddings(
        v3_model, v3_index, v3_pm, full_df, test_idx, cfg,
    )

    feat = list(FEATURE_COLS)
    train_x = np.hstack([
        train_emb, train_df[feat].values.astype(np.float32),
    ])
    val_x = np.hstack([
        val_emb, val_df[feat].values.astype(np.float32),
    ])
    test_x = np.hstack([
        test_emb, test_df[feat].values.astype(np.float32),
    ])
    train_y = train_df["pitch_type_int"].values.astype(int)
    val_y = val_df["pitch_type_int"].values.astype(int)

    print(f"  hybrid V3 features: {train_x.shape[1]}", flush=True)
    params = {
        "objective": "multiclass",
        "num_class": len(PITCH_TYPE_CLASSES),
        "metric": "multi_logloss",
        "learning_rate": 0.05,
        "num_leaves": 63,
        "seed": cfg.seed,
        "deterministic": True,
        "force_row_wise": True,
        "verbose": -1,
    }
    dt = lgb.Dataset(train_x, label=train_y)
    dv = lgb.Dataset(val_x, label=val_y, reference=dt)
    booster = lgb.train(
        params, dt, 2000,
        valid_sets=[dt, dv], valid_names=["t", "v"],
        callbacks=[
            lgb.early_stopping(50, first_metric_only=True, verbose=False),
        ],
    )
    hy_proba = np.asarray(
        booster.predict(test_x), dtype=np.float32,
    )
    hy_time = v3_time + (time_mod.perf_counter() - t0)
    m = compute_pitch_type_metrics(
        "V3 Hybrid (V3+LGBM)", y_test, hy_proba, hy_time,
    )
    results.append(m)
    print(f"  acc={m.accuracy:.4f}  top2={m.top2_accuracy:.4f}")

    # === Results ===
    _print_table(results)
    _plot(results, out_dir)

    json_out = {
        "schema_version": 1,
        "artifact_name": "v3_experiment",
        "models": [
            {
                "name": r.name,
                "accuracy": r.accuracy,
                "top2_accuracy": r.top2_accuracy,
                "logloss": r.logloss,
                "calibration_ece": r.calibration_ece,
                "train_time_sec": r.train_time_sec,
            }
            for r in results
        ],
    }
    (out_dir / "v3_experiment.json").write_text(
        json.dumps(json_out, indent=2),
    )
    print(f"  wrote {out_dir / 'v3_experiment.json'}")


if __name__ == "__main__":
    main()
