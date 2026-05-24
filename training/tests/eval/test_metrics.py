"""Unit tests for the multi-class metrics (Phase 2a.4)."""

from __future__ import annotations

import numpy as np
import pytest

from bullpen_training.eval.metrics import (
    _coerce_to_onehot,
    expected_calibration_error,
    multiclass_brier,
    multiclass_log_loss,
)


def test_perfect_predictor_brier_is_zero() -> None:
    y_int = np.array([0, 1, 2, 1, 0])
    proba = _coerce_to_onehot(y_int, n_classes=3)
    assert multiclass_brier(y_int, proba) == pytest.approx(0.0, abs=1e-12)


def test_brier_matches_hand_computed_value_for_3class() -> None:
    """One row, one wrong class:
    y = class 0 → onehot = [1, 0, 0]
    pred       = [0.7, 0.2, 0.1]
    squared diff = [0.09, 0.04, 0.01] → sum 0.14
    / n_classes (3) = 0.04666...
    / n_rows (1) = 0.04666..."""
    y_int = np.array([0])
    proba = np.array([[0.7, 0.2, 0.1]])
    expected = (0.09 + 0.04 + 0.01) / 3
    assert multiclass_brier(y_int, proba) == pytest.approx(expected, rel=1e-9)


def test_random_3class_brier_is_around_2_over_9() -> None:
    """A uniform predictor scores (K-1)/K per row averaged over classes."""
    n = 10_000
    rng = np.random.default_rng(42)
    y_int = rng.integers(0, 3, n)
    proba = np.full((n, 3), 1 / 3)
    brier = multiclass_brier(y_int, proba)
    # Each row: sum of (1/3 - {0,1})^2 = 2 * (1/3)^2 + (2/3)^2 = 6/9
    # / 3 classes = 2/9 ≈ 0.2222
    assert brier == pytest.approx(2 / 9, abs=1e-3)


def test_perfect_predictor_log_loss_is_zero() -> None:
    y_int = np.array([0, 1, 2])
    proba = _coerce_to_onehot(y_int, n_classes=3)
    assert multiclass_log_loss(y_int, proba) == pytest.approx(0.0, abs=1e-12)


def test_uniform_predictor_log_loss_is_ln_k() -> None:
    n = 5_000
    rng = np.random.default_rng(7)
    y_int = rng.integers(0, 5, n)
    proba = np.full((n, 5), 0.2)
    assert multiclass_log_loss(y_int, proba) == pytest.approx(np.log(5), abs=1e-9)


def test_proba_validation_rejects_negative_values() -> None:
    proba = np.array([[-0.1, 0.6, 0.5]])
    with pytest.raises(ValueError, match="negative"):
        multiclass_brier(np.array([0]), proba)


def test_proba_validation_rejects_unnormalized_rows() -> None:
    proba = np.array([[0.5, 0.5, 0.5]])
    with pytest.raises(ValueError, match="rows must sum to 1"):
        multiclass_brier(np.array([0]), proba)


def test_ece_perfectly_calibrated_is_zero() -> None:
    """If for every prediction confidence c, accuracy on that bin == c,
    ECE = 0. Construct a fixture where each row's predicted class is
    correct iff a uniform draw < confidence."""
    rng = np.random.default_rng(0)
    n = 100_000
    n_classes = 3
    confidences = rng.uniform(0.4, 1.0, n)
    predicted = rng.integers(0, n_classes, n)
    correct = rng.uniform(0, 1, n) < confidences
    true_class = np.where(correct, predicted, (predicted + 1) % n_classes)

    proba = np.zeros((n, n_classes))
    for i in range(n):
        # Predicted class gets `confidences[i]`, remaining mass split evenly
        for c in range(n_classes):
            proba[i, c] = (1 - confidences[i]) / (n_classes - 1)
        proba[i, predicted[i]] = confidences[i]

    ece = expected_calibration_error(true_class, proba, n_bins=10)
    assert ece < 0.02


def test_ece_overconfident_predictor_has_nonzero_ece() -> None:
    """If the predictor says 0.9 but is right only 50% of the time, ECE > 0."""
    n = 1_000
    n_classes = 3
    rng = np.random.default_rng(99)
    predicted = rng.integers(0, n_classes, n)
    correct = rng.uniform(0, 1, n) < 0.5  # only 50% accuracy
    true_class = np.where(correct, predicted, (predicted + 1) % n_classes)

    proba = np.full((n, n_classes), (1 - 0.9) / (n_classes - 1))
    proba[np.arange(n), predicted] = 0.9

    ece = expected_calibration_error(true_class, proba, n_bins=10)
    assert ece > 0.3, f"overconfident predictor should have ECE > 0.3, got {ece}"


def test_metrics_accept_both_int_and_onehot_labels() -> None:
    y_int = np.array([0, 1, 2])
    y_onehot = np.eye(3)
    proba = np.array([[0.9, 0.05, 0.05], [0.05, 0.9, 0.05], [0.05, 0.05, 0.9]])

    brier_int = multiclass_brier(y_int, proba)
    brier_onehot = multiclass_brier(y_onehot, proba)
    assert brier_int == pytest.approx(brier_onehot, rel=1e-9)
