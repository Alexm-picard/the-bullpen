"""Calibration-aware multi-class metrics (Phase 2a.4).

Brier and log-loss are proper scoring rules — they penalise both
inaccuracy AND poor calibration, so the model has to be well-calibrated
to score well. Expected Calibration Error (ECE) is the gating metric
for the Phase 2 exit criterion (`ECE < 0.02 per model`).

Conventions:
  - Predictions arrive as `(N, K)` ndarrays of class probabilities;
    rows sum to 1 within float tolerance.
  - True labels: either integer indices (shape `(N,)`) OR one-hot
    encoded (shape `(N, K)`). Functions accept both via converters.
"""

from __future__ import annotations

from typing import cast

import numpy as np


def _validate_proba(y_pred_proba: np.ndarray) -> np.ndarray:
    proba = np.asarray(y_pred_proba, dtype=np.float64)
    if proba.ndim != 2:
        raise ValueError(f"y_pred_proba must be 2-D, got shape {proba.shape}")
    if not np.all(proba >= -1e-9):
        raise ValueError("y_pred_proba contains negative values")
    row_sums = proba.sum(axis=1)
    if not np.allclose(row_sums, 1.0, atol=1e-4):
        raise ValueError(
            f"y_pred_proba rows must sum to 1 within 1e-4; max deviation "
            f"{abs(row_sums - 1.0).max():.4g}"
        )
    return proba


def _onehot_from_int(y_int: np.ndarray, n_classes: int) -> np.ndarray:
    y = np.asarray(y_int, dtype=np.int64)
    if y.ndim != 1:
        raise ValueError(f"y_int must be 1-D, got shape {y.shape}")
    out = np.zeros((y.shape[0], n_classes), dtype=np.float64)
    out[np.arange(y.shape[0]), y] = 1.0
    return out


def _coerce_to_onehot(y: np.ndarray, n_classes: int) -> np.ndarray:
    arr = np.asarray(y)
    if arr.ndim == 2 and arr.shape[1] == n_classes:
        return arr.astype(np.float64)
    if arr.ndim == 1:
        return _onehot_from_int(arr, n_classes)
    raise ValueError(
        f"y must be either 1-D ints or 2-D one-hot (N, {n_classes}); got shape {arr.shape}"
    )


def multiclass_brier(y_true: np.ndarray, y_pred_proba: np.ndarray) -> float:
    """Mean squared difference between predicted probabilities and the
    one-hot true distribution, averaged over rows and classes.

    Range [0, 2]. Lower is better. A random K-class predictor scores
    `(K-1)/K`. A perfect predictor scores 0.
    """
    proba = _validate_proba(y_pred_proba)
    onehot = _coerce_to_onehot(y_true, proba.shape[1])
    return float(np.mean(np.sum((proba - onehot) ** 2, axis=1)) / proba.shape[1])


def multiclass_log_loss(
    y_true: np.ndarray, y_pred_proba: np.ndarray, *, eps: float = 1e-15
) -> float:
    """Cross-entropy. Lower is better; 0 is perfect."""
    proba = _validate_proba(y_pred_proba)
    onehot = _coerce_to_onehot(y_true, proba.shape[1])
    clipped = np.clip(proba, eps, 1.0 - eps)
    return float(-np.mean(np.sum(onehot * np.log(clipped), axis=1)))


def expected_calibration_error(
    y_true: np.ndarray,
    y_pred_proba: np.ndarray,
    *,
    n_bins: int = 10,
) -> float:
    """Weighted average gap between confidence and accuracy across n_bins
    equal-width bins of `max(predicted_proba)`.

    THE single shared ECE (C1): the promotion gate (eval/promotion/criteria.ece)
    delegates here, so an eval-side ECE and a gate verdict use identical math, and
    both match the Java MetricsComputer. Phase 2 exit criterion is ECE < 0.02 per
    model. Returns 0 for a perfectly-calibrated predictor; max 1.
    """
    proba = _validate_proba(y_pred_proba)
    onehot = _coerce_to_onehot(y_true, proba.shape[1])

    confidence = cast(np.ndarray, proba.max(axis=1))
    predicted_class = proba.argmax(axis=1)
    true_class = onehot.argmax(axis=1)
    correct = (predicted_class == true_class).astype(np.float64)

    # Bin policy: bin = min(n_bins-1, floor(conf * n_bins)), right-open with the last
    # bin absorbing conf == 1.0 - identical to the Java MetricsComputer + the gate.
    # The prior linspace-edge variant disagreed with the gate's floor variant by one
    # bin on a confidence sitting exactly on a boundary (float-mult rounding put
    # 0.3 * 10 at 2.9999...), which could split a verdict near the 0.02 ECE bar.
    n = confidence.shape[0]
    bins = np.minimum(n_bins - 1, np.floor(confidence * n_bins).astype(np.int64))
    total: float = 0.0
    for b in range(n_bins):
        mask = bins == b
        bin_n = int(mask.sum())
        if bin_n == 0:
            continue
        bin_conf = float(confidence[mask].mean())
        bin_acc = float(correct[mask].mean())
        total += (bin_n / n) * abs(bin_conf - bin_acc)
    return float(total)
