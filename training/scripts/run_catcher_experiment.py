"""Catcher-influence experiment.

Research question: does catcher identity materially alter pitch calling?

Compares:
  1. Pitcher embeddings only (baseline)
  2. Pitcher + catcher embeddings

If catcher embeddings improve prediction, catchers measurably shape
sequencing. Also clusters catcher embeddings to find framing/sequencing
archetypes.

Requires the V012/V013 expanded re-pull (catcher_id column).

Usage:
  uv run python training/scripts/run_catcher_experiment.py
"""

from __future__ import annotations

import argparse
import gc
import json
from pathlib import Path

import matplotlib.pyplot as plt
import numpy as np
import pandas as pd

from bullpen_training.pitch_comparison.config import ExperimentConfig
from bullpen_training.pitch_comparison.data import FEATURE_COLS
from bullpen_training.pitch_comparison.data_enriched import (
    load_enriched_data,
    prepare_enriched_datasets,
)
from bullpen_training.pitch_comparison.metrics import (
    PitchTypeMetrics,
    compute_pitch_type_metrics,
)
from bullpen_training.pitch_comparison.transformer_catcher import (
    extract_catcher_embeddings,
    predict_catcher_transformer,
    train_catcher_transformer,
)


def main() -> None:
    parser = argparse.ArgumentParser(description="Catcher-influence experiment.")
    parser.add_argument("--limit", type=int, default=None)
    parser.add_argument(
        "--out-dir", type=Path, default=Path("data/eval/pitch_catcher"),
    )
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
    print("preparing splits...")
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

    # Check catcher_id coverage.
    cov = (test_df["catcher_id"] > 0).mean()
    print(f"  catcher_id coverage in test: {cov:.1%}")
    if cov < 0.5:
        print("  WARNING: low catcher coverage — re-pull may be incomplete.")

    for df in [train_df, val_df, test_df]:
        for col in FEATURE_COLS:
            df[col] = pd.to_numeric(df[col], errors="coerce")

    full_df = pd.concat([train_df, val_df, test_df], ignore_index=True)
    y_test = test_df["pitch_type_int"].values.astype(int)
    results: list[PitchTypeMetrics] = []

    # === 1. Pitcher embeddings only ===
    print("\n[1/2] Pitcher embeddings only...")
    p_model, p_index, p_pm, p_cm, p_time = train_catcher_transformer(
        train_df, val_df, full_df, cfg,
        use_catcher=False, variant_name="Pitcher-only",
    )
    p_preds = predict_catcher_transformer(
        p_model, p_index, p_pm, p_cm, full_df, cfg,
    )
    m = compute_pitch_type_metrics(
        "Pitcher only", y_test, p_preds.pitch_type_proba, p_time,
    )
    results.append(m)
    print(f"  acc={m.accuracy:.4f}  top2={m.top2_accuracy:.4f}")
    del p_model, p_index, p_preds
    gc.collect()

    # === 2. Pitcher + catcher embeddings ===
    print("\n[2/2] Pitcher + catcher embeddings...")
    c_model, c_index, c_pm, c_cm, c_time = train_catcher_transformer(
        train_df, val_df, full_df, cfg,
        use_catcher=True, variant_name="Pitcher+Catcher",
    )
    c_preds = predict_catcher_transformer(
        c_model, c_index, c_pm, c_cm, full_df, cfg,
    )
    m = compute_pitch_type_metrics(
        "Pitcher + Catcher", y_test, c_preds.pitch_type_proba, c_time,
    )
    results.append(m)
    print(f"  acc={m.accuracy:.4f}  top2={m.top2_accuracy:.4f}")

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

    delta = results[1].accuracy - results[0].accuracy
    print(f"\nCatcher effect on accuracy: {delta:+.4f} "
          f"({'catcher helps' if delta > 0.001 else 'no material effect'})")

    # Plot comparison.
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
        bars = ax.bar(names, vals, color=["#7c3aed", "#0891b2"], alpha=0.85)
        ax.set_title(title)
        ax.grid(axis="y", alpha=0.3)
        ax.tick_params(axis="x", rotation=15, labelsize=9)
        best = int(np.argmax(vals)) if higher else int(np.argmin(vals))
        bars[best].set_edgecolor("black")
        bars[best].set_linewidth(2)
        for i, v in enumerate(vals):
            ax.text(i, v, f"{v:.4f}", ha="center", va="bottom", fontsize=9)
    fig.suptitle("Catcher Influence on Pitch Prediction (2025)", fontweight="bold")
    fig.tight_layout()
    fig.savefig(out_dir / "catcher_comparison.png", dpi=150)
    plt.close(fig)
    print(f"  wrote {out_dir / 'catcher_comparison.png'}")

    # === Catcher embedding clustering (UMAP if available, else t-SNE) ===
    print("\nclustering catcher embeddings...")
    embs, cids = extract_catcher_embeddings(c_model, c_cm)
    if embs.shape[0] > 10:
        coords = None
        try:
            import umap

            reducer = umap.UMAP(n_components=2, random_state=cfg.seed)
            coords = reducer.fit_transform(embs)
            method = "UMAP"
        except ImportError:
            from sklearn.manifold import TSNE

            perp = min(30, embs.shape[0] - 1)
            coords = TSNE(
                n_components=2, random_state=cfg.seed, perplexity=perp,
            ).fit_transform(embs)
            method = "t-SNE"

        fig, ax = plt.subplots(figsize=(10, 8))
        ax.scatter(coords[:, 0], coords[:, 1], alpha=0.5, s=20, c="#0891b2")
        ax.set_title(f"Catcher Embeddings ({method})")
        ax.set_xlabel(f"{method} 1")
        ax.set_ylabel(f"{method} 2")
        ax.grid(alpha=0.2)
        fig.tight_layout()
        fig.savefig(out_dir / "catcher_embeddings.png", dpi=150)
        plt.close(fig)
        print(f"  wrote {out_dir / 'catcher_embeddings.png'} "
              f"({len(cids)} catchers, {method})")

    json_out = {
        "schema_version": 1,
        "artifact_name": "catcher_experiment",
        "catcher_coverage_test": float(cov),
        "catcher_accuracy_delta": float(delta),
        "models": [
            {
                "name": r.name, "accuracy": r.accuracy,
                "top2_accuracy": r.top2_accuracy, "logloss": r.logloss,
                "calibration_ece": r.calibration_ece,
            }
            for r in results
        ],
    }
    (out_dir / "catcher_experiment.json").write_text(
        json.dumps(json_out, indent=2),
    )
    print(f"  wrote {out_dir / 'catcher_experiment.json'}")


if __name__ == "__main__":
    main()
