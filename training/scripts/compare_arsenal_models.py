"""Arsenal-aware pitch model comparison.

Compares models with and without pitcher arsenal features and dynamic
masking. Focuses on the hybrid (Transformer + LightGBM) architecture
since it's the current best performer.

Usage:
  uv run python training/scripts/compare_arsenal_models.py
  uv run python training/scripts/compare_arsenal_models.py --skip-baselines
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

from bullpen_training.pitch_comparison.arsenal import (
    ARSENAL_FEATURE_COLS,
    apply_hard_mask,
    apply_soft_prior,
    arsenal_features_for_df,
    compute_arsenal_stats,
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
from bullpen_training.pitch_comparison.models import (
    predict_lgbm,
    train_lgbm,
)
from bullpen_training.pitch_comparison.sequence_data import (
    PitcherSequenceIndex,
    PitchSequenceDataset,
    collate_sequences,
)
from bullpen_training.pitch_comparison.transformer_model import (
    PitchSequenceTransformer,
    train_transformer,
)

COLORS = {
    "LightGBM": "#2563eb",
    "LightGBM + Arsenal": "#1d4ed8",
    "Hybrid": "#0891b2",
    "Hybrid + Arsenal": "#0e7490",
    "Hybrid + Hard Mask": "#155e75",
    "Hybrid + Soft Prior": "#164e63",
    "LGBM + Arsenal": "#059669",
    "LGBM + Hard Mask": "#047857",
    "LGBM + Soft Prior": "#065f46",
}


def _train_lgbm_with_features(
    train_x: np.ndarray,
    train_y: np.ndarray,
    val_x: np.ndarray,
    val_y: np.ndarray,
    *,
    seed: int = 42,
) -> lgb.Booster:
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
    }
    dt = lgb.Dataset(train_x, label=train_y)
    dv = lgb.Dataset(val_x, label=val_y, reference=dt)
    return lgb.train(
        params, dt, 2000,
        valid_sets=[dt, dv], valid_names=["t", "v"],
        callbacks=[
            lgb.early_stopping(50, first_metric_only=True, verbose=False),
        ],
    )


def _extract_transformer_embeddings(
    model: PitchSequenceTransformer,
    index: PitcherSequenceIndex,
    indices: np.ndarray,
    config: ExperimentConfig,
) -> np.ndarray:
    import torch
    from torch.utils.data import DataLoader

    device = torch.device(config.resolve_device())
    model.to(device).eval()
    ds = PitchSequenceDataset(index, indices, config.seq_window)
    loader = DataLoader(
        ds, batch_size=config.transformer_batch_size * 2,
        shuffle=False, collate_fn=collate_sequences, num_workers=0,
    )
    embs: list[np.ndarray] = []
    with torch.no_grad():
        for seq, pad_mask, _f, _t in loader:
            emb = model.encode(
                seq.to(device), pad_mask.to(device),
            ).cpu().numpy()
            embs.append(emb)
    return np.concatenate(embs, axis=0).astype(np.float32)


def _plot_summary(
    results: list[PitchTypeMetrics], out_dir: Path,
) -> None:
    names = [r.name for r in results]
    colors = [COLORS.get(n, "#888") for n in names]

    fig, axes = plt.subplots(1, 4, figsize=(22, 6))
    metrics = [
        ("Top-1 Accuracy", [r.accuracy for r in results], True),
        ("Top-2 Accuracy", [r.top2_accuracy for r in results], True),
        ("Log Loss", [r.logloss for r in results], False),
        ("Calibration ECE", [r.calibration_ece for r in results], False),
    ]
    for ax, (title, vals, higher) in zip(axes, metrics, strict=True):
        bars = ax.bar(names, vals, color=colors, alpha=0.85)
        ax.set_title(title, fontsize=11)
        ax.grid(axis="y", alpha=0.3)
        ax.tick_params(axis="x", rotation=35, labelsize=7)
        best = int(np.argmax(vals)) if higher else int(np.argmin(vals))
        bars[best].set_edgecolor("black")
        bars[best].set_linewidth(2)
        for i, v in enumerate(vals):
            ax.text(i, v, f"{v:.4f}", ha="center", va="bottom",
                    fontsize=7)

    fig.suptitle(
        "Arsenal-Aware Model Comparison (2025 holdout)",
        fontsize=14, fontweight="bold",
    )
    fig.tight_layout()
    fig.savefig(out_dir / "arsenal_comparison.png", dpi=150)
    plt.close(fig)
    print(f"  wrote {out_dir / 'arsenal_comparison.png'}")


def _plot_accuracy_vs_arsenal_size(
    test_df: pd.DataFrame,
    results_dict: dict[str, np.ndarray],
    out_dir: Path,
) -> None:
    """Accuracy binned by pitcher arsenal size."""
    sizes = test_df["arsenal_size"].values
    y_true = test_df["pitch_type_int"].values

    bins = [(1, 3, "2-3 pitches"), (4, 5, "4-5 pitches"), (6, 8, "6+ pitches")]
    fig, ax = plt.subplots(figsize=(10, 6))
    x = np.arange(len(bins))
    width = 0.8 / len(results_dict)
    colors_list = list(COLORS.values())

    for idx, (model_name, proba) in enumerate(results_dict.items()):
        y_pred = proba.argmax(axis=1)
        accs = []
        for lo, hi, _label in bins:
            mask = (sizes >= lo) & (sizes <= hi)
            if mask.sum() > 0:
                accs.append(float((y_pred[mask] == y_true[mask]).mean()))
            else:
                accs.append(0.0)
        offset = (idx - len(results_dict) / 2 + 0.5) * width
        ax.bar(x + offset, accs, width, label=model_name,
               color=colors_list[idx % len(colors_list)], alpha=0.85)

    ax.set_xticks(x)
    ax.set_xticklabels([b[2] for b in bins])
    ax.set_xlabel("Pitcher Arsenal Size")
    ax.set_ylabel("Accuracy")
    ax.set_title("Accuracy vs Pitcher Arsenal Size")
    ax.legend(fontsize=8)
    ax.grid(axis="y", alpha=0.3)
    fig.tight_layout()
    fig.savefig(out_dir / "accuracy_vs_arsenal_size.png", dpi=150)
    plt.close(fig)
    print(f"  wrote {out_dir / 'accuracy_vs_arsenal_size.png'}")


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


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Arsenal-aware model comparison.",
    )
    parser.add_argument("--limit", type=int, default=None)
    parser.add_argument(
        "--out-dir", type=Path,
        default=Path("data/eval/pitch_arsenal"),
    )
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--skip-baselines", action="store_true")
    parser.add_argument("--season-from", type=int, default=2015)
    parser.add_argument("--season-to", type=int, default=2025)
    args = parser.parse_args()

    train_years = tuple(
        y for y in range(args.season_from, args.season_to + 1)
        if y not in (2024, 2025)
    )
    cfg = ExperimentConfig(
        seed=args.seed, limit=args.limit, out_dir=args.out_dir,
        season_from=args.season_from, season_to=args.season_to,
        train_years=train_years,
    )

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

    # --- Compute arsenal ---
    print("computing arsenal stats...")
    full_df_for_mask = pd.concat(
        [train_df, val_df, test_df], ignore_index=False,
    )
    full_train_mask = np.array(
        full_df_for_mask.index.isin(train_df.index),
    )
    arsenal_stats = compute_arsenal_stats(
        full_df_for_mask, full_train_mask,
    )
    print(f"  {len(arsenal_stats)} pitchers with arsenal profiles")

    print("building arsenal features...")
    arsenal_feats = arsenal_features_for_df(
        full_df_for_mask, arsenal_stats, full_train_mask,
    )
    # Attach to splits.
    for _df_name, df in [
        ("train", train_df), ("val", val_df), ("test", test_df),
    ]:
        for col in arsenal_feats.columns:
            df[col] = arsenal_feats.loc[df.index, col].values
    del full_df_for_mask, arsenal_feats
    gc.collect()

    y_test = test_df["pitch_type_int"].values.astype(int)
    all_results: list[PitchTypeMetrics] = []
    predictions: dict[str, np.ndarray] = {}

    feat = list(FEATURE_COLS)
    feat_arsenal = list(FEATURE_COLS) + list(ARSENAL_FEATURE_COLS)

    import time

    # --- 1. LightGBM baseline ---
    if not args.skip_baselines:
        print("\n[1] LightGBM (no arsenal)...")
        t0 = time.perf_counter()
        lgbm_b, _ = train_lgbm(train_df, val_df, seed=cfg.seed)
        lgbm_p = predict_lgbm(lgbm_b, test_df)
        t1 = time.perf_counter() - t0
        m = compute_pitch_type_metrics(
            "LightGBM", y_test, lgbm_p.pitch_type_proba, t1,
        )
        all_results.append(m)
        predictions["LightGBM"] = lgbm_p.pitch_type_proba
        print(f"  acc={m.accuracy:.4f}  done in {t1:.1f}s")
        del lgbm_b, lgbm_p
        gc.collect()

    # --- 2. LightGBM + Arsenal features ---
    print("\n[2] LightGBM + Arsenal features...")
    t0 = time.perf_counter()
    train_x = train_df[feat_arsenal].values.astype(np.float32)
    val_x = val_df[feat_arsenal].values.astype(np.float32)
    test_x = test_df[feat_arsenal].values.astype(np.float32)
    train_y = train_df["pitch_type_int"].values.astype(int)
    val_y = val_df["pitch_type_int"].values.astype(int)
    booster_a = _train_lgbm_with_features(
        train_x, train_y, val_x, val_y, seed=cfg.seed,
    )
    proba_a = np.asarray(
        booster_a.predict(test_x), dtype=np.float32,
    )
    t1 = time.perf_counter() - t0
    m = compute_pitch_type_metrics(
        "LGBM + Arsenal", y_test, proba_a, t1,
    )
    all_results.append(m)
    predictions["LGBM + Arsenal"] = proba_a
    print(f"  acc={m.accuracy:.4f}  done in {t1:.1f}s")

    # --- 3. LightGBM + Arsenal + Hard Mask ---
    print("\n[3] LightGBM + Arsenal + Hard Mask...")
    active_cols = [f"active_{pt}" for pt in PITCH_TYPE_CLASSES]
    test_active = test_df[active_cols].values.astype(np.float32)
    proba_hm = apply_hard_mask(proba_a.copy(), test_active)
    m = compute_pitch_type_metrics(
        "LGBM + Hard Mask", y_test, proba_hm, t1,
    )
    all_results.append(m)
    predictions["LGBM + Hard Mask"] = proba_hm
    print(f"  acc={m.accuracy:.4f}")

    # --- 4. LightGBM + Arsenal + Soft Prior ---
    print("\n[4] LightGBM + Arsenal + Soft Prior...")
    prior_cols = [f"context_prior_{pt}" for pt in PITCH_TYPE_CLASSES]
    test_priors = test_df[prior_cols].values.astype(np.float32)
    logits_a = np.log(proba_a + 1e-9)
    proba_sp = apply_soft_prior(logits_a, test_priors, temperature=1.0)
    m = compute_pitch_type_metrics(
        "LGBM + Soft Prior", y_test, proba_sp, t1,
    )
    all_results.append(m)
    predictions["LGBM + Soft Prior"] = proba_sp
    print(f"  acc={m.accuracy:.4f}")
    del booster_a, proba_a, proba_hm, proba_sp
    gc.collect()

    # --- 5. Hybrid (Transformer + LightGBM), no arsenal ---
    print("\n[5] Hybrid (no arsenal)...")
    full_df = pd.concat(
        [train_df, val_df, test_df], ignore_index=True,
    )
    t0 = time.perf_counter()
    tf_model, tf_index, _ = train_transformer(
        train_df, val_df, full_df, cfg,
    )
    train_idx = np.where(
        full_df["season"].isin(cfg.train_years).values,
    )[0].astype(np.int32)
    val_idx = np.where(
        full_df["season"].isin(cfg.val_years).values,
    )[0].astype(np.int32)
    test_idx = np.where(
        full_df["season"].isin(cfg.test_years).values,
    )[0].astype(np.int32)

    train_emb = _extract_transformer_embeddings(
        tf_model, tf_index, train_idx, cfg,
    )
    val_emb = _extract_transformer_embeddings(
        tf_model, tf_index, val_idx, cfg,
    )
    test_emb = _extract_transformer_embeddings(
        tf_model, tf_index, test_idx, cfg,
    )

    hy_train_x = np.hstack([
        train_emb, train_df[feat].values.astype(np.float32),
    ])
    hy_val_x = np.hstack([
        val_emb, val_df[feat].values.astype(np.float32),
    ])
    hy_test_x = np.hstack([
        test_emb, test_df[feat].values.astype(np.float32),
    ])
    hy_booster = _train_lgbm_with_features(
        hy_train_x, train_y,
        hy_val_x, val_y,
        seed=cfg.seed,
    )
    hy_proba = np.asarray(
        hy_booster.predict(hy_test_x), dtype=np.float32,
    )
    t1 = time.perf_counter() - t0
    m = compute_pitch_type_metrics(
        "Hybrid", y_test, hy_proba, t1,
    )
    all_results.append(m)
    predictions["Hybrid"] = hy_proba
    print(f"  acc={m.accuracy:.4f}  done in {t1:.1f}s")

    # --- 6. Hybrid + Arsenal features ---
    print("\n[6] Hybrid + Arsenal features...")
    t0 = time.perf_counter()
    ha_train_x = np.hstack([
        train_emb,
        train_df[feat_arsenal].values.astype(np.float32),
    ])
    ha_val_x = np.hstack([
        val_emb,
        val_df[feat_arsenal].values.astype(np.float32),
    ])
    ha_test_x = np.hstack([
        test_emb,
        test_df[feat_arsenal].values.astype(np.float32),
    ])
    ha_booster = _train_lgbm_with_features(
        ha_train_x, train_y,
        ha_val_x, val_y,
        seed=cfg.seed,
    )
    ha_proba = np.asarray(
        ha_booster.predict(ha_test_x), dtype=np.float32,
    )
    t1_ha = time.perf_counter() - t0 + t1
    m = compute_pitch_type_metrics(
        "Hybrid + Arsenal", y_test, ha_proba, t1_ha,
    )
    all_results.append(m)
    predictions["Hybrid + Arsenal"] = ha_proba
    print(f"  acc={m.accuracy:.4f}  done in {t1_ha:.1f}s")

    # --- 7. Hybrid + Arsenal + Hard Mask ---
    print("\n[7] Hybrid + Arsenal + Hard Mask...")
    ha_hm = apply_hard_mask(ha_proba.copy(), test_active)
    m = compute_pitch_type_metrics(
        "Hybrid + Hard Mask", y_test, ha_hm, t1_ha,
    )
    all_results.append(m)
    predictions["Hybrid + Hard Mask"] = ha_hm
    print(f"  acc={m.accuracy:.4f}")

    # --- 8. Hybrid + Arsenal + Soft Prior ---
    print("\n[8] Hybrid + Arsenal + Soft Prior...")
    ha_logits = np.log(ha_proba + 1e-9)
    ha_sp = apply_soft_prior(ha_logits, test_priors, temperature=1.0)
    m = compute_pitch_type_metrics(
        "Hybrid + Soft Prior", y_test, ha_sp, t1_ha,
    )
    all_results.append(m)
    predictions["Hybrid + Soft Prior"] = ha_sp
    print(f"  acc={m.accuracy:.4f}")

    # --- Results ---
    _print_table(all_results)

    out_dir = cfg.out_dir
    out_dir.mkdir(parents=True, exist_ok=True)
    _plot_summary(all_results, out_dir)
    _plot_accuracy_vs_arsenal_size(test_df, predictions, out_dir)

    # Save JSON.
    json_out = {
        "schema_version": 1,
        "artifact_name": "arsenal_comparison",
        "models": [
            {
                "name": r.name,
                "accuracy": r.accuracy,
                "top2_accuracy": r.top2_accuracy,
                "logloss": r.logloss,
                "calibration_ece": r.calibration_ece,
                "train_time_sec": r.train_time_sec,
            }
            for r in all_results
        ],
    }
    json_path = out_dir / "arsenal_comparison.json"
    json_path.write_text(json.dumps(json_out, indent=2))
    print(f"  wrote {json_path}")


if __name__ == "__main__":
    main()
