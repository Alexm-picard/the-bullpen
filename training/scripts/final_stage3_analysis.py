"""Final experiment — STAGE 3 (CPU-light): SHAP + rookie prototyping.

Loads the stage-1 embeddings/clusters and the stage-2 streak booster from disk
and runs the two cheap analyses:
  - SHAP feature attribution on the streak booster
  - Rookie prototype-clustering eval (default vs feature/embedding/both
    substitution for <500-pitch pitchers)

No GPU, light CPU. Run after stage 2.

Usage:
  uv run python scripts/final_stage3_analysis.py
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import lightgbm as lgb
import numpy as np
from sklearn.metrics import accuracy_score

from bullpen_training.pitch_comparison.final_common import (
    STAGE_DIR,
    load_array,
    load_meta,
    load_pickle,
    shap_importance,
)
from bullpen_training.pitch_comparison.rookie_prototyping import (
    apply_prototype_substitution,
)


def main() -> None:
    ap = argparse.ArgumentParser(description="Final experiment stage 3 (SHAP + rookie).")
    ap.add_argument("--stage-dir", type=Path, default=STAGE_DIR)
    ap.add_argument("--out-dir", type=Path, default=Path("data/eval/pitch_final"))
    ap.add_argument("--shap-sample", type=int, default=3000)
    ap.add_argument("--seed", type=int, default=42)
    args = ap.parse_args()

    stage_dir = args.stage_dir
    args.out_dir.mkdir(parents=True, exist_ok=True)
    meta = load_meta(stage_dir)
    n_emb = meta["n_emb"]
    tab_cols = meta["tab_cols"]
    pe_slice = slice(*meta["pitcher_emb_slice"])

    booster_path = stage_dir / "streak_booster.txt"
    if not booster_path.exists():
        print(f"ERROR: {booster_path} not found — run final_stage2_boosters.py first.")
        return
    booster = lgb.Booster(model_file=str(booster_path))

    emb_test = load_array(stage_dir, "emb_test")
    tab_test = load_array(stage_dir, "tab_test")
    y_test = load_array(stage_dir, "y_test")
    xte = np.hstack([emb_test, tab_test])
    feat_names = [f"emb_{i}" for i in range(n_emb)] + list(tab_cols)

    # ---- SHAP ----
    print("[SHAP] analysing the streak booster...")
    rng = np.random.default_rng(args.seed)
    samp = rng.choice(xte.shape[0], size=min(args.shap_sample, xte.shape[0]), replace=False)
    shap_ranked = shap_importance(booster, xte[samp], feat_names, n_emb, args.out_dir)

    # ---- Rookie prototyping ----
    print("\n[Rookie] prototype-clustering eval...")
    clusters = load_pickle(stage_dir, "clusters")
    is_rookie_test = load_array(stage_dir, "is_rookie_test").astype(bool)
    cluster_ids_test = load_array(stage_dir, "cluster_ids_test")
    n_rookie = int(is_rookie_test.sum())
    n_test = meta["n_test"]
    print(f"  rookie test pitches: {n_rookie:,} / {n_test:,} ({n_rookie / max(n_test, 1):.2%})")

    def _rookie_acc(proba):
        if n_rookie == 0:
            return float("nan")
        return float(accuracy_score(y_test[is_rookie_test], proba[is_rookie_test].argmax(1)))

    rookie_rows = {"default": _rookie_acc(np.asarray(booster.predict(xte), dtype=np.float32))}
    if n_rookie > 0:
        for label, sub_f, sub_e in [
            ("features", True, False),
            ("embedding", False, True),
            ("features+embedding", True, True),
        ]:
            tab2, emb2 = apply_prototype_substitution(
                tab_test,
                tab_cols,
                emb_test,
                cluster_ids=cluster_ids_test,
                is_rookie=is_rookie_test,
                clusters=clusters,
                pitcher_emb_slice=pe_slice,
                substitute_features=sub_f,
                substitute_embedding=sub_e,
            )
            p = np.asarray(booster.predict(np.hstack([emb2, tab2])), dtype=np.float32)
            rookie_rows[label] = _rookie_acc(p)

    print("  rookie-subset accuracy:")
    for k, v in rookie_rows.items():
        print(f"    {k:<22s} {v:.4f}")

    out = {
        "stage": 3,
        "shap_importance": shap_ranked,
        "rookie": {
            "threshold": meta["rookie_threshold"],
            "n_clusters": clusters.n_clusters,
            "n_rookie_test": n_rookie,
            "n_test": n_test,
            "rookie_accuracy": rookie_rows,
        },
    }
    (args.out_dir / "final_stage3.json").write_text(json.dumps(out, indent=2))
    print(f"\n  wrote {args.out_dir / 'final_stage3.json'}")
    print("  all stages complete.")


if __name__ == "__main__":
    main()
