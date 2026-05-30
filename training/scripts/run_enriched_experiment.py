"""Enriched-context experiment: does catcher/fatigue/leverage context help?

Compares the V2 hybrid (transformer + pitcher embed + LightGBM) with:
  1. base features only (current best, 45.03%)
  2. + context features (times-through-order, live score, win exp,
     times-faced-today, at-bat-number, pitcher biomech averages)

Uses the V012/V013 expanded Statcast columns. Requires the expanded
re-pull to have completed.

Usage:
  uv run python training/scripts/run_enriched_experiment.py
"""

from __future__ import annotations

import argparse
import gc
import json
import time
from pathlib import Path

import lightgbm  # noqa: F401
import lightgbm as lgb
import matplotlib.pyplot as plt
import numpy as np
import torch
from torch.utils.data import DataLoader

from bullpen_training.pitch_comparison.config import ExperimentConfig
from bullpen_training.pitch_comparison.data import (
    FEATURE_COLS,
    PITCH_TYPE_CLASSES,
)
from bullpen_training.pitch_comparison.data_enriched import (
    CONTEXT_FEATURE_COLS,
    load_enriched_data,
    prepare_enriched_datasets,
)
from bullpen_training.pitch_comparison.metrics import (
    PitchTypeMetrics,
    compute_pitch_type_metrics,
)
from bullpen_training.pitch_comparison.transformer_v2 import (
    _collate_v2,
    _map_ids,
    _SeqWithIdsDataset,
    train_transformer_v2,
)


def _extract_v2_embeddings(model, index, pitcher_map, batter_map, full_df, indices, cfg):
    device = torch.device(cfg.resolve_device())
    model.to(device).eval()
    all_pid = _map_ids(full_df["pitcher_id"].values, pitcher_map)
    all_bid = _map_ids(full_df["batter_id"].values, batter_map)
    ds = _SeqWithIdsDataset(
        index, indices, all_pid[indices], all_bid[indices],
        cfg.seq_window,
    )
    loader = DataLoader(
        ds, batch_size=cfg.transformer_batch_size * 2,
        shuffle=False, collate_fn=_collate_v2, num_workers=0,
    )
    embs = []
    with torch.no_grad():
        for seq, pad_mask, pids, _bids, _t in loader:
            seq = seq.to(device)
            pad_mask = pad_mask.to(device)
            pooled = model.encode(seq, pad_mask)
            p_emb = model.pitcher_emb(pids.to(device))
            embs.append(torch.cat([pooled, p_emb], dim=-1).cpu().numpy())
    return np.concatenate(embs, axis=0).astype(np.float32)


def _train_lgbm(train_x, train_y, val_x, val_y, seed):
    params = {
        "objective": "multiclass",
        "num_class": len(PITCH_TYPE_CLASSES),
        "metric": "multi_logloss",
        "learning_rate": 0.05, "num_leaves": 63, "seed": seed,
        "deterministic": True, "force_row_wise": True, "verbose": -1,
    }
    dt = lgb.Dataset(train_x, label=train_y)
    dv = lgb.Dataset(val_x, label=val_y, reference=dt)
    return lgb.train(
        params, dt, 2000, valid_sets=[dt, dv], valid_names=["t", "v"],
        callbacks=[lgb.early_stopping(50, first_metric_only=True, verbose=False)],
    )


