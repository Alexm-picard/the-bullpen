"""Rolling-origin cross-validation harness (Phase 2a.4).

The only valid way to evaluate any model trained on the pitch corpus.
Decision [56] locks 4 folds spanning 2015-2025 with within-fold split
granularity by **date** (decision [59]).

The 4 folds:

    Fold | Train       | Validation | Test
    -----+-------------+------------+------
      1  | 2015-2020   | 2021       | 2022
      2  | 2015-2021   | 2022       | 2023
      3  | 2015-2022   | 2023       | 2024
      4  | 2015-2023   | 2024       | 2025

This harness is model-agnostic. The caller passes a `feature_loader`
(returns labelled DataFrames per (start_year, end_year, fold)) and a
`model_factory` (takes train + val DataFrames, returns an object with
`.predict_proba(test)`). The harness iterates the 4 folds, computes
each metric per fold, then reports mean ± std.

Within-fold val/test are full calendar years (decision [59]); finer
splits MUST be by-date if they're ever needed (never by game/pitch).

A reminder about the feature-table state: 2a.1's tier_1_2 built
features rows with train_end = test_year - 1 (val year included in
the TE training window). For a leakage-clean run of THIS harness, the
features table needs to be rebuilt with train_end = test_year - 2
before any model trains. The rebuild lands as a 2a.5 prereq.
"""

from __future__ import annotations

import gc
import logging
import statistics
from collections.abc import Callable, Iterable
from dataclasses import dataclass
from typing import Any, Protocol, cast

import numpy as np
import pandas as pd

from bullpen_training.eval.leakage_guards import LeakageError
from bullpen_training.logging_config import get_logger

log = get_logger(__name__)


@dataclass(frozen=True)
class FoldSpec:
    """One rolling-origin fold. Years are inclusive."""

    fold_id: int
    train_start_year: int
    train_end_year: int
    val_year: int
    test_year: int

    def __post_init__(self) -> None:
        if not (self.train_end_year < self.val_year < self.test_year):
            raise LeakageError(
                f"fold {self.fold_id}: years must satisfy "
                f"train_end < val < test; got "
                f"train_end={self.train_end_year}, val={self.val_year}, test={self.test_year}"
            )


FOLDS: tuple[FoldSpec, ...] = (
    FoldSpec(1, 2015, 2020, 2021, 2022),
    FoldSpec(2, 2015, 2021, 2022, 2023),
    FoldSpec(3, 2015, 2022, 2023, 2024),
    FoldSpec(4, 2015, 2023, 2024, 2025),
)


class HasPredictProba(Protocol):
    def predict_proba(self, X: pd.DataFrame) -> np.ndarray:
        ...


# A loader takes (start_year, end_year, fold_id) and returns a DataFrame
# whose `label` column is the integer-encoded target and whose other
# columns are features.
FeatureLoader = Callable[[int, int, int], pd.DataFrame]

# Model factory takes (train_df, val_df) and returns a fitted model.
ModelFactory = Callable[[pd.DataFrame, pd.DataFrame], HasPredictProba]

# Metric takes (y_true_int, y_pred_proba) -> float
Metric = Callable[[np.ndarray, np.ndarray], float]


@dataclass(frozen=True)
class FoldResult:
    fold_id: int
    train_rows: int
    val_rows: int
    test_rows: int
    metrics: dict[str, float]


@dataclass(frozen=True)
class CVResult:
    per_fold: tuple[FoldResult, ...]
    summary: dict[str, tuple[float, float]]  # metric_name -> (mean, std)

    def __str__(self) -> str:
        lines = ["CV summary (n_folds=4):"]
        for name, (mean, std) in sorted(self.summary.items()):
            lines.append(f"  {name}: {mean:.4f} ± {std:.4f}")
        return "\n".join(lines)


def _label_array(df: pd.DataFrame) -> np.ndarray:
    if "label" not in df.columns:
        raise ValueError("feature_loader DataFrame must contain a 'label' column")
    return np.asarray(df["label"], dtype=np.int64)


