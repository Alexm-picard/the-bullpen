"""Final pre-model experiments: streak features + SHAP + rookie prototyping.

Builds on the best model so far (catcher-aware transformer embeddings ->
LightGBM with base + enriched context). Trains the transformer ONCE and
reuses its embeddings for three studies:

  Idea 1 — Streak/lag features
      Add repeat_pitch_type + previous pitch type/result to the booster
      and measure the accuracy delta vs context-only.

  Idea 2 — SHAP explanations
      TreeExplainer on the streak booster; rank named features (and the
      grouped sequence/entity embedding) by mean |SHAP|.

  Idea 3 — Rookie prototype clustering
      For pitches by pitchers with < 500 career pitches, substitute a
      cluster prototype (career features + pitcher embedding) drawn from
      similar established pitchers, and measure rookie-subset accuracy
      vs the default model.

Usage:
  CLICKHOUSE_PORT=9000 uv run python scripts/run_final_experiments.py
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
import torch
from sklearn.metrics import accuracy_score

from bullpen_training.pitch_comparison.config import ExperimentConfig
from bullpen_training.pitch_comparison.data import (
    FEATURE_COLS,
    PITCH_TYPE_CLASSES,
)
from bullpen_training.pitch_comparison.data_enriched import (
    CONTEXT_FEATURE_COLS,
    STREAK_FEATURE_COLS,
    load_enriched_data,
    prepare_enriched_datasets,
)
from bullpen_training.pitch_comparison.metrics import (
    PitchTypeMetrics,
    compute_pitch_type_metrics,
)
from bullpen_training.pitch_comparison.rookie_prototyping import (
    ROOKIE_PITCH_THRESHOLD,
    apply_prototype_substitution,
    assign_clusters_streaming,
    build_prototype_clusters,
    compute_cum_pitch_count,
    set_cluster_embeddings,
)
from bullpen_training.pitch_comparison.transformer_catcher import (
    extract_catcher_hybrid_embeddings,
    train_catcher_transformer,
)


def _rss_mb() -> float:
    """Resident set size of this process in MB (Linux /proc)."""
    try:
        with open("/proc/self/status") as fh:
            for line in fh:
                if line.startswith("VmRSS:"):
                    return int(line.split()[1]) / 1024.0
    except OSError:
        pass
    return -1.0


def _mem(tag: str) -> None:
    avail = -1.0
    try:
        with open("/proc/meminfo") as fh:
            for line in fh:
                if line.startswith("MemAvailable:"):
                    avail = int(line.split()[1]) / 1024.0
                    break
    except OSError:
        pass
    print(f"  [mem] {tag}: RSS={_rss_mb():.0f}MB  MemAvailable={avail:.0f}MB", flush=True)


def _train_lgbm(train_x, train_y, val_x, val_y, seed, num_threads=8):
    params = {
        "objective": "multiclass",
        "num_class": len(PITCH_TYPE_CLASSES),
        "metric": "multi_logloss",
        "learning_rate": 0.05,
        "num_leaves": 63,
        "seed": seed,
        "deterministic": True,
        "force_row_wise": True,
        "verbose": -1,
        # Cap threads to limit peak power/heat (host crashes under all-core load).
        "num_threads": num_threads,
    }
    dt = lgb.Dataset(train_x, label=train_y)
    dv = lgb.Dataset(val_x, label=val_y, reference=dt)
    return lgb.train(
        params,
        dt,
        2000,
        valid_sets=[dt, dv],
        valid_names=["t", "v"],
        callbacks=[lgb.early_stopping(50, first_metric_only=True, verbose=False)],
    )


def _shap_analysis(booster, x_sample, feat_names, n_emb, out_dir):
    """Mean |SHAP| ranking; embedding dims grouped into one bar."""
    try:
        import shap
    except ImportError:
        print("  shap not installed — skipping SHAP analysis")
        return {}

    explainer = shap.TreeExplainer(booster)
    sv = explainer.shap_values(x_sample)
    # Normalise to (n_classes, n_samples, n_features).
    if isinstance(sv, list):
        arr = np.stack(sv, axis=0)
    else:
        arr = np.asarray(sv)
        # (n_samples, n_features, n_classes) → (n_classes, n_samples, n_features)
        arr = np.transpose(arr, (2, 0, 1)) if arr.ndim == 3 else arr[None, ...]
    mean_abs = np.abs(arr).mean(axis=(0, 1))  # per feature

    named = {feat_names[i]: float(mean_abs[i]) for i in range(n_emb, len(feat_names))}
    named["sequence+entity_embedding"] = float(mean_abs[:n_emb].sum())

    ranked = dict(sorted(named.items(), key=lambda x: -x[1]))
    labels = list(ranked.keys())
    vals = list(ranked.values())
    fig, ax = plt.subplots(figsize=(10, max(5, 0.4 * len(labels))))
    ax.barh(labels[::-1], vals[::-1], color="#0891b2", alpha=0.85)
    ax.set_xlabel("mean |SHAP| (averaged over classes)")
    ax.set_title("Feature importance via SHAP — Catcher-Hybrid + Context + Streak")
    ax.grid(axis="x", alpha=0.3)
    fig.tight_layout()
    fig.savefig(out_dir / "shap_importance.png", dpi=150)
    plt.close(fig)
    print(f"  wrote {out_dir / 'shap_importance.png'}")
    return ranked


def main() -> None:
    parser = argparse.ArgumentParser(description="Final pre-model experiments.")
    parser.add_argument("--limit", type=int, default=None)
    parser.add_argument("--out-dir", type=Path, default=Path("data/eval/pitch_final"))
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--n-clusters", type=int, default=8)
    parser.add_argument("--shap-sample", type=int, default=3000)
    args = parser.parse_args()

    cfg = ExperimentConfig(seed=args.seed, limit=args.limit, out_dir=args.out_dir)
    out_dir = cfg.out_dir
    out_dir.mkdir(parents=True, exist_ok=True)

    print("loading enriched data...")
    raw_df = load_enriched_data(
        season_from=cfg.season_from,
        season_to=cfg.season_to,
        limit=cfg.limit,
    )
    print(f"  {len(raw_df)} rows")
    print("preparing enriched splits (with streak features)...")
    train_df, val_df, test_df = prepare_enriched_datasets(
        raw_df,
        train_years=cfg.train_years,
        val_years=cfg.val_years,
        test_years=cfg.test_years,
        add_streak=True,  # this is the streak experiment (cloud)
    )
    del raw_df
    gc.collect()
    print(f"  train: {len(train_df)}  val: {len(val_df)}  test: {len(test_df)}")
    _mem("after splits")
    if len(test_df) == 0:
        print("ERROR: empty test set.")
        return

    base_feat = list(FEATURE_COLS)
    ctx_feat = base_feat + list(CONTEXT_FEATURE_COLS)
    streak_feat = ctx_feat + list(STREAK_FEATURE_COLS)
    for df in [train_df, val_df, test_df]:
        for col in streak_feat:
            df[col] = pd.to_numeric(df[col], errors="coerce")

    full_df = pd.concat([train_df, val_df, test_df], ignore_index=True)
    _mem("after full_df concat")
    y_test = test_df["pitch_type_int"].to_numpy().astype(int)
    train_y = train_df["pitch_type_int"].to_numpy().astype(int)
    val_y = val_df["pitch_type_int"].to_numpy().astype(int)

    train_idx = np.where(full_df["season"].isin(cfg.train_years).to_numpy())[0].astype(np.int32)
    val_idx = np.where(full_df["season"].isin(cfg.val_years).to_numpy())[0].astype(np.int32)
    test_idx = np.where(full_df["season"].isin(cfg.test_years).to_numpy())[0].astype(np.int32)

    # ---- Prototype clusters (train-only) -------------------------------
    print(f"\nbuilding prototype clusters (k={args.n_clusters})...")
    clusters = build_prototype_clusters(train_df, n_clusters=args.n_clusters, seed=cfg.seed)
    print(f"  {clusters.n_established} established pitchers -> {clusters.n_clusters} clusters")

    # ---- Train the catcher-aware transformer once ----------------------
    print("\ntraining catcher-aware transformer (pitcher + catcher)...")
    model, index, p_map, c_map, t_time = train_catcher_transformer(
        train_df,
        val_df,
        full_df,
        cfg,
        use_catcher=True,
        variant_name="Catcher",
    )
    set_cluster_embeddings(
        clusters,
        model.pitcher_emb.weight.detach().cpu().numpy(),
        p_map,
        train_df,
    )
    pe_dim = model.pitcher_emb.embedding_dim
    pitcher_emb_slice = slice(cfg.d_model, cfg.d_model + pe_dim)

    _mem("after train (pre-extract)")
    print("extracting catcher-hybrid embeddings...")
    emb_train = extract_catcher_hybrid_embeddings(
        model, index, p_map, c_map, full_df, train_idx, cfg
    )
    _mem("after emb_train")
    emb_val = extract_catcher_hybrid_embeddings(model, index, p_map, c_map, full_df, val_idx, cfg)
    emb_test = extract_catcher_hybrid_embeddings(model, index, p_map, c_map, full_df, test_idx, cfg)
    _mem("after emb_val+test")
    n_emb = emb_train.shape[1]

    # The transformer + sequence index have served their purpose (embeddings
    # extracted, cluster embeddings already set). Free them — and the GPU
    # allocator cache — before the booster phase. Rookie eval needs only
    # `clusters`, the streak booster, and emb_test.
    del model, index, p_map, c_map
    gc.collect()
    if torch.cuda.is_available():
        torch.cuda.empty_cache()

    results: list[PitchTypeMetrics] = []
    n_streak = len(STREAK_FEATURE_COLS)
    tab_cols = streak_feat  # superset; the streak cols are the LAST n_streak

    # Precompute rookie-eval inputs from full_df NOW, then free full_df.
    n_test = len(test_df)
    cum = compute_cum_pitch_count(full_df)
    is_rookie_test = cum[test_idx] < ROOKIE_PITCH_THRESHOLD
    cluster_ids_test = assign_clusters_streaming(full_df, clusters)[test_idx]
    del cum, full_df
    gc.collect()
    _mem("after rookie inputs (full_df freed)")

    # Pull the (small) tabular feature arrays, free the split frames, THEN
    # build the embedding+tabular hstacks one split at a time — freeing each
    # raw embedding the instant it is copied in. This keeps the peak to ~one
    # train-size array plus its transient copy, never all three embeddings and
    # all three matrices at once. The context-only matrices are later a column
    # slice of these (drop the last n_streak cols) — no second full hstack.
    feat_names = [f"emb_{i}" for i in range(n_emb)] + list(tab_cols)
    tab_tr = train_df[tab_cols].to_numpy(np.float32)
    tab_va = val_df[tab_cols].to_numpy(np.float32)
    tab_test = test_df[tab_cols].to_numpy(np.float32)
    del train_df, val_df, test_df
    gc.collect()
    print("\nbuilding booster feature matrices...")
    xtr = np.hstack([emb_train, tab_tr])
    del emb_train, tab_tr
    gc.collect()
    xva = np.hstack([emb_val, tab_va])
    del emb_val, tab_va
    gc.collect()
    xte = np.hstack([emb_test, tab_test])  # keep emb_test + tab_test for rookie
    gc.collect()
    _mem("after matrices (embs + split frames freed)")

    # ---- Idea 1: streak booster (full feature set) ---------------------
    print("\n[1] Catcher-Hybrid + Context + Streak...")
    t0 = time.perf_counter()
    b_streak = _train_lgbm(xtr, train_y, xva, val_y, cfg.seed, cfg.lgbm_num_threads)
    p_streak = np.asarray(b_streak.predict(xte), dtype=np.float32)
    m_streak = compute_pitch_type_metrics(
        "Context + Streak",
        y_test,
        p_streak,
        t_time + (time.perf_counter() - t0),
    )
    print(f"  acc={m_streak.accuracy:.4f}  top2={m_streak.top2_accuracy:.4f}")
    _mem("after streak booster")

    streak_importance = {
        n: float(v)
        for n, v in zip(
            feat_names,
            b_streak.feature_importance(importance_type="gain"),
            strict=True,
        )
        if n in STREAK_FEATURE_COLS
    }
    print("  streak feature importance (gain):")
    for n, v in sorted(streak_importance.items(), key=lambda x: -x[1]):
        print(f"    {n:<24s} {v:>12.0f}")

    # SHAP sample (small) grabbed now so the big xte can be freed afterwards.
    rng = np.random.default_rng(cfg.seed)
    samp = rng.choice(xte.shape[0], size=min(args.shap_sample, xte.shape[0]), replace=False)
    shap_x = xte[samp].copy()

    # ---- Idea 1 reference: context-only = streak matrices minus streak cols.
    # A column slice (view) keeps us from building a second full hstack. ----
    print("\n[1b] Catcher-Hybrid + Context (reference, no streak)...")
    t0 = time.perf_counter()
    b_ref = _train_lgbm(
        xtr[:, :-n_streak],
        train_y,
        xva[:, :-n_streak],
        val_y,
        cfg.seed,
        cfg.lgbm_num_threads,
    )
    p_ref = np.asarray(b_ref.predict(xte[:, :-n_streak]), dtype=np.float32)
    m_ref = compute_pitch_type_metrics(
        "Context (ref)",
        y_test,
        p_ref,
        t_time + (time.perf_counter() - t0),
    )
    print(f"  acc={m_ref.accuracy:.4f}  top2={m_ref.top2_accuracy:.4f}")
    del b_ref, p_ref
    gc.collect()

    # Reference first, then +streak (matches the plot's 2-bar order/colours).
    results.append(m_ref)
    results.append(m_streak)

    # Free the big matrices; rookie eval needs only emb_test / tab_test.
    del xtr, xva, xte
    gc.collect()
    _mem("after both boosters (matrices freed)")

    # ---- Idea 2: SHAP --------------------------------------------------
    print("\n[3] SHAP analysis on the streak booster...")
    shap_ranked = _shap_analysis(b_streak, shap_x, feat_names, n_emb, out_dir)
    del shap_x
    gc.collect()

    # ---- Idea 3: rookie prototyping ------------------------------------
    print("\n[4] rookie prototype-clustering evaluation...")
    n_rookie = int(is_rookie_test.sum())
    print(f"  rookie test pitches: {n_rookie:,} / {n_test:,} ({n_rookie / max(n_test, 1):.2%})")

    def _rookie_acc(proba):
        if n_rookie == 0:
            return float("nan")
        return float(
            accuracy_score(
                y_test[is_rookie_test],
                proba[is_rookie_test].argmax(1),
            )
        )

    rookie_rows = {"default": _rookie_acc(p_streak)}

    if n_rookie > 0:
        variants = [
            ("features", True, False),
            ("embedding", False, True),
            ("features+embedding", True, True),
        ]
        for label, sub_f, sub_e in variants:
            tab2, emb2 = apply_prototype_substitution(
                tab_test,
                tab_cols,
                emb_test,
                cluster_ids=cluster_ids_test,
                is_rookie=is_rookie_test,
                clusters=clusters,
                pitcher_emb_slice=pitcher_emb_slice,
                substitute_features=sub_f,
                substitute_embedding=sub_e,
            )
            x_proto = np.hstack([emb2, tab2])
            p_proto = np.asarray(b_streak.predict(x_proto), dtype=np.float32)
            rookie_rows[label] = _rookie_acc(p_proto)

    print("  rookie-subset accuracy:")
    for k, v in rookie_rows.items():
        print(f"    {k:<22s} {v:.4f}")

    # ---- Comparison plot (model rows) ----------------------------------
    names = [r.name for r in results]
    fig, axes = plt.subplots(1, 4, figsize=(18, 5))
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
    fig.suptitle("Streak-feature ablation (2025 holdout)", fontweight="bold")
    fig.tight_layout()
    fig.savefig(out_dir / "streak_comparison.png", dpi=150)
    plt.close(fig)

    if n_rookie > 0:
        fig, ax = plt.subplots(figsize=(8, 5))
        ks = list(rookie_rows.keys())
        vs = [rookie_rows[k] for k in ks]
        bars = ax.bar(ks, vs, color="#7c3aed", alpha=0.85)
        best = int(np.argmax(vs))
        bars[best].set_edgecolor("black")
        bars[best].set_linewidth(2)
        for i, v in enumerate(vs):
            ax.text(i, v, f"{v:.4f}", ha="center", va="bottom", fontsize=9)
        ax.set_title(f"Rookie-subset accuracy ({n_rookie:,} pitches, <500 career)")
        ax.set_ylabel("Top-1 accuracy")
        ax.tick_params(axis="x", rotation=15, labelsize=9)
        ax.grid(axis="y", alpha=0.3)
        fig.tight_layout()
        fig.savefig(out_dir / "rookie_prototyping.png", dpi=150)
        plt.close(fig)
        print(f"  wrote {out_dir / 'rookie_prototyping.png'}")

    # ---- JSON ----------------------------------------------------------
    json_out = {
        "schema_version": 1,
        "artifact_name": "final_experiments",
        "streak_models": [
            {
                "name": r.name,
                "accuracy": r.accuracy,
                "top2_accuracy": r.top2_accuracy,
                "logloss": r.logloss,
                "calibration_ece": r.calibration_ece,
            }
            for r in results
        ],
        "streak_feature_importance": streak_importance,
        "shap_importance": shap_ranked,
        "rookie": {
            "threshold": ROOKIE_PITCH_THRESHOLD,
            "n_clusters": clusters.n_clusters,
            "n_established": clusters.n_established,
            "n_rookie_test": n_rookie,
            "n_test": n_test,
            "rookie_accuracy": rookie_rows,
        },
    }
    (out_dir / "final_experiments.json").write_text(json.dumps(json_out, indent=2))
    print(f"\n  wrote {out_dir / 'final_experiments.json'}")


if __name__ == "__main__":
    main()
