"""Unit tests for the rolling-origin CV harness (Phase 2a.4)."""

from __future__ import annotations

import numpy as np
import pandas as pd
import pytest

from bullpen_training.eval.cv_harness import (
    FOLDS,
    CVResult,
    FoldResult,
    FoldSpec,
    assert_no_within_fold_random_split,
    run,
)
from bullpen_training.eval.leakage_guards import (
    LeakageError,
    assert_no_random_split,
)
from bullpen_training.eval.metrics import (
    expected_calibration_error,
    multiclass_brier,
    multiclass_log_loss,
)

# ---------------------------------------------------------------------------
# FoldSpec / FOLDS invariants
# ---------------------------------------------------------------------------


def test_folds_match_decision_56() -> None:
    """The 4-fold layout is locked by decision [56]; this test prevents
    drift if anyone reorders them."""
    expected = [
        (1, 2015, 2020, 2021, 2022),
        (2, 2015, 2021, 2022, 2023),
        (3, 2015, 2022, 2023, 2024),
        (4, 2015, 2023, 2024, 2025),
    ]
    actual = [
        (f.fold_id, f.train_start_year, f.train_end_year, f.val_year, f.test_year) for f in FOLDS
    ]
    assert actual == expected


def test_foldspec_rejects_train_after_val() -> None:
    with pytest.raises(LeakageError, match="train_end < val < test"):
        FoldSpec(99, 2015, 2022, 2021, 2023)


def test_foldspec_rejects_val_equal_to_test() -> None:
    with pytest.raises(LeakageError, match="train_end < val < test"):
        FoldSpec(99, 2015, 2020, 2022, 2022)


# ---------------------------------------------------------------------------
# Leakage guards
# ---------------------------------------------------------------------------


def test_assert_no_random_split_raises_with_decision_reference() -> None:
    with pytest.raises(LeakageError, match="decision \\[56\\]"):
        assert_no_random_split([1, 2, 3], test_size=0.2, random_state=42)


def test_assert_no_within_fold_random_split_raises() -> None:
    with pytest.raises(LeakageError, match="decision \\[59\\]"):
        assert_no_within_fold_random_split()


# ---------------------------------------------------------------------------
# Harness end-to-end on a synthetic feature loader + a trivial model factory
# ---------------------------------------------------------------------------


class _ConstantPredictor:
    """Returns the train-set class marginals as the prediction for every row."""

    def __init__(self, n_classes: int, marginals: np.ndarray) -> None:
        self.n_classes = n_classes
        self.marginals = marginals

    def predict_proba(self, X: pd.DataFrame) -> np.ndarray:
        return np.tile(self.marginals, (len(X), 1))


def _synthetic_loader(start_year: int, end_year: int, fold_id: int) -> pd.DataFrame:
    rng = np.random.default_rng(seed=1000 * fold_id + start_year)
    n = 200 * (end_year - start_year + 1)
    return pd.DataFrame(
        {
            "feat_a": rng.normal(size=n).astype("float32"),
            "feat_b": rng.normal(size=n).astype("float32"),
            "label": rng.integers(0, 3, n),
        }
    )


def _constant_factory(train: pd.DataFrame, val: pd.DataFrame) -> _ConstantPredictor:
    counts = np.bincount(np.asarray(train["label"], dtype=np.int64), minlength=3)
    return _ConstantPredictor(n_classes=3, marginals=counts / counts.sum())


def test_run_emits_per_fold_and_summary() -> None:
    result = run(
        model_factory=_constant_factory,
        feature_loader=_synthetic_loader,
        eval_metrics=[multiclass_brier, multiclass_log_loss, expected_calibration_error],
    )
    assert isinstance(result, CVResult)
    assert len(result.per_fold) == 4
    assert all(isinstance(fr, FoldResult) for fr in result.per_fold)
    assert set(result.summary.keys()) == {
        "multiclass_brier",
        "multiclass_log_loss",
        "expected_calibration_error",
    }
    for _, (mean, std) in result.summary.items():
        assert mean >= 0.0
        assert std >= 0.0


def test_run_train_val_test_have_expected_year_spans() -> None:
    """Spot-check that the loader gets called with each fold's
    documented year span."""
    calls: list[tuple[int, int, int]] = []

    def tracer(start_year: int, end_year: int, fold_id: int) -> pd.DataFrame:
        calls.append((start_year, end_year, fold_id))
        return _synthetic_loader(start_year, end_year, fold_id)

    run(
        model_factory=_constant_factory,
        feature_loader=tracer,
        eval_metrics=[multiclass_brier],
    )

    # 3 calls per fold * 4 folds = 12 total
    assert len(calls) == 12
    # Fold 1: train (2015, 2020, 1), val (2021, 2021, 1), test (2022, 2022, 1)
    assert calls[0] == (2015, 2020, 1)
    assert calls[1] == (2021, 2021, 1)
    assert calls[2] == (2022, 2022, 1)
    # Fold 4: train (2015, 2023, 4), val (2024, 2024, 4), test (2025, 2025, 4)
    assert calls[-3] == (2015, 2023, 4)
    assert calls[-2] == (2024, 2024, 4)
    assert calls[-1] == (2025, 2025, 4)


def test_run_rejects_empty_metric_list() -> None:
    with pytest.raises(ValueError, match="at least one metric"):
        run(
            model_factory=_constant_factory,
            feature_loader=_synthetic_loader,
            eval_metrics=[],
        )


def test_run_loader_missing_label_raises() -> None:
    def bad_loader(_s: int, _e: int, _f: int) -> pd.DataFrame:
        return pd.DataFrame({"feat_a": [1.0, 2.0]})

    with pytest.raises(ValueError, match="label"):
        run(
            model_factory=_constant_factory,
            feature_loader=bad_loader,
            eval_metrics=[multiclass_brier],
        )


def test_cvresult_str_includes_mean_and_std() -> None:
    fold = FoldResult(fold_id=1, train_rows=10, val_rows=5, test_rows=5, metrics={"brier": 0.2})
    result = CVResult(per_fold=(fold,), summary={"brier": (0.2, 0.0)})
    rendered = str(result)
    assert "brier" in rendered
    assert "0.2000" in rendered
