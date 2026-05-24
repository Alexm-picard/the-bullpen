"""Unit tests for the eval artifact generator (Phase 2a.7)."""

from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import pandas as pd

from bullpen_training.eval._plots import confusion_matrix_plot, reliability_diagram
from bullpen_training.eval._segments import segment_metrics
from bullpen_training.eval.artifact import (
    ArtifactInputs,
    generate,
    hash_dataframe,
    lightgbm_feature_importance,
)
from bullpen_training.eval.cv_harness import CVResult, FoldResult


def _toy_cv_result() -> CVResult:
    folds = (
        FoldResult(
            fold_id=1,
            train_rows=1000,
            val_rows=200,
            test_rows=200,
            metrics={
                "multiclass_brier": 0.15,
                "multiclass_log_loss": 1.45,
                "expected_calibration_error": 0.005,
            },
        ),
        FoldResult(
            fold_id=2,
            train_rows=1200,
            val_rows=200,
            test_rows=200,
            metrics={
                "multiclass_brier": 0.14,
                "multiclass_log_loss": 1.43,
                "expected_calibration_error": 0.004,
            },
        ),
    )
    summary = {
        "multiclass_brier": (0.145, 0.007),
        "multiclass_log_loss": (1.44, 0.014),
        "expected_calibration_error": (0.0045, 0.0007),
    }
    return CVResult(per_fold=folds, summary=summary)


def _toy_test_predictions(
    n: int = 300, n_classes: int = 5, seed: int = 0
) -> tuple[pd.DataFrame, np.ndarray]:
    rng = np.random.default_rng(seed)
    labels = rng.integers(0, n_classes, n)
    raw = rng.dirichlet(np.ones(n_classes), size=n)
    test_df = pd.DataFrame(
        {
            "label": labels,
            "pitcher_throws": rng.choice(["L", "R"], n),
            "batter_stand": rng.choice(["L", "R"], n),
            "park_id": rng.choice(["NYY", "BOS", "LAD", "COL"], n),
            "count_balls": rng.integers(0, 4, n),
            "count_strikes": rng.integers(0, 3, n),
            "inning": rng.integers(1, 10, n),
        }
    )
    return test_df, raw


def test_reliability_diagram_returns_figure_with_k_axes() -> None:
    n, k = 200, 5
    rng = np.random.default_rng(1)
    y = rng.integers(0, k, n)
    proba = rng.dirichlet(np.ones(k), size=n)
    fig = reliability_diagram(y, proba, [f"c{i}" for i in range(k)], n_bins=5)
    # K class subplots
    assert len(fig.axes) == k


def test_confusion_matrix_plot_has_one_axis_plus_colorbar() -> None:
    n, k = 100, 3
    rng = np.random.default_rng(2)
    y_true = rng.integers(0, k, n)
    y_pred = rng.integers(0, k, n)
    fig = confusion_matrix_plot(y_true, y_pred, ["a", "b", "c"])
    # 1 main axis + 1 colorbar
    assert len(fig.axes) == 2


def test_segment_metrics_skips_sparse_buckets() -> None:
    rng = np.random.default_rng(3)
    n = 500
    df = pd.DataFrame(
        {
            "label": rng.integers(0, 5, n),
            "pitcher_throws": rng.choice(["L", "R"], n),
            # rare bucket
            "park_id": np.where(rng.uniform(0, 1, n) < 0.02, "RARE", "COMMON"),
        }
    )
    proba = rng.dirichlet(np.ones(5), size=n)
    out = segment_metrics(
        df, proba, segment_cols=["pitcher_throws", "park_id"], min_rows_per_bucket=50
    )
    # The RARE bucket has ~10 rows; below the threshold → must be skipped
    assert "RARE" not in set(out["bucket"])
    assert "COMMON" in set(out["bucket"])


def test_segment_metrics_rejects_proba_length_mismatch() -> None:
    df = pd.DataFrame({"label": [0, 1], "park_id": ["A", "B"]})
    bad_proba = np.full((10, 5), 0.2)
    import pytest

    with pytest.raises(ValueError, match="rows"):
        segment_metrics(df, bad_proba, segment_cols=["park_id"])


