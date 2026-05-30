"""Train ONE model: the catcher-aware transformer (+ its two boosters).

The second GPU-heavy transformer from the combined experiment, in its own
script for isolated runs (train this, rest, then train_hybrid_context.py — or
vice-versa). Adds a learned catcher embedding to the sequence representation;
best-on-calibration model (ECE 0.0070).

Trains the catcher-aware transformer once, then two LightGBM meta-models off the
SAME embeddings (cheap, CPU): base (emb + base features) and the combined
(emb + base + enriched context). Saves weights to --save-dir and merges results
+ arch into the shared metadata.json.

Usage:
  CLICKHOUSE_PORT=9000 uv run python scripts/train_catcher.py
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
    BASE_FEAT,
    catcher_arch_meta,
    feature_matrix,
    load_splits,
    metric_dict,
    train_lgbm,
    update_metadata,
)
from bullpen_training.pitch_comparison.config import ExperimentConfig
from bullpen_training.pitch_comparison.metrics import compute_pitch_type_metrics
from bullpen_training.pitch_comparison.transformer_catcher import (
    extract_catcher_hybrid_embeddings,
    train_catcher_transformer,
)


def main() -> None:
    ap = argparse.ArgumentParser(description="Train the catcher-aware model only.")
    ap.add_argument("--limit", type=int, default=None)
    ap.add_argument("--out-dir", type=Path, default=Path("data/eval/pitch_combined"))
    ap.add_argument("--save-dir", type=Path, default=Path("artifacts/pitch_combined_v1"))
    ap.add_argument("--no-save", action="store_true")
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument(
        "--skip-base",
        action="store_true",
        help="Train only the combined (context) booster, skip the base ablation.",
    )
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
    cov = float((test_df["catcher_id"] > 0).mean())
    print(f"  catcher_id coverage in test: {cov:.1%}")

    print("\ntraining catcher-aware transformer (pitcher + catcher embeddings)...")
    model, index, pm, cm, t_time = train_catcher_transformer(
        train_df,
        val_df,
        full_df,
        cfg,
        use_catcher=True,
        variant_name="Catcher",
    )
    print("extracting catcher-hybrid embeddings...")
    emb_tr = extract_catcher_hybrid_embeddings(model, index, pm, cm, full_df, idx["train"], cfg)
    emb_va = extract_catcher_hybrid_embeddings(model, index, pm, cm, full_df, idx["val"], cfg)
    emb_te = extract_catcher_hybrid_embeddings(model, index, pm, cm, full_df, idx["test"], cfg)

    results: list = []
    boosters: dict = {}

    if not args.skip_base:
        print("\n[Catcher-Hybrid (base)] training LightGBM (emb + base)...")
        t0 = time.perf_counter()
        b_base = train_lgbm(
            feature_matrix(emb_tr, train_df, BASE_FEAT),
            y["train"],
            feature_matrix(emb_va, val_df, BASE_FEAT),
            y["val"],
            cfg,
        )
        p = np.asarray(b_base.predict(feature_matrix(emb_te, test_df, BASE_FEAT)), dtype=np.float32)
        m = compute_pitch_type_metrics(
            "Catcher-Hybrid (base)", y["test"], p, t_time + (time.perf_counter() - t0)
        )
        print(f"  acc={m.accuracy:.4f}  top2={m.top2_accuracy:.4f}  ece={m.calibration_ece:.4f}")
        results.append(m)
        boosters["catcher_base_lgbm.txt"] = b_base

    print("\n[Catcher-Hybrid + Context] training LightGBM (emb + base + context)...")
    t0 = time.perf_counter()
    b_ctx = train_lgbm(
        feature_matrix(emb_tr, train_df, ALL_FEAT),
        y["train"],
        feature_matrix(emb_va, val_df, ALL_FEAT),
        y["val"],
        cfg,
    )
    p = np.asarray(b_ctx.predict(feature_matrix(emb_te, test_df, ALL_FEAT)), dtype=np.float32)
    m = compute_pitch_type_metrics(
        "Catcher-Hybrid + Context", y["test"], p, t_time + (time.perf_counter() - t0)
    )
    print(f"  acc={m.accuracy:.4f}  top2={m.top2_accuracy:.4f}  ece={m.calibration_ece:.4f}")
    results.append(m)
    boosters["catcher_context_lgbm.txt"] = b_ctx

    (args.out_dir / "catcher.json").write_text(
        json.dumps([metric_dict(r) for r in results], indent=2)
    )

    if save_dir is not None:
        torch.save(model.state_dict(), save_dir / "catcher_transformer.pt")
        for name, b in boosters.items():
            b.save_model(str(save_dir / name))
        (save_dir / "catcher_id_maps.json").write_text(
            json.dumps({"pitcher_map": pm, "catcher_map": cm})
        )
        update_metadata(
            save_dir,
            catcher_transformer=catcher_arch_meta(model, cfg),
            catcher_coverage_test=cov,
            results=[metric_dict(r) for r in results],
        )
        print(f"  saved catcher model -> {save_dir}")


if __name__ == "__main__":
    main()