def main() -> None:
    parser = argparse.ArgumentParser(description="Enriched-context experiment.")
    parser.add_argument("--limit", type=int, default=None)
    parser.add_argument("--out-dir", type=Path, default=Path("data/eval/pitch_enriched"))
    parser.add_argument("--seed", type=int, default=42)
    args = parser.parse_args()

    cfg = ExperimentConfig(seed=args.seed, limit=args.limit, out_dir=args.out_dir)
    out_dir = cfg.out_dir
    out_dir.mkdir(parents=True, exist_ok=True)

    print("loading enriched data...")
    raw_df = load_enriched_data(
        season_from=cfg.season_from, season_to=cfg.season_to, limit=cfg.limit,
    )
    print(f"  {len(raw_df)} rows")
    print("preparing enriched splits...")
    train_df, val_df, test_df = prepare_enriched_datasets(
        raw_df,
        train_years=cfg.train_years,
        val_years=cfg.val_years,
        test_years=cfg.test_years,
    )
    del raw_df
    gc.collect()
    print(f"  train: {len(train_df)}  val: {len(val_df)}  test: {len(test_df)}")
    if len(test_df) == 0:
        print("ERROR: empty test set.")
        return

    import pandas as pd
    all_feat = list(FEATURE_COLS) + list(CONTEXT_FEATURE_COLS)
    for df in [train_df, val_df, test_df]:
        for col in all_feat:
            df[col] = pd.to_numeric(df[col], errors="coerce")

    full_df = pd.concat([train_df, val_df, test_df], ignore_index=True)
    y_test = test_df["pitch_type_int"].values.astype(int)
    train_y = train_df["pitch_type_int"].values.astype(int)
    val_y = val_df["pitch_type_int"].values.astype(int)
    results: list[PitchTypeMetrics] = []

    # Train the V2 transformer (pitcher embeddings) once.
    print("\ntraining V2 transformer (pitcher embeddings)...")
    v2_model, v2_index, v2_pm, v2_bm, v2_time = train_transformer_v2(
        train_df, val_df, full_df, cfg, use_batter_embed=False,
        variant_name="V2",
    )

    train_idx = np.where(full_df["season"].isin(cfg.train_years).values)[0].astype(np.int32)
    val_idx = np.where(full_df["season"].isin(cfg.val_years).values)[0].astype(np.int32)
    test_idx = np.where(full_df["season"].isin(cfg.test_years).values)[0].astype(np.int32)

    print("extracting embeddings...")
    train_emb = _extract_v2_embeddings(v2_model, v2_index, v2_pm, v2_bm, full_df, train_idx, cfg)
    val_emb = _extract_v2_embeddings(v2_model, v2_index, v2_pm, v2_bm, full_df, val_idx, cfg)
    test_emb = _extract_v2_embeddings(v2_model, v2_index, v2_pm, v2_bm, full_df, test_idx, cfg)

    # === 1. Hybrid base (no context) ===
    print("\n[1] Hybrid base (embeddings + base features)...")
    t0 = time.perf_counter()
    feat = list(FEATURE_COLS)
    base_train = np.hstack([train_emb, train_df[feat].values.astype(np.float32)])
    base_val = np.hstack([val_emb, val_df[feat].values.astype(np.float32)])
    base_test = np.hstack([test_emb, test_df[feat].values.astype(np.float32)])
    b_booster = _train_lgbm(base_train, train_y, base_val, val_y, cfg.seed)
    b_proba = np.asarray(b_booster.predict(base_test), dtype=np.float32)
    m = compute_pitch_type_metrics(
        "Hybrid (base)", y_test, b_proba, v2_time + (time.perf_counter() - t0),
    )
    results.append(m)
    print(f"  acc={m.accuracy:.4f}  top2={m.top2_accuracy:.4f}")

    # === 2. Hybrid + context features ===
    print("\n[2] Hybrid + enriched context...")
    t0 = time.perf_counter()
    ctx_train = np.hstack([train_emb, train_df[all_feat].values.astype(np.float32)])
    ctx_val = np.hstack([val_emb, val_df[all_feat].values.astype(np.float32)])
    ctx_test = np.hstack([test_emb, test_df[all_feat].values.astype(np.float32)])
    c_booster = _train_lgbm(ctx_train, train_y, ctx_val, val_y, cfg.seed)
    c_proba = np.asarray(c_booster.predict(ctx_test), dtype=np.float32)
    m = compute_pitch_type_metrics(
        "Hybrid + Context", y_test, c_proba, v2_time + (time.perf_counter() - t0),
    )
    results.append(m)
    print(f"  acc={m.accuracy:.4f}  top2={m.top2_accuracy:.4f}")

    # === Feature importance of context features ===
    feat_names = (
        [f"emb_{i}" for i in range(train_emb.shape[1])]
        + list(all_feat)
    )
    importances = c_booster.feature_importance(importance_type="gain")
    ctx_importance = {
        name: float(imp)
        for name, imp in zip(feat_names, importances, strict=True)
        if name in CONTEXT_FEATURE_COLS
    }

    # === Results ===
    hdr = f"{'Model':<22s} | {'Acc':>7s} {'Top2':>7s} {'LogL':>7s} {'ECE':>7s}"
    print("\n" + "=" * len(hdr))
    print(hdr)
    print("-" * len(hdr))
    for r in results:
        print(
            f"{r.name:<22s} | {r.accuracy:>7.4f} {r.top2_accuracy:>7.4f} "
            f"{r.logloss:>7.4f} {r.calibration_ece:>7.4f}"
        )
    print("=" * len(hdr))

    print("\nContext feature importance (gain):")
    for name, imp in sorted(ctx_importance.items(), key=lambda x: -x[1]):
        print(f"  {name:<28s} {imp:>12.0f}")

    # Plot.
    names = [r.name for r in results]
    fig, axes = plt.subplots(1, 4, figsize=(20, 5))
    for ax, (title, vals, higher) in zip(
        axes,
        [
            ("Top-1 Acc", [r.accuracy for r in results], True),
            ("Top-2 Acc", [r.top2_accuracy for r in results], True),
            ("Log Loss", [r.logloss for r in results], False),
            ("ECE", [r.calibration_ece for r in results], False),
        ],
        strict=True,
    ):
        bars = ax.bar(names, vals, color=["#0891b2", "#059669"], alpha=0.85)
        ax.set_title(title)
        ax.grid(axis="y", alpha=0.3)
        ax.tick_params(axis="x", rotation=15, labelsize=9)
        best = int(np.argmax(vals)) if higher else int(np.argmin(vals))
        bars[best].set_edgecolor("black")
        bars[best].set_linewidth(2)
        for i, v in enumerate(vals):
            ax.text(i, v, f"{v:.4f}", ha="center", va="bottom", fontsize=9)
    fig.suptitle("Enriched Context Experiment (2025 holdout)", fontweight="bold")
    fig.tight_layout()
    fig.savefig(out_dir / "enriched_comparison.png", dpi=150)
    plt.close(fig)
    print(f"  wrote {out_dir / 'enriched_comparison.png'}")

    json_out = {
        "schema_version": 1,
        "artifact_name": "enriched_experiment",
        "models": [
            {
                "name": r.name, "accuracy": r.accuracy,
                "top2_accuracy": r.top2_accuracy, "logloss": r.logloss,
                "calibration_ece": r.calibration_ece,
            }
            for r in results
        ],
        "context_feature_importance": ctx_importance,
    }
    (out_dir / "enriched_experiment.json").write_text(
        json.dumps(json_out, indent=2),
    )
    print(f"  wrote {out_dir / 'enriched_experiment.json'}")


if __name__ == "__main__":
    main()
