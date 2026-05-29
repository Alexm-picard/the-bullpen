"""Deep pitch prediction research: attention analysis + enhanced transformers.

Trains TransformerV2 variants (with pitcher/batter embeddings),
extracts attention patterns from the trained models, and generates
research-quality visualizations.

Usage:
  uv run python training/scripts/run_pitch_research.py
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

from bullpen_training.pitch_comparison.analysis import (
    extract_attention_weights,
    plot_attention_heatmap,
    plot_calibration_curve,
    plot_confidence_histogram,
    plot_entropy_analysis,
    plot_temporal_attention_decay,
    plot_topk_curve,
)
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
from bullpen_training.pitch_comparison.sequence_data import (
    PitcherSequenceIndex,
)
from bullpen_training.pitch_comparison.transformer_v2 import (
    TransformerV2,
    extract_embeddings_for_viz,
    predict_transformer_v2,
    train_transformer_v2,
)

COLORS = {
    "Transformer (base)": "#7c3aed",
    "Hybrid (base)": "#0891b2",
    "T+Pitcher Embed": "#2563eb",
    "T+Pitcher+Batter": "#059669",
    "Hybrid+Pitcher": "#d97706",
    "Hybrid+P+B": "#dc2626",
}


def _train_hybrid_from_v2(
    model: TransformerV2,
    index: PitcherSequenceIndex,
    pitcher_map: dict[int, int],
    batter_map: dict[int, int],
    train_df: pd.DataFrame,
    val_df: pd.DataFrame,
    full_df: pd.DataFrame,
    config: ExperimentConfig,
) -> tuple[lgb.Booster, float]:
    """Extract V2 embeddings and train LightGBM meta-model."""
    import time

    import torch
    from torch.utils.data import DataLoader

    from bullpen_training.pitch_comparison.transformer_v2 import (
        _collate_v2,
        _map_ids,
        _SeqWithIdsDataset,
    )

    device = torch.device(config.resolve_device())
    model.to(device).eval()
    t0 = time.perf_counter()

    def _extract(indices, pid_mapped, bid_mapped):
        ds = _SeqWithIdsDataset(
            index, indices, pid_mapped, bid_mapped, config.seq_window,
        )
        loader = DataLoader(
            ds, batch_size=config.transformer_batch_size * 2,
            shuffle=False, collate_fn=_collate_v2, num_workers=0,
        )
        embs = []
        with torch.no_grad():
            for seq, pad_mask, pids, _bids, _t in loader:
                seq = seq.to(device)
                pad_mask = pad_mask.to(device)
                pooled = model.encode(seq, pad_mask)
                p_emb = model.pitcher_emb(pids.to(device))
                combined = torch.cat([pooled, p_emb], dim=-1)
                embs.append(combined.cpu().numpy())
        return np.concatenate(embs, axis=0).astype(np.float32)

    all_pid = _map_ids(full_df["pitcher_id"].values, pitcher_map)
    all_bid = _map_ids(full_df["batter_id"].values, batter_map)

    train_idx = np.where(
        full_df["season"].isin(config.train_years).values,
    )[0].astype(np.int32)
    val_idx = np.where(
        full_df["season"].isin(config.val_years).values,
    )[0].astype(np.int32)

    print("  extracting V2 train embeddings...", flush=True)
    train_emb = _extract(train_idx, all_pid[train_idx], all_bid[train_idx])
    print("  extracting V2 val embeddings...", flush=True)
    val_emb = _extract(val_idx, all_pid[val_idx], all_bid[val_idx])

    feat = list(FEATURE_COLS)
    train_x = np.hstack([
        train_emb, train_df[feat].values.astype(np.float32),
    ])
    val_x = np.hstack([
        val_emb, val_df[feat].values.astype(np.float32),
    ])
    train_y = train_df["pitch_type_int"].values.astype(int)
    val_y = val_df["pitch_type_int"].values.astype(int)

    print(f"  hybrid features: {train_x.shape[1]}", flush=True)
    params = {
        "objective": "multiclass",
        "num_class": len(PITCH_TYPE_CLASSES),
        "metric": "multi_logloss",
        "learning_rate": 0.05,
        "num_leaves": 63,
        "seed": config.seed,
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
    elapsed = time.perf_counter() - t0
    return booster, elapsed


def _predict_hybrid_v2(
    booster: lgb.Booster,
    model: TransformerV2,
    index: PitcherSequenceIndex,
    pitcher_map: dict[int, int],
    batter_map: dict[int, int],
    test_df: pd.DataFrame,
    full_df: pd.DataFrame,
    config: ExperimentConfig,
) -> np.ndarray:
    import torch
    from torch.utils.data import DataLoader

    from bullpen_training.pitch_comparison.transformer_v2 import (
        _collate_v2,
        _map_ids,
        _SeqWithIdsDataset,
    )

    device = torch.device(config.resolve_device())
    model.to(device).eval()
    test_idx = np.where(
        full_df["season"].isin(config.test_years).values,
    )[0].astype(np.int32)
    all_pid = _map_ids(full_df["pitcher_id"].values, pitcher_map)
    all_bid = _map_ids(full_df["batter_id"].values, batter_map)

    ds = _SeqWithIdsDataset(
        index, test_idx, all_pid[test_idx], all_bid[test_idx],
        config.seq_window,
    )
    loader = DataLoader(
        ds, batch_size=config.transformer_batch_size * 2,
        shuffle=False, collate_fn=_collate_v2, num_workers=0,
    )
    embs = []
    with torch.no_grad():
        for seq, pad_mask, pids, _bids, _t in loader:
            seq = seq.to(device)
            pad_mask = pad_mask.to(device)
            pooled = model.encode(seq, pad_mask)
            p_emb = model.pitcher_emb(pids.to(device))
            combined = torch.cat([pooled, p_emb], dim=-1)
            embs.append(combined.cpu().numpy())
    test_emb = np.concatenate(embs, axis=0).astype(np.float32)

    feat = list(FEATURE_COLS)
    test_tab = test_df[feat].values.astype(np.float32)
    test_x = np.hstack([test_emb, test_tab])
    return np.asarray(booster.predict(test_x), dtype=np.float32)


def _print_table(results: list[PitchTypeMetrics]) -> None:
    hdr = (
        f"{'Model':<25s} | {'Acc':>7s} {'Top2':>7s} "
        f"{'LogL':>7s} {'ECE':>7s} | {'Time':>7s}"
    )
    print("\n" + "=" * len(hdr))
    print(hdr)
    print("-" * len(hdr))
    for r in results:
        print(
            f"{r.name:<25s} | "
            f"{r.accuracy:>7.4f} {r.top2_accuracy:>7.4f} "
            f"{r.logloss:>7.4f} {r.calibration_ece:>7.4f} | "
            f"{r.train_time_sec:>6.1f}s"
        )
    print("=" * len(hdr))


def _plot_comparison(
    results: list[PitchTypeMetrics], out_dir: Path,
) -> None:
    names = [r.name for r in results]
    colors = [COLORS.get(n, "#888") for n in names]

    fig, axes = plt.subplots(1, 4, figsize=(22, 6))
    chart_defs = [
        ("Top-1 Accuracy", [r.accuracy for r in results], True),
        ("Top-2 Accuracy", [r.top2_accuracy for r in results], True),
        ("Log Loss", [r.logloss for r in results], False),
        ("ECE", [r.calibration_ece for r in results], False),
    ]
    for ax, (title, vals, higher) in zip(axes, chart_defs, strict=True):
        bars = ax.bar(names, vals, color=colors, alpha=0.85)
        ax.set_title(title, fontsize=11)
        ax.grid(axis="y", alpha=0.3)
        ax.tick_params(axis="x", rotation=30, labelsize=7)
        best = int(np.argmax(vals)) if higher else int(np.argmin(vals))
        bars[best].set_edgecolor("black")
        bars[best].set_linewidth(2)
        for i, v in enumerate(vals):
            ax.text(i, v, f"{v:.4f}", ha="center", va="bottom",
                    fontsize=7)

    fig.suptitle(
        "Transformer Variants — Pitch Type Prediction (2025 holdout)",
        fontsize=14, fontweight="bold",
    )
    fig.tight_layout()
    fig.savefig(out_dir / "transformer_variants.png", dpi=150)
    plt.close(fig)
    print(f"  wrote {out_dir / 'transformer_variants.png'}")


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Pitch prediction research: attention + embeddings.",
    )
    parser.add_argument("--limit", type=int, default=None)
    parser.add_argument(
        "--out-dir", type=Path,
        default=Path("data/eval/pitch_research"),
    )
    parser.add_argument("--seed", type=int, default=42)
    args = parser.parse_args()

    cfg = ExperimentConfig(
        seed=args.seed, limit=args.limit, out_dir=args.out_dir,
    )
    out_dir = cfg.out_dir
    out_dir.mkdir(parents=True, exist_ok=True)

    # --- Load data ---
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
    all_results: list[PitchTypeMetrics] = []

    # === EXPERIMENT 1: TransformerV2 with Pitcher Embeddings ===
    print("\n[1/4] TransformerV2 + Pitcher Embeddings...")
    v2_model, v2_index, v2_pm, v2_bm, v2_time = train_transformer_v2(
        train_df, val_df, full_df, cfg,
        use_batter_embed=False,
        variant_name="T+Pitcher",
    )
    v2_preds = predict_transformer_v2(
        v2_model, v2_index, v2_pm, v2_bm, full_df, cfg,
    )
    m = compute_pitch_type_metrics(
        "T+Pitcher Embed", y_test, v2_preds.pitch_type_proba, v2_time,
    )
    all_results.append(m)
    print(f"  acc={m.accuracy:.4f}  top2={m.top2_accuracy:.4f}  "
          f"done in {v2_time:.1f}s")

    # === EXPERIMENT 2: TransformerV2 + Pitcher + Batter Embeddings ===
    print("\n[2/4] TransformerV2 + Pitcher + Batter Embeddings...")
    v2b_model, v2b_index, v2b_pm, v2b_bm, v2b_time = train_transformer_v2(
        train_df, val_df, full_df, cfg,
        use_batter_embed=True,
        variant_name="T+P+B",
    )
    v2b_preds = predict_transformer_v2(
        v2b_model, v2b_index, v2b_pm, v2b_bm, full_df, cfg,
    )
    m = compute_pitch_type_metrics(
        "T+Pitcher+Batter", y_test, v2b_preds.pitch_type_proba, v2b_time,
    )
    all_results.append(m)
    print(f"  acc={m.accuracy:.4f}  top2={m.top2_accuracy:.4f}  "
          f"done in {v2b_time:.1f}s")

    # === EXPERIMENT 3: Hybrid with V2 (pitcher embed) + LightGBM ===
    print("\n[3/4] Hybrid (V2 + Pitcher Embed + LightGBM)...")
    hy_booster, hy_time = _train_hybrid_from_v2(
        v2_model, v2_index, v2_pm, v2_bm,
        train_df, val_df, full_df, cfg,
    )
    hy_proba = _predict_hybrid_v2(
        hy_booster, v2_model, v2_index, v2_pm, v2_bm,
        test_df, full_df, cfg,
    )
    m = compute_pitch_type_metrics(
        "Hybrid+Pitcher", y_test, hy_proba, v2_time + hy_time,
    )
    all_results.append(m)
    print(f"  acc={m.accuracy:.4f}  top2={m.top2_accuracy:.4f}  "
          f"done in {v2_time + hy_time:.1f}s")

    # === EXPERIMENT 4: Hybrid with V2 (pitcher+batter) + LightGBM ===
    print("\n[4/4] Hybrid (V2 + P+B Embed + LightGBM)...")
    hyb_booster, hyb_time = _train_hybrid_from_v2(
        v2b_model, v2b_index, v2b_pm, v2b_bm,
        train_df, val_df, full_df, cfg,
    )
    hyb_proba = _predict_hybrid_v2(
        hyb_booster, v2b_model, v2b_index, v2b_pm, v2b_bm,
        test_df, full_df, cfg,
    )
    m = compute_pitch_type_metrics(
        "Hybrid+P+B", y_test, hyb_proba, v2b_time + hyb_time,
    )
    all_results.append(m)
    print(f"  acc={m.accuracy:.4f}  top2={m.top2_accuracy:.4f}  "
          f"done in {v2b_time + hyb_time:.1f}s")

    # === RESULTS TABLE ===
    _print_table(all_results)
    _plot_comparison(all_results, out_dir)

    # === ANALYSIS: Best model ===
    best_idx = max(range(len(all_results)), key=lambda i: all_results[i].accuracy)
    best_name = all_results[best_idx].name
    best_proba = [
        v2_preds.pitch_type_proba,
        v2b_preds.pitch_type_proba,
        hy_proba,
        hyb_proba,
    ][best_idx]

    print(f"\n=== Analysis on best model: {best_name} ===")

    # Calibration.
    plot_calibration_curve(
        y_test, best_proba, out_dir, model_name=best_name,
    )
    plot_confidence_histogram(
        y_test, best_proba, out_dir, model_name=best_name,
    )
    plot_topk_curve(
        y_test, best_proba, out_dir, model_name=best_name,
    )
    plot_entropy_analysis(
        y_test, best_proba, out_dir, model_name=best_name,
    )

    # Attention analysis on V2 pitcher model.
    # Extract attention weights from V2 model (skip the head, just
    # get attention patterns from the encoder layers).
    print("\nextracting attention weights from V2 model...")
    test_indices = np.where(
        full_df["season"].isin(cfg.test_years).values,
    )[0].astype(np.int32)
    try:
        attentions, _attn_preds, _attn_targets = extract_attention_weights(
            v2_model, v2_index, test_indices, cfg, max_samples=3000,
        )
        if attentions.size > 0:
            plot_attention_heatmap(attentions, out_dir, n_examples=3)
            plot_temporal_attention_decay(attentions, out_dir)
        else:
            print("  no attention weights captured")
    except Exception as e:
        print(f"  attention extraction failed: {e}")
        print("  (V2 head shape mismatch — skipping attention viz)")

    # Pitcher embedding visualization.
    print("\nvisualizing pitcher embeddings...")
    try:
        from sklearn.manifold import TSNE

        embs, _pids = extract_embeddings_for_viz(v2_model, v2_pm)
        if embs.shape[0] > 10:
            tsne = TSNE(n_components=2, random_state=cfg.seed, perplexity=30)
            coords = tsne.fit_transform(embs)
            fig, ax = plt.subplots(figsize=(10, 8))
            ax.scatter(
                coords[:, 0], coords[:, 1],
                alpha=0.4, s=15, c="#2563eb",
            )
            ax.set_title("Pitcher Embeddings (t-SNE)")
            ax.set_xlabel("t-SNE 1")
            ax.set_ylabel("t-SNE 2")
            ax.grid(alpha=0.2)
            fig.tight_layout()
            fig.savefig(out_dir / "pitcher_embeddings_tsne.png", dpi=150)
            plt.close(fig)
            print(f"  wrote {out_dir / 'pitcher_embeddings_tsne.png'}")
    except ImportError:
        print("  sklearn not available for t-SNE")

    # Save JSON.
    json_out = {
        "schema_version": 1,
        "artifact_name": "pitch_research_v2",
        "models": [
            {
                "name": r.name,
                "accuracy": r.accuracy,
                "top2_accuracy": r.top2_accuracy,
                "logloss": r.logloss,
                "calibration_ece": r.calibration_ece,
                "per_class_accuracy": r.per_class_accuracy,
                "train_time_sec": r.train_time_sec,
            }
            for r in all_results
        ],
    }
    json_path = out_dir / "pitch_research_v2.json"
    json_path.write_text(json.dumps(json_out, indent=2))
    print(f"  wrote {json_path}")
    print(f"\nall outputs in {out_dir}")


if __name__ == "__main__":
    main()
