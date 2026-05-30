"""Final experiment — STAGE 1 (GPU-heavy): transformer + embeddings.

The only GPU-intensive stage. Trains the catcher-aware transformer (20 epochs),
builds the rookie prototype clusters, extracts catcher-hybrid embeddings for
train/val/test, computes the rookie-eval inputs, and persists everything to the
stage dir so stages 2 & 3 run CPU-only off disk.

Run this, then let the machine cool before stage 2.

Usage:
  CLICKHOUSE_PORT=9000 uv run python scripts/final_stage1_embeddings.py
"""

from __future__ import annotations

import argparse
import gc
from pathlib import Path

import numpy as np

from bullpen_training.pitch_comparison.combined_common import (
    ALL_FEAT,
    feature_matrix,  # noqa: F401  (kept for parity; stages build matrices themselves)
)
from bullpen_training.pitch_comparison.config import ExperimentConfig
from bullpen_training.pitch_comparison.data import FEATURE_COLS
from bullpen_training.pitch_comparison.data_enriched import (
    CONTEXT_FEATURE_COLS,
    STREAK_FEATURE_COLS,
    load_enriched_data,
    prepare_enriched_datasets,
)
from bullpen_training.pitch_comparison.final_common import (
    STAGE_DIR,
    save_array,
    save_meta,
    save_pickle,
)
from bullpen_training.pitch_comparison.rookie_prototyping import (
    ROOKIE_PITCH_THRESHOLD,
    assign_clusters_streaming,
    build_prototype_clusters,
    compute_cum_pitch_count,
    set_cluster_embeddings,
)
from bullpen_training.pitch_comparison.transformer_catcher import (
    extract_catcher_hybrid_embeddings,
    train_catcher_transformer,
)


def main() -> None:
    ap = argparse.ArgumentParser(description="Final experiment stage 1 (GPU).")
    ap.add_argument("--limit", type=int, default=None)
    ap.add_argument("--stage-dir", type=Path, default=STAGE_DIR)
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--n-clusters", type=int, default=8)
    args = ap.parse_args()

    cfg = ExperimentConfig(seed=args.seed, limit=args.limit)
    stage_dir = args.stage_dir
    stage_dir.mkdir(parents=True, exist_ok=True)

    base_feat = list(FEATURE_COLS)
    ctx_feat = base_feat + list(CONTEXT_FEATURE_COLS)
    streak_feat = ctx_feat + list(STREAK_FEATURE_COLS)
    n_streak = len(STREAK_FEATURE_COLS)

    print("loading enriched data (with streak features)...")
    raw = load_enriched_data(season_from=cfg.season_from, season_to=cfg.season_to, limit=cfg.limit)
    train_df, val_df, test_df = prepare_enriched_datasets(
        raw, train_years=cfg.train_years, val_years=cfg.val_years,
        test_years=cfg.test_years, add_streak=True,
    )
    del raw
    gc.collect()
    print(f"  train: {len(train_df)}  val: {len(val_df)}  test: {len(test_df)}")
    if len(test_df) == 0:
        print("ERROR: empty test set.")
        return

    import pandas as pd
    for df in (train_df, val_df, test_df):
        for col in streak_feat:
            df[col] = pd.to_numeric(df[col], errors="coerce")

    full_df = pd.concat([train_df, val_df, test_df], ignore_index=True)
    train_idx = np.where(full_df["season"].isin(cfg.train_years).values)[0].astype(np.int32)
    val_idx = np.where(full_df["season"].isin(cfg.val_years).values)[0].astype(np.int32)
    test_idx = np.where(full_df["season"].isin(cfg.test_years).values)[0].astype(np.int32)

    print(f"\nbuilding prototype clusters (k={args.n_clusters})...")
    clusters = build_prototype_clusters(train_df, n_clusters=args.n_clusters, seed=cfg.seed)
    print(f"  {clusters.n_established} established -> {clusters.n_clusters} clusters")

    print("\ntraining catcher-aware transformer (GPU)...")
    model, index, p_map, c_map, _t = train_catcher_transformer(
        train_df, val_df, full_df, cfg, use_catcher=True, variant_name="Catcher",
    )
    set_cluster_embeddings(
        clusters, model.pitcher_emb.weight.detach().cpu().numpy(), p_map, train_df,
    )
    pe_dim = model.pitcher_emb.embedding_dim

    print("extracting catcher-hybrid embeddings (GPU)...")
    emb_train = extract_catcher_hybrid_embeddings(model, index, p_map, c_map, full_df, train_idx, cfg)
    emb_val = extract_catcher_hybrid_embeddings(model, index, p_map, c_map, full_df, val_idx, cfg)
    emb_test = extract_catcher_hybrid_embeddings(model, index, p_map, c_map, full_df, test_idx, cfg)
    n_emb = int(emb_train.shape[1])

    # Rookie-eval inputs (need full_df; compute now so later stages don't reload it).
    cum = compute_cum_pitch_count(full_df)
    is_rookie_test = cum[test_idx] < ROOKIE_PITCH_THRESHOLD
    cluster_ids_test = assign_clusters_streaming(full_df, clusters)[test_idx]

    print("\npersisting stage-1 outputs...")
    save_array(stage_dir, "emb_train", emb_train)
    save_array(stage_dir, "emb_val", emb_val)
    save_array(stage_dir, "emb_test", emb_test)
    save_array(stage_dir, "tab_train", train_df[streak_feat].to_numpy(np.float32))
    save_array(stage_dir, "tab_val", val_df[streak_feat].to_numpy(np.float32))
    save_array(stage_dir, "tab_test", test_df[streak_feat].to_numpy(np.float32))
    save_array(stage_dir, "y_train", train_df["pitch_type_int"].to_numpy().astype(np.int64))
    save_array(stage_dir, "y_val", val_df["pitch_type_int"].to_numpy().astype(np.int64))
    save_array(stage_dir, "y_test", test_df["pitch_type_int"].to_numpy().astype(np.int64))
    save_array(stage_dir, "is_rookie_test", is_rookie_test.astype(bool))
    save_array(stage_dir, "cluster_ids_test", cluster_ids_test.astype(np.int32))
    save_pickle(stage_dir, "clusters", clusters)
    save_meta(stage_dir, {
        "n_emb": n_emb,
        "n_streak": n_streak,
        "d_model": int(cfg.d_model),
        "pitcher_embed_dim": int(pe_dim),
        "pitcher_emb_slice": [int(cfg.d_model), int(cfg.d_model + pe_dim)],
        "tab_cols": list(streak_feat),
        "ctx_cols": list(ctx_feat),
        "streak_cols": list(STREAK_FEATURE_COLS),
        "n_test": int(len(test_df)),
        "n_rookie_test": int(is_rookie_test.sum()),
        "rookie_threshold": ROOKIE_PITCH_THRESHOLD,
        "seed": cfg.seed,
        "lgbm_num_threads": cfg.lgbm_num_threads,
    })
    print(f"  stage 1 complete -> {stage_dir}")
    print("  next: let the machine cool, then run final_stage2_boosters.py")


if __name__ == "__main__":
    main()
