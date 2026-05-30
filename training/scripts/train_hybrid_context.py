"""Train ONE model: Hybrid + Context (V2 pitcher-embedding transformer).

This is one of the two GPU-heavy transformers from the combined experiment,
split into its own script so it can be run in isolation (train one model, let
the machine rest, then run train_catcher.py). Best-on-accuracy model (45.26%).

Trains the V2 transformer (pitcher embeddings) -> extracts [pooled|pitcher_emb]
-> LightGBM on [emb + base + enriched context]. Saves weights to --save-dir and
merges its result + arch into the shared metadata.json.

Usage:
  CLICKHOUSE_PORT=9000 uv run python scripts/train_hybrid_context.py
"""

from __future__ import annotations

import argparse
import json
import time
from pathlib import Path

import numpy as np
import torch

from bullpen_training.pitch_comparison.combined_common import (
    ALL_FEAT,
    extract_v2_embeddings,
    feature_matrix,
    load_splits,
    metric_dict,
    train_lgbm,
    update_metadata,
    v2_arch_meta,
)
from bullpen_training.pitch_comparison.config import ExperimentConfig
from bullpen_training.pitch_comparison.metrics import compute_pitch_type_metrics
from bullpen_training.pitch_comparison.transformer_v2 import train_transformer_v2


def main() -> None:
    ap = argparse.ArgumentParser(description="Train the Hybrid+Context model only.")
    ap.add_argument("--limit", type=int, default=None)
    ap.add_argument("--out-dir", type=Path, default=Path("data/eval/pitch_combined"))
    ap.add_argument("--save-dir", type=Path, default=Path("artifacts/pitch_combined_v1"))
    ap.add_argument("--no-save", action="store_true")
    ap.add_argument("--seed", type=int, default=42)
    args = ap.parse_args()

    cfg = ExperimentConfig(seed=args.seed, limit=args.limit, out_dir=args.out_dir)
    args.out_dir.mkdir(parents=True, exist_ok=True)
    save_dir = None if args.no_save else args.save_dir
    if save_dir is not None:
        save_dir.mkdir(parents=True, exist_ok=True)

    train_df, val_df, test_df, full_df, idx, y = load_splits(cfg)
    if len(test_df) == 0:
        print("ERROR: empty test set.")
        return

    print("\ntraining V2 transformer (pitcher embeddings only)...")
    model, index, pm, bm, t_time = train_transformer_v2(
        train_df,
        val_df,
        full_df,
        cfg,
        use_batter_embed=False,
        variant_name="V2",
    )
    print("extracting V2 embeddings...")
    emb_tr = extract_v2_embeddings(model, index, pm, bm, full_df, idx["train"], cfg)
    emb_va = extract_v2_embeddings(model, index, pm, bm, full_df, idx["val"], cfg)
    emb_te = extract_v2_embeddings(model, index, pm, bm, full_df, idx["test"], cfg)

    print("\n[Hybrid + Context] training LightGBM (emb + base + context)...")
    t0 = time.perf_counter()
    booster = train_lgbm(
        feature_matrix(emb_tr, train_df, ALL_FEAT),
        y["train"],
        feature_matrix(emb_va, val_df, ALL_FEAT),
        y["val"],
        cfg,
    )
    proba = np.asarray(
        booster.predict(feature_matrix(emb_te, test_df, ALL_FEAT)),
        dtype=np.float32,
    )
    m = compute_pitch_type_metrics(
        "Hybrid + Context",
        y["test"],
        proba,
        t_time + (time.perf_counter() - t0),
    )
    print(
        f"  acc={m.accuracy:.4f}  top2={m.top2_accuracy:.4f}  "
        f"logloss={m.logloss:.4f}  ece={m.calibration_ece:.4f}"
    )

    (args.out_dir / "hybrid_context.json").write_text(json.dumps(metric_dict(m), indent=2))

    if save_dir is not None:
        torch.save(model.state_dict(), save_dir / "v2_transformer.pt")
        booster.save_model(str(save_dir / "hybrid_context_lgbm.txt"))
        (save_dir / "v2_id_maps.json").write_text(json.dumps({"pitcher_map": pm, "batter_map": bm}))
        update_metadata(
            save_dir,
            v2_transformer=v2_arch_meta(model, cfg),
            results=[metric_dict(m)],
        )
        print(f"  saved Hybrid+Context model -> {save_dir}")


if __name__ == "__main__":
    main()
