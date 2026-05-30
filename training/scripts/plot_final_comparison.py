"""Render the four-model accuracy comparison bar chart from the eval JSONs.

Pulls the three combined-experiment models + the streak winner and draws a
zoomed accuracy bar chart (the models cluster within ~0.2pp, so the y-axis is
zoomed with value labels for honesty).

Usage:
  uv run python scripts/plot_final_comparison.py
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import matplotlib.pyplot as plt


def main() -> None:
    ap = argparse.ArgumentParser(description="Four-model accuracy bar chart.")
    ap.add_argument(
        "--combined", type=Path, default=Path("data/eval/pitch_combined/combined_experiment.json")
    )
    ap.add_argument("--stage2", type=Path, default=Path("data/eval/pitch_final/final_stage2.json"))
    ap.add_argument(
        "--out", type=Path, default=Path("data/eval/pitch_final/four_model_accuracy.png")
    )
    args = ap.parse_args()

    models: dict[str, dict] = {}
    if args.combined.exists():
        for m in json.loads(args.combined.read_text())["models"]:
            models[m["name"]] = m
    if args.stage2.exists():
        for m in json.loads(args.stage2.read_text())["streak_models"]:
            models.setdefault(m["name"], m)

    wanted = [
        "Catcher-Hybrid (base)",
        "Catcher-Hybrid + Context",
        "Hybrid + Context",
        "Context + Streak",
    ]
    rows = [(n, models[n]["accuracy"]) for n in wanted if n in models]
    rows.sort(key=lambda r: -r[1])  # accuracy descending
    if not rows:
        print("No model metrics found — run the experiments first.")
        return

    names = [r[0] for r in rows]
    accs = [r[1] for r in rows]
    best = max(range(len(accs)), key=lambda i: accs[i])

    fig, ax = plt.subplots(figsize=(10, 6))
    colors = ["#059669" if i == best else "#0891b2" for i in range(len(names))]
    bars = ax.bar(names, accs, color=colors, alpha=0.9)
    bars[best].set_edgecolor("black")
    bars[best].set_linewidth(2)

    lo, hi = min(accs) - 0.0010, max(accs) + 0.0020
    ax.set_ylim(lo, hi)
    ax.set_ylabel("Top-1 accuracy — 2025 holdout (709,906 pitches)")
    ax.set_title("Pitch-type model accuracy — 4 architectures  (y-axis zoomed)")
    ax.tick_params(axis="x", rotation=12, labelsize=9)
    ax.grid(axis="y", alpha=0.3)
    for b, a in zip(bars, accs, strict=True):
        ax.text(
            b.get_x() + b.get_width() / 2,
            a,
            f"{a * 100:.2f}%",
            ha="center",
            va="bottom",
            fontweight="bold",
            fontsize=10,
        )
    ax.text(
        0.99,
        0.02,
        "best = Context + Streak",
        transform=ax.transAxes,
        ha="right",
        va="bottom",
        fontsize=8,
        color="#059669",
    )
    fig.tight_layout()
    args.out.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(args.out, dpi=150)
    plt.close(fig)
    print(f"wrote {args.out}")
    for n, a in rows:
        print(f"  {n:<28s} {a * 100:.2f}%")


if __name__ == "__main__":
    main()
