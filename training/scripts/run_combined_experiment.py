"""Combined experiment: fuse the two winning models.

Combines the two prior bests into a single architecture:
  - "Hybrid + Context" (45.13%): V2 transformer pitcher embeddings ->
    LightGBM with base + enriched-context features.
  - "Pitcher + Catcher" (44.19%): catcher-aware transformer that adds a
    learned catcher embedding to the sequence representation.

The fusion: train the catcher-aware transformer (pitcher + catcher
embeddings on the pooled sequence), extract the
[pooled | pitcher_emb | catcher_emb] vector, then feed THAT into the
LightGBM meta-model alongside the base + enriched-context features.

Rows produced (all on the same data load / seed, fully comparable):
  1. Hybrid + Context        — pitcher-only emb + base + context (prior best)
  2. Catcher-Hybrid (base)   — catcher-aware emb + base only (ablation)
  3. Catcher-Hybrid + Context — catcher-aware emb + base + context (COMBINED)

Requires the V012/V013 expanded re-pull (catcher_id + context columns).

Usage:
  CLICKHOUSE_PORT=9000 uv run python scripts/run_combined_experiment.py
"""

from __future__ import annotations

import argparse
import gc
import json
import time
from pathlib import Path

import lightgbm as lgb
import matplotlib.pyplot as plt
import numpy as np
import pandas as pd

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
from bullpen_training.pitch_comparison.transformer_catcher import (
    extract_catcher_hybrid_embeddings,
    train_catcher_transformer,
)
from bullpen_training.pitch_comparison.transformer_v2 import (
    train_transformer_v2,
)
from bullpen_training.pitch_comparison.transformer_v2 import (
    _collate_v2,
    _map_ids,
    _SeqWithIdsDataset,
)
import torch
from torch.utils.data import DataLoader