def _require_label(df: pd.DataFrame, fold_id: int, split_name: str) -> None:
    """Fail loud if a loaded split is missing the label column (per-split so test can
    be validated after its deferred load - CV-MEM-1)."""
    if "label" not in df.columns:
        raise ValueError(
            f"feature_loader returned a DataFrame missing the 'label' column "
            f"(fold {fold_id}, split {split_name})"
        )


def run(
    model_factory: ModelFactory,
    feature_loader: FeatureLoader,
    eval_metrics: Iterable[Metric],
    *,
    folds: Iterable[FoldSpec] = FOLDS,
    log_level: int = logging.INFO,
) -> CVResult:
    """Run rolling-origin CV. Returns per-fold metrics + mean/std summary.

    `feature_loader(start_year, end_year, fold_id)` is called three times
    per fold (train, val, test). The harness sets no global seeds — the
    model_factory and feature_loader are responsible for their own
    determinism.
    """
    metrics_list = list(eval_metrics)
    if not metrics_list:
        raise ValueError("at least one metric required")

    folds_tuple = tuple(folds)
    per_fold: list[FoldResult] = []
    for fold in folds_tuple:
        log.info(
            "fold start",
            fold=fold.fold_id,
            train=f"{fold.train_start_year}-{fold.train_end_year}",
            val=fold.val_year,
            test=fold.test_year,
        )
        train_df = feature_loader(fold.train_start_year, fold.train_end_year, fold.fold_id)
        val_df = feature_loader(fold.val_year, fold.val_year, fold.fold_id)
        _require_label(train_df, fold.fold_id, "train")
        _require_label(val_df, fold.fold_id, "val")

        model = model_factory(train_df, val_df)
        train_rows = len(train_df)
        val_rows = len(val_df)
        # CV-MEM-1: train/val are no longer needed once model_factory has returned
        # (it has already fit the booster + the val-based calibrator). Free them
        # before the test frame loads so test + the metric computation don't stack on
        # top of the train-time peak.
        del train_df, val_df
        gc.collect()

        # test_df is only needed for the test-metric, NOT during training - load it
        # here (after the train peak) so it is never resident while the model fits.
        test_df = feature_loader(fold.test_year, fold.test_year, fold.fold_id)
        _require_label(test_df, fold.fold_id, "test")
        y_test = _label_array(test_df)
        feature_cols = [c for c in test_df.columns if c != "label"]
        test_features = cast(pd.DataFrame, test_df[feature_cols])
        proba = model.predict_proba(test_features)
        test_rows = len(test_df)

        fold_metrics = {m.__name__: float(m(y_test, proba)) for m in metrics_list}
        per_fold.append(
            FoldResult(
                fold_id=fold.fold_id,
                train_rows=train_rows,
                val_rows=val_rows,
                test_rows=test_rows,
                metrics=fold_metrics,
            )
        )
        log.log(log_level, "fold done", fold=fold.fold_id, **fold_metrics)
        # Release this fold's frames before the next fold loads, so two folds'
        # worth of data never coexist (the cross-fold overlap, CV-MEM-1).
        del test_df, test_features, proba, model
        gc.collect()

    summary: dict[str, tuple[float, float]] = {}
    for name in {m.__name__ for m in metrics_list}:
        values = [fr.metrics[name] for fr in per_fold]
        mean = statistics.mean(values)
        std = statistics.stdev(values) if len(values) > 1 else 0.0
        summary[name] = (float(mean), float(std))

    return CVResult(per_fold=tuple(per_fold), summary=summary)


def assert_no_within_fold_random_split(*_args: Any, **_kwargs: Any) -> None:
    """Sentinel for decision [59]: within-fold splits must be by-date,
    not by game or pitch. Anyone reaching for a random shuffler within
    a fold should import this and get a LeakageError."""
    raise LeakageError(
        "Within-fold splits must be by date (decision [59]). "
        "Random/by-game/by-pitch splits are forbidden."
    )
