"""Final experiment — STAGE 2 (CPU-heavy): LightGBM boosters.

Loads the stage-1 embeddings + tabular features from disk (no GPU, no data
reload) and trains the two boosters for the streak ablation:
  - Context + Streak (full feature set)
  - Context (reference) = the streak matrix minus the streak columns

Persists both boosters + the streak feature importance + metrics. Run after
stage 1 has cooled down; let it cool again before stage 3.

Usage:
  uv run python scripts/final_stage2_boosters.py
"""

from __future__ import annotations

import argparse
import gc
import json
from pathlib import Path

import numpy as np

from bullpen_training.pitch_comparison.combined_common import train_lgbm
from bullpen_training.pitch_comparison.config import ExperimentConfig
from bullpen_training.pitch_comparison.data_enriched import STREAK_FEATURE_COLS
from bullpen_training.pitch_comparison.final_common import (
    STAGE_DIR,
    load_array,
    load_meta,
)
from bullpen_training.pitch_comparison.metrics import compute_pitch_type_metrics


def main() -> None:
    ap = argparse.ArgumentParser(description="Final experiment stage 2 (boosters).")
    ap.add_argument("--stage-dir", type=Path, default=STAGE_DIR)
    ap.add_argument("--out-dir", type=Path, default=Path("data/eval/pitch_final"))
    args = ap.parse_args()

    stage_dir = args.stage_dir
    args.out_dir.mkdir(parents=True, exist_ok=True)
    meta = load_meta(stage_dir)
    n_emb, n_streak = meta["n_emb"], meta["n_streak"]
    tab_cols = meta["tab_cols"]
    cfg = ExperimentConfig(seed=meta["seed"])
    cfg.lgbm_num_threads = meta["lgbm_num_threads"]

    print("loading stage-1 arrays...")
    y_tr, y_va, y_te = (load_array(stage_dir, f"y_{s}") for s in ("train", "val", "test"))

    # Build the streak (superset) matrices, freeing each embedding as copied.
    def _matrix(split):
        emb = load_array(stage_dir, f"emb_{split}")
        tab = load_array(stage_dir, f"tab_{split}")
        x = np.hstack([emb, tab])
        del emb, tab
        gc.collect()
        return x

    xtr, xva, xte = _matrix("train"), _matrix("val"), _matrix("test")
    feat_names = [f"emb_{i}" for i in range(n_emb)] + list(tab_cols)
    results = []

    print("\n[Context + Streak] training booster...")
    b_streak = train_lgbm(xtr, y_tr, xva, y_va, cfg)
    p = np.asarray(b_streak.predict(xte), dtype=np.float32)
    m = compute_pitch_type_metrics("Context + Streak", y_te, p, 0.0)
    print(f"  acc={m.accuracy:.4f}  top2={m.top2_accuracy:.4f}  ece={m.calibration_ece:.4f}")
    results.append(m)
    b_streak.save_model(str(stage_dir / "streak_booster.txt"))

    streak_importance = {
        n: float(v) for n, v in zip(
            feat_names, b_streak.feature_importance(importance_type="gain"), strict=True,
        ) if n in STREAK_FEATURE_COLS
    }
    print("  streak feature importance (gain):")
    for n, v in sorted(streak_importance.items(), key=lambda x: -x[1]):
        print(f"    {n:<24s} {v:>12.0f}")

    print("\n[Context (reference, no streak)] training booster...")
    b_ref = train_lgbm(xtr[:, :-n_streak], y_tr, xva[:, :-n_streak], y_va, cfg)
    p = np.asarray(b_ref.predict(xte[:, :-n_streak]), dtype=np.float32)
    m_ref = compute_pitch_type_metrics("Context (ref)", y_te, p, 0.0)
    print(f"  acc={m_ref.accuracy:.4f}  top2={m_ref.top2_accuracy:.4f}  ece={m_ref.calibration_ece:.4f}")
    results.insert(0, m_ref)  # reference first
    b_ref.save_model(str(stage_dir / "context_ref_booster.txt"))

    out = {
        "stage": 2,
        "streak_models": [
            {"name": r.name, "accuracy": r.accuracy, "top2_accuracy": r.top2_accuracy,
             "logloss": r.logloss, "calibration_ece": r.calibration_ece}
            for r in results
        ],
        "streak_feature_importance": streak_importance,
        "streak_delta_acc": float(results[1].accuracy - results[0].accuracy),
    }
    (args.out_dir / "final_stage2.json").write_text(json.dumps(out, indent=2))
    print(f"\n  wrote {args.out_dir / 'final_stage2.json'}")
    print("  next: let the machine cool, then run final_stage3_analysis.py")


if __name__ == "__main__":
    main()
