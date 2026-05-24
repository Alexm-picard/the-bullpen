"""Eval artifact generator (Phase 2a.7).

Produces an `eval/` directory under a model version dir, with the
artifacts the Ops dashboard (Phase 4e) reads and the recruiter-clicked
README links to:

    eval/
      metrics.json              # per-fold + summary, machine-readable
      reliability_diagrams.png  # per-class calibration (post-calibration)
      confusion_matrix.png      # row-normalised heatmap
      segment_metrics.csv       # per (handedness, park, count, inning, month)
      temporal_cv_results.csv   # per-fold rolling-origin CV result
      feature_importance.csv    # LightGBM only; LR's coefficients aren't comparable
      commit_sha.txt
      data_hash.txt

Reused for LR baseline, LightGBM pre-pitch, post-pitch, MLP, and the
batted-ball LightGBM head.
"""

from __future__ import annotations

import hashlib
import json
import subprocess
from dataclasses import dataclass
from pathlib import Path
from typing import Any, cast

import lightgbm as lgb
import matplotlib.pyplot as plt
import numpy as np
import pandas as pd

from bullpen_training.eval._plots import confusion_matrix_plot, reliability_diagram
from bullpen_training.eval._segments import segment_metrics
from bullpen_training.eval.cv_harness import CVResult


@dataclass(frozen=True)
class ArtifactInputs:
    model_name: str
    model_version: str
    class_labels: list[str]
    cv_result: CVResult
    test_df: pd.DataFrame  # the LAST fold's test set (for diagrams)
    test_predictions: np.ndarray  # post-calibration prob matrix for test_df
    training_data_hash: str  # hash of the persisted training snapshot
    feature_importance: pd.DataFrame | None = None
    segment_cols: tuple[str, ...] = (
        "pitcher_throws",
        "batter_stand",
        "park_id",
        "count_balls",
        "count_strikes",
        "inning",
    )


def _git_commit_sha(repo_root: Path | None = None) -> str:
    try:
        return subprocess.check_output(
            ["git", "rev-parse", "HEAD"],
            cwd=str(repo_root) if repo_root else None,
            text=True,
        ).strip()
    except (subprocess.CalledProcessError, FileNotFoundError):
        return "unknown"


def _make_metrics_json(inputs: ArtifactInputs) -> dict[str, Any]:
    return {
        "model_name": inputs.model_name,
        "model_version": inputs.model_version,
        "class_labels": list(inputs.class_labels),
        "n_folds": len(inputs.cv_result.per_fold),
        "summary": {
            name: {"mean": mean, "std": std}
            for name, (mean, std) in inputs.cv_result.summary.items()
        },
        "per_fold": [
            {
                "fold_id": fr.fold_id,
                "train_rows": fr.train_rows,
                "val_rows": fr.val_rows,
                "test_rows": fr.test_rows,
                "metrics": fr.metrics,
            }
            for fr in inputs.cv_result.per_fold
        ],
    }


def _per_fold_csv(cv_result: CVResult) -> pd.DataFrame:
    rows: list[dict[str, Any]] = []
    for fr in cv_result.per_fold:
        row: dict[str, Any] = {
            "fold_id": fr.fold_id,
            "train_rows": fr.train_rows,
            "val_rows": fr.val_rows,
            "test_rows": fr.test_rows,
        }
        row.update(fr.metrics)
        rows.append(row)
    return pd.DataFrame(rows)


def lightgbm_feature_importance(booster: lgb.Booster, feature_names: list[str]) -> pd.DataFrame:
    """`gain` is the LightGBM-recommended ranking signal (not `split`)."""
    importance = booster.feature_importance(importance_type="gain")
    df = pd.DataFrame(
        {
            "feature": feature_names,
            "importance_gain": importance.astype("float64"),
        }
    )
    return df.sort_values("importance_gain", ascending=False).reset_index(drop=True)


def generate(inputs: ArtifactInputs, model_dir: Path) -> Path:
    eval_dir = model_dir / "eval"
    eval_dir.mkdir(parents=True, exist_ok=True)

    (eval_dir / "metrics.json").write_text(
        json.dumps(_make_metrics_json(inputs), indent=2, sort_keys=False) + "\n"
    )

    rel_fig = reliability_diagram(
        y_true=np.asarray(inputs.test_df["label"], dtype=np.int64),
        y_pred_proba=inputs.test_predictions,
        class_labels=list(inputs.class_labels),
    )
    rel_fig.savefig(eval_dir / "reliability_diagrams.png", dpi=120)
    plt.close(rel_fig)

    cm_fig = confusion_matrix_plot(
        y_true=np.asarray(inputs.test_df["label"], dtype=np.int64),
        y_pred_argmax=inputs.test_predictions.argmax(axis=1),
        class_labels=list(inputs.class_labels),
    )
    cm_fig.savefig(eval_dir / "confusion_matrix.png", dpi=120)
    plt.close(cm_fig)

    seg = segment_metrics(inputs.test_df, inputs.test_predictions, segment_cols=inputs.segment_cols)
    seg.to_csv(eval_dir / "segment_metrics.csv", index=False)

    _per_fold_csv(inputs.cv_result).to_csv(eval_dir / "temporal_cv_results.csv", index=False)

    if inputs.feature_importance is not None:
        inputs.feature_importance.to_csv(eval_dir / "feature_importance.csv", index=False)

    (eval_dir / "commit_sha.txt").write_text(_git_commit_sha() + "\n")
    (eval_dir / "data_hash.txt").write_text(inputs.training_data_hash + "\n")
    return eval_dir


def hash_dataframe(df: pd.DataFrame) -> str:
    """Deterministic SHA-256 of a DataFrame's row content + column names."""
    canonical = df.sort_index(axis=1)
    payload = canonical.to_csv(index=False).encode("utf-8")
    return hashlib.sha256(payload).hexdigest()


def _coerce_pred_proba_array(arr: Any) -> np.ndarray:
    """Centralised cast so callers don't have to import numpy."""
    return cast(np.ndarray, np.asarray(arr, dtype=np.float64))