def test_hash_dataframe_is_deterministic() -> None:
    df = pd.DataFrame({"a": [1, 2, 3], "b": ["x", "y", "z"]})
    assert hash_dataframe(df) == hash_dataframe(df)
    # Column reordering doesn't change the hash
    df2 = pd.DataFrame({"b": ["x", "y", "z"], "a": [1, 2, 3]})
    assert hash_dataframe(df) == hash_dataframe(df2)


def test_generate_emits_all_expected_files(tmp_path: Path) -> None:
    test_df, proba = _toy_test_predictions(n=300, seed=11)
    inputs = ArtifactInputs(
        model_name="_unit_test_model",
        model_version="v0",
        class_labels=["ball", "called_strike", "swinging_strike", "foul", "in_play"],
        cv_result=_toy_cv_result(),
        test_df=test_df,
        test_predictions=proba,
        training_data_hash="abc123" * 11,
        feature_importance=pd.DataFrame(
            {"feature": ["a", "b", "c"], "importance_gain": [10.0, 5.0, 1.0]}
        ),
    )
    eval_dir = generate(inputs, tmp_path / "model")

    expected_files = {
        "metrics.json",
        "reliability_diagrams.png",
        "confusion_matrix.png",
        "segment_metrics.csv",
        "temporal_cv_results.csv",
        "feature_importance.csv",
        "commit_sha.txt",
        "data_hash.txt",
    }
    on_disk = {p.name for p in eval_dir.iterdir()}
    assert expected_files == on_disk

    metrics = json.loads((eval_dir / "metrics.json").read_text())
    assert metrics["model_name"] == "_unit_test_model"
    assert metrics["n_folds"] == 2
    assert "summary" in metrics

    per_fold = pd.read_csv(eval_dir / "temporal_cv_results.csv")
    assert len(per_fold) == 2
    assert "multiclass_brier" in per_fold.columns

    seg = pd.read_csv(eval_dir / "segment_metrics.csv")
    assert "brier" in seg.columns
    assert "log_loss" in seg.columns

    fi = pd.read_csv(eval_dir / "feature_importance.csv")
    assert fi["importance_gain"].iloc[0] == 10.0  # already sorted desc by caller


def test_generate_skips_feature_importance_when_none(tmp_path: Path) -> None:
    test_df, proba = _toy_test_predictions(n=200, seed=22)
    inputs = ArtifactInputs(
        model_name="_lr_baseline",
        model_version="v0",
        class_labels=["ball", "called_strike", "swinging_strike", "foul", "in_play"],
        cv_result=_toy_cv_result(),
        test_df=test_df,
        test_predictions=proba,
        training_data_hash="def456" * 11,
        feature_importance=None,
    )
    eval_dir = generate(inputs, tmp_path / "model")
    assert not (eval_dir / "feature_importance.csv").exists()
    # All other files still present
    assert (eval_dir / "metrics.json").exists()
    assert (eval_dir / "reliability_diagrams.png").exists()


def test_lightgbm_feature_importance_returns_sorted_descending() -> None:
    """Just verify the sort property using a Booster-like stub."""
    import lightgbm as lgb

    # Build a tiny real booster on synthetic data so feature_importance() works
    rng = np.random.default_rng(7)
    X = rng.normal(size=(200, 4))
    y = (X[:, 0] + X[:, 2] > 0).astype("int64")
    dtrain = lgb.Dataset(X, label=y, feature_name=["f0", "f1", "f2", "f3"])
    booster = lgb.train({"objective": "binary", "verbosity": -1}, dtrain, num_boost_round=10)

    fi = lightgbm_feature_importance(booster, ["f0", "f1", "f2", "f3"])
    assert list(fi.columns) == ["feature", "importance_gain"]
    gains = fi["importance_gain"].tolist()
    assert gains == sorted(gains, reverse=True)
