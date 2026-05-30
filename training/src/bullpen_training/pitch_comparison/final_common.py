"""Staging I/O + shared helpers for the staged "final experiments" pipeline.

The final experiment (streak / SHAP / rookie prototyping on top of the
catcher-aware transformer) is split into manually-run stages so no single run
overloads the training machine (see the split-ml-training-stages memory):

  stage 1  (GPU)        scripts/final_stage1_embeddings.py
  stage 2  (CPU heavy)  scripts/final_stage2_boosters.py
  stage 3  (CPU light)  scripts/final_stage3_analysis.py

Each stage persists into ``STAGE_DIR`` and the next loads from there — no
recompute, no chaining inside one process. Large arrays/weights live under
``training/artifacts/**`` (gitignored).
"""

from __future__ import annotations

import json
import pickle
from pathlib import Path

import numpy as np

STAGE_DIR = Path("artifacts/pitch_final_stage")


def save_array(stage_dir: Path, name: str, arr: np.ndarray) -> None:
    np.save(stage_dir / f"{name}.npy", arr)


def load_array(stage_dir: Path, name: str) -> np.ndarray:
    return np.load(stage_dir / f"{name}.npy", allow_pickle=False)


def save_pickle(stage_dir: Path, name: str, obj) -> None:
    with open(stage_dir / f"{name}.pkl", "wb") as fh:
        pickle.dump(obj, fh)


def load_pickle(stage_dir: Path, name: str):
    with open(stage_dir / f"{name}.pkl", "rb") as fh:
        return pickle.load(fh)


def save_meta(stage_dir: Path, meta: dict) -> None:
    (stage_dir / "stage_meta.json").write_text(json.dumps(meta, indent=2))


def load_meta(stage_dir: Path) -> dict:
    p = stage_dir / "stage_meta.json"
    if not p.exists():
        raise FileNotFoundError(f"{p} not found — run final_stage1_embeddings.py first.")
    return json.loads(p.read_text())


def shap_importance(booster, x_sample, feat_names, n_emb, out_dir) -> dict:
    """Mean |SHAP| ranking; the embedding dims are grouped into one bar."""
    try:
        import shap
    except ImportError:
        print("  shap not installed — run `uv add shap`")
        return {}
    import matplotlib.pyplot as plt

    explainer = shap.TreeExplainer(booster)
    sv = explainer.shap_values(x_sample)
    if isinstance(sv, list):
        arr = np.stack(sv, axis=0)
    else:
        arr = np.asarray(sv)
        arr = np.transpose(arr, (2, 0, 1)) if arr.ndim == 3 else arr[None, ...]
    mean_abs = np.abs(arr).mean(axis=(0, 1))

    named = {feat_names[i]: float(mean_abs[i]) for i in range(n_emb, len(feat_names))}
    named["sequence+entity_embedding"] = float(mean_abs[:n_emb].sum())
    ranked = dict(sorted(named.items(), key=lambda x: -x[1]))

    labels, vals = list(ranked.keys()), list(ranked.values())
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


__all__ = (
    "STAGE_DIR",
    "load_array",
    "load_meta",
    "load_pickle",
    "save_array",
    "save_meta",
    "save_pickle",
    "shap_importance",
)
