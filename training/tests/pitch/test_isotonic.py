"""Unit tests for the per-class isotonic calibrator (Phase 2a.5)."""

from __future__ import annotations

from pathlib import Path

import numpy as np
import pytest

from bullpen_training.eval.metrics import expected_calibration_error
from bullpen_training.features import LABEL_CLASSES
from bullpen_training.pitch.isotonic import IsotonicCalibrator


def _make_miscalibrated_predictions(n: int = 5_000, seed: int = 0) -> tuple[np.ndarray, np.ndarray]:
    """Build an overconfident predictor: confidence 0.9 but accuracy 0.6.
    The first class is the truth half the time; the other half it's the
    predicted-with-0.9 class."""
    rng = np.random.default_rng(seed)
    n_classes = len(LABEL_CLASSES)
    predicted = rng.integers(0, n_classes, n)
    correct = rng.uniform(0, 1, n) < 0.6
    true_class = np.where(correct, predicted, (predicted + 1) % n_classes)
    proba = np.full((n, n_classes), (1 - 0.9) / (n_classes - 1))
    proba[np.arange(n), predicted] = 0.9
    return true_class, proba


def test_isotonic_reduces_ece_on_overconfident_predictor() -> None:
    y_true, y_proba = _make_miscalibrated_predictions(seed=1)
    cal = IsotonicCalibrator.fit(y_true, y_proba, class_labels=LABEL_CLASSES)
    calibrated = cal.transform(y_proba)
    ece_raw = expected_calibration_error(y_true, y_proba)
    ece_cal = expected_calibration_error(y_true, calibrated)
    assert ece_cal < ece_raw, f"raw {ece_raw:.4f} cal {ece_cal:.4f}"
    # Leaf 2a.5 acceptance: ≥30% reduction
    assert ece_cal < 0.7 * ece_raw, (
        f"calibration didn't reduce ECE by 30%: raw {ece_raw:.4f} cal {ece_cal:.4f}"
    )


def test_isotonic_rows_renormalise_to_one() -> None:
    y_true, y_proba = _make_miscalibrated_predictions(n=200, seed=2)
    cal = IsotonicCalibrator.fit(y_true, y_proba, class_labels=LABEL_CLASSES)
    calibrated = cal.transform(y_proba)
    sums = calibrated.sum(axis=1)
    assert np.allclose(sums, 1.0, atol=1e-9)


def test_isotonic_json_roundtrip_preserves_predictions(tmp_path: Path) -> None:
    y_true, y_proba = _make_miscalibrated_predictions(n=500, seed=3)
    cal = IsotonicCalibrator.fit(y_true, y_proba, class_labels=LABEL_CLASSES)
    path = tmp_path / "calibrator.json"
    cal.to_json(path)
    loaded = IsotonicCalibrator.from_json(path)
    assert loaded.class_labels == cal.class_labels
    original = cal.transform(y_proba)
    roundtripped = loaded.transform(y_proba)
    assert np.allclose(original, roundtripped, atol=1e-9)


def test_isotonic_rejects_class_count_mismatch() -> None:
    y_true = np.array([0, 1, 2])
    bad_proba = np.array([[0.5, 0.5], [0.5, 0.5], [0.5, 0.5]])  # 2 classes, but 5 expected
    with pytest.raises(ValueError, match="expected 5"):
        IsotonicCalibrator.fit(y_true, bad_proba, class_labels=LABEL_CLASSES)


def test_isotonic_transform_rejects_wrong_shape() -> None:
    y_true, y_proba = _make_miscalibrated_predictions(n=100, seed=4)
    cal = IsotonicCalibrator.fit(y_true, y_proba, class_labels=LABEL_CLASSES)
    wrong = np.ones((10, 3)) / 3
    with pytest.raises(ValueError, match="calibrator was fit for 5"):
        cal.transform(wrong)


def test_isotonic_handles_all_zero_row_after_clip() -> None:
    """If every class's calibrator clips to 0 on a row (pathological raw
    proba), the renormalisation must fall back to uniform — not divide by 0."""
    y_true, y_proba = _make_miscalibrated_predictions(n=200, seed=5)
    cal = IsotonicCalibrator.fit(y_true, y_proba, class_labels=LABEL_CLASSES)
    # Synthetic row that no calibrator was trained on the low end of
    weird = np.zeros((1, len(LABEL_CLASSES)))
    out = cal.transform(weird)
    assert np.isfinite(out).all()
    assert np.isclose(out.sum(), 1.0, atol=1e-9)