def _extract_v2_embeddings(model, index, pitcher_map, batter_map, full_df, indices, cfg):
    """[pooled | pitcher_emb] — mirrors run_enriched_experiment."""
    device = torch.device(cfg.resolve_device())
    model.to(device).eval()
    all_pid = _map_ids(full_df["pitcher_id"].values, pitcher_map)
    all_bid = _map_ids(full_df["batter_id"].values, batter_map)
    ds = _SeqWithIdsDataset(
        index, indices, all_pid[indices], all_bid[indices], cfg.seq_window,
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


def _train_lgbm(train_x, train_y, val_x, val_y, seed, num_threads=0):
    params = {
        "objective": "multiclass",
        "num_class": len(PITCH_TYPE_CLASSES),
        "metric": "multi_logloss",
        "learning_rate": 0.05, "num_leaves": 63, "seed": seed,
        "deterministic": True, "force_row_wise": True, "verbose": -1,
        "num_threads": num_threads,  # 0 = all cores (cloud default)
    }
    dt = lgb.Dataset(train_x, label=train_y)
    dv = lgb.Dataset(val_x, label=val_y, reference=dt)
    return lgb.train(
        params, dt, 2000, valid_sets=[dt, dv], valid_names=["t", "v"],
        callbacks=[lgb.early_stopping(50, first_metric_only=True, verbose=False)],
    )


def main() -> None:
    parser = argparse.ArgumentParser(description="Combined (catcher + context) experiment.")
    parser.add_argument("--limit", type=int, default=None)
    parser.add_argument("--out-dir", type=Path, default=Path("data/eval/pitch_combined"))
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument(
        "--save-dir", type=Path, default=Path("artifacts/pitch_combined_v1"),
        help="Where to persist trained model weights (.gitignored).",
    )
    parser.add_argument(
        "--no-save", action="store_true",
        help="Skip writing model weights (metrics-only run).",
    )
    args = parser.parse_args()

    cfg = ExperimentConfig(seed=args.seed, limit=args.limit, out_dir=args.out_dir)
    out_dir = cfg.out_dir
    out_dir.mkdir(parents=True, exist_ok=True)
    save_dir = None if args.no_save else args.save_dir
    if save_dir is not None:
        save_dir.mkdir(parents=True, exist_ok=True)

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

    cov = float((test_df["catcher_id"] > 0).mean())
    print(f"  catcher_id coverage in test: {cov:.1%}")

    all_feat = list(FEATURE_COLS) + list(CONTEXT_FEATURE_COLS)
    for df in [train_df, val_df, test_df]:
        for col in all_feat:
            df[col] = pd.to_numeric(df[col], errors="coerce")

    full_df = pd.concat([train_df, val_df, test_df], ignore_index=True)
    y_test = test_df["pitch_type_int"].values.astype(int)
    train_y = train_df["pitch_type_int"].values.astype(int)
    val_y = val_df["pitch_type_int"].values.astype(int)

    train_idx = np.where(full_df["season"].isin(cfg.train_years).values)[0].astype(np.int32)
    val_idx = np.where(full_df["season"].isin(cfg.val_years).values)[0].astype(np.int32)
    test_idx = np.where(full_df["season"].isin(cfg.test_years).values)[0].astype(np.int32)

    base_feat = list(FEATURE_COLS)
    results: list[PitchTypeMetrics] = []

    # ============================================================
    # Baseline reproduction: Hybrid + Context (pitcher-only emb).
    # ============================================================
    print("\n[A] training V2 transformer (pitcher embeddings only)...")
    v2_model, v2_index, v2_pm, v2_bm, v2_time = train_transformer_v2(
        train_df, val_df, full_df, cfg, use_batter_embed=False, variant_name="V2",
    )
    print("extracting V2 embeddings...")
    v2_train = _extract_v2_embeddings(v2_model, v2_index, v2_pm, v2_bm, full_df, train_idx, cfg)
    v2_val = _extract_v2_embeddings(v2_model, v2_index, v2_pm, v2_bm, full_df, val_idx, cfg)
    v2_test = _extract_v2_embeddings(v2_model, v2_index, v2_pm, v2_bm, full_df, test_idx, cfg)

    print("\n[1] Hybrid + Context (pitcher emb + base + context)...")
    t0 = time.perf_counter()
    x_tr = np.hstack([v2_train, train_df[all_feat].values.astype(np.float32)])
    x_va = np.hstack([v2_val, val_df[all_feat].values.astype(np.float32)])
    x_te = np.hstack([v2_test, test_df[all_feat].values.astype(np.float32)])
    booster = _train_lgbm(x_tr, train_y, x_va, val_y, cfg.seed, cfg.lgbm_num_threads)
    proba = np.asarray(booster.predict(x_te), dtype=np.float32)
    results.append(compute_pitch_type_metrics(
        "Hybrid + Context", y_test, proba, v2_time + (time.perf_counter() - t0),
    ))
    print(f"  acc={results[-1].accuracy:.4f}  top2={results[-1].top2_accuracy:.4f}")
    if save_dir is not None:
        torch.save(v2_model.state_dict(), save_dir / "v2_transformer.pt")
        booster.save_model(str(save_dir / "hybrid_context_lgbm.txt"))
        (save_dir / "v2_id_maps.json").write_text(
            json.dumps({"pitcher_map": v2_pm, "batter_map": v2_bm})
        )
        print(f"  saved V2 transformer + Hybrid+Context booster -> {save_dir}")
    del v2_model, v2_index, v2_train, v2_val, v2_test
    gc.collect()

    # ============================================================
    # Combined model: catcher-aware emb -> LightGBM + context.
    # ============================================================
    print("\n[B] training catcher-aware transformer (pitcher + catcher embeddings)...")
    c_model, c_index, c_pm, c_cm, c_time = train_catcher_transformer(
        train_df, val_df, full_df, cfg, use_catcher=True, variant_name="Catcher",
    )
    print("extracting catcher-hybrid embeddings...")
    c_train = extract_catcher_hybrid_embeddings(c_model, c_index, c_pm, c_cm, full_df, train_idx, cfg)
    c_val = extract_catcher_hybrid_embeddings(c_model, c_index, c_pm, c_cm, full_df, val_idx, cfg)
    c_test = extract_catcher_hybrid_embeddings(c_model, c_index, c_pm, c_cm, full_df, test_idx, cfg)

    # Ablation: catcher emb + base only (no enriched context).
    print("\n[2] Catcher-Hybrid (base) — catcher emb + base features...")
    t0 = time.perf_counter()
    x_tr = np.hstack([c_train, train_df[base_feat].values.astype(np.float32)])
    x_va = np.hstack([c_val, val_df[base_feat].values.astype(np.float32)])
    x_te = np.hstack([c_test, test_df[base_feat].values.astype(np.float32)])
    booster = _train_lgbm(x_tr, train_y, x_va, val_y, cfg.seed, cfg.lgbm_num_threads)
    proba = np.asarray(booster.predict(x_te), dtype=np.float32)
    results.append(compute_pitch_type_metrics(
        "Catcher-Hybrid (base)", y_test, proba, c_time + (time.perf_counter() - t0),
    ))
    print(f"  acc={results[-1].accuracy:.4f}  top2={results[-1].top2_accuracy:.4f}")

    # The combination: catcher emb + base + enriched context.
    print("\n[3] Catcher-Hybrid + Context (COMBINED)...")
    t0 = time.perf_counter()
    x_tr = np.hstack([c_train, train_df[all_feat].values.astype(np.float32)])
    x_va = np.hstack([c_val, val_df[all_feat].values.astype(np.float32)])
    x_te = np.hstack([c_test, test_df[all_feat].values.astype(np.float32)])
    combined_booster = _train_lgbm(x_tr, train_y, x_va, val_y, cfg.seed, cfg.lgbm_num_threads)
    proba = np.asarray(combined_booster.predict(x_te), dtype=np.float32)
    results.append(compute_pitch_type_metrics(
        "Catcher-Hybrid + Context", y_test, proba, c_time + (time.perf_counter() - t0),
    ))
    print(f"  acc={results[-1].accuracy:.4f}  top2={results[-1].top2_accuracy:.4f}")

    # Context feature importance within the combined model.
    feat_names = [f"emb_{i}" for i in range(c_train.shape[1])] + list(all_feat)
    importances = combined_booster.feature_importance(importance_type="gain")
    ctx_importance = {
        name: float(imp)
        for name, imp in zip(feat_names, importances, strict=True)
        if name in CONTEXT_FEATURE_COLS
    }

    # ============================================================
    # Results.
    # ============================================================
    hdr = f"{'Model':<26s} | {'Acc':>7s} {'Top2':>7s} {'LogL':>7s} {'ECE':>7s}"
    print("\n" + "=" * len(hdr))
    print(hdr)
    print("-" * len(hdr))
    for r in results:
        print(
            f"{r.name:<26s} | {r.accuracy:>7.4f} {r.top2_accuracy:>7.4f} "
            f"{r.logloss:>7.4f} {r.calibration_ece:>7.4f}"
        )
    print("=" * len(hdr))

    base_acc = results[0].accuracy
    combined_acc = results[2].accuracy
    print(
        f"\nCombined vs Hybrid+Context: {combined_acc - base_acc:+.4f} "
        f"({'fusion helps' if combined_acc - base_acc > 0.0005 else 'no material gain'})"
    )
    print("\nContext feature importance in combined model (gain):")
    for name, imp in sorted(ctx_importance.items(), key=lambda x: -x[1]):
        print(f"  {name:<28s} {imp:>12.0f}")

    # Plot.
    names = [r.name for r in results]
    fig, axes = plt.subplots(1, 4, figsize=(22, 5))
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
        bars = ax.bar(names, vals, color=["#0891b2", "#7c3aed", "#059669"], alpha=0.85)
        ax.set_title(title)
        ax.grid(axis="y", alpha=0.3)
        ax.tick_params(axis="x", rotation=20, labelsize=8)
        best = int(np.argmax(vals)) if higher else int(np.argmin(vals))
        bars[best].set_edgecolor("black")
        bars[best].set_linewidth(2)
        for i, v in enumerate(vals):
            ax.text(i, v, f"{v:.4f}", ha="center", va="bottom", fontsize=8)
    fig.suptitle(
        "Combined Model: Catcher Embeddings + Enriched Context (2025 holdout)",
        fontweight="bold",
    )
    fig.tight_layout()
    fig.savefig(out_dir / "combined_comparison.png", dpi=150)
    plt.close(fig)
    print(f"  wrote {out_dir / 'combined_comparison.png'}")

    json_out = {
        "schema_version": 1,
        "artifact_name": "combined_experiment",
        "catcher_coverage_test": cov,
        "combined_vs_hybrid_context_delta": float(combined_acc - base_acc),
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
    (out_dir / "combined_experiment.json").write_text(json.dumps(json_out, indent=2))
    print(f"  wrote {out_dir / 'combined_experiment.json'}")

    # ---- Persist trained models so future experiments don't retrain ----
    if save_dir is not None:
        torch.save(c_model.state_dict(), save_dir / "catcher_transformer.pt")
        combined_booster.save_model(str(save_dir / "catcher_context_lgbm.txt"))
        booster.save_model(str(save_dir / "catcher_base_lgbm.txt"))
        (save_dir / "catcher_id_maps.json").write_text(
            json.dumps({"pitcher_map": c_pm, "catcher_map": c_cm})
        )
        # Reload metadata — enough to reconstruct each model and the feature
        # layout without retraining. Architecture dims read off the live model.
        meta = {
            "artifact_name": "pitch_combined_v1",
            "trained": "2026-05-28",
            "seed": cfg.seed,
            "seasons": {"train": cfg.train_years, "val": cfg.val_years, "test": cfg.test_years},
            "pitch_type_classes": list(PITCH_TYPE_CLASSES),
            "catcher_transformer": {
                "class": "CatcherAwareTransformer",
                "raw_token_dim": int(c_model.token_proj.in_features),
                "d_model": int(c_model.d_model),
                "nhead": cfg.nhead,
                "num_layers": cfg.num_encoder_layers,
                "dim_feedforward": cfg.dim_feedforward,
                "dropout": cfg.transformer_dropout,
                "pitcher_embed_dim": int(c_model.pitcher_emb.embedding_dim),
                "catcher_embed_dim": int(c_model.catcher_emb.embedding_dim),
                "n_pitchers": int(c_model.pitcher_emb.num_embeddings),
                "n_catchers": int(c_model.catcher_emb.num_embeddings),
                "seq_window": cfg.seq_window,
                "weights": "catcher_transformer.pt",
                "id_maps": "catcher_id_maps.json",
            },
            "boosters": {
                "catcher_context_lgbm.txt": {
                    "features": "[catcher-hybrid emb] + " + str(list(all_feat)),
                    "metrics": next(
                        (m for m in json_out["models"] if m["name"] == "Catcher-Hybrid + Context"), None
                    ),
                },
                "catcher_base_lgbm.txt": {
                    "features": "[catcher-hybrid emb] + " + str(list(base_feat)),
                    "metrics": next(
                        (m for m in json_out["models"] if m["name"] == "Catcher-Hybrid (base)"), None
                    ),
                },
                "hybrid_context_lgbm.txt": {
                    "features": "[V2 pooled|pitcher emb] + " + str(list(all_feat)),
                    "metrics": next(
                        (m for m in json_out["models"] if m["name"] == "Hybrid + Context"), None
                    ),
                },
            },
            "results": json_out["models"],
        }
        (save_dir / "metadata.json").write_text(json.dumps(meta, indent=2))
        print(f"  saved catcher transformer + boosters + metadata -> {save_dir}")


if __name__ == "__main__":
    main()
