"""Unit tests for the single-scalar temperature calibrator (pitch_type_pre, [183]).

The load-bearing property under [183]'s honest framing is ORDER PRESERVATION:
temperature scaling moves ECE without ever changing the argmax or any pairwise
class ranking, so it cannot quietly turn a calibrated prior into a top-1 predictor.
`test_temperature_is_order_preserving` pins that; the rest cover ECE reduction, the
T~=1.0 identity signal, row normalisation, and the calibrator.json round-trip.
"""

from __future__ import annotations

import logging
from pathlib import Path

import numpy as np
import pytest

from bullpen_training.eval.metrics import expected_calibration_error
from bullpen_training.pitch_type import PITCH_TYPE_CLASSES
from bullpen_training.pitch_type.temperature import TemperatureCalibrator


def _make_overconfident_predictions(
    n: int = 5_000, confidence: float = 0.9, accuracy: float = 0.6, seed: int = 0
) -> tuple[np.ndarray, np.ndarray]:
    """A globally overconfident 7-class predictor: it asserts `confidence` on its
    top class but is only right `accuracy` of the time. A single temperature is the
    ideal corrector for this uniform miscalibration."""
    rng = np.random.default_rng(seed)
    k = len(PITCH_TYPE_CLASSES)
    predicted = rng.integers(0, k, n)
    correct = rng.uniform(0, 1, n) < accuracy
    true_class = np.where(correct, predicted, (predicted + 1) % k)
    proba = np.full((n, k), (1 - confidence) / (k - 1))
    proba[np.arange(n), predicted] = confidence
    return true_class, proba


def _make_distinct_predictions(n: int = 500, seed: int = 0) -> tuple[np.ndarray, np.ndarray]:
    """Rows whose 7 class probs are ALL DISTINCT (softmax of random logits), so the
    full-ranking invariant is stressed on a strictly-interior ordering rather than on
    ties (where argsort passes trivially since temperature maps equal -> equal)."""
    rng = np.random.default_rng(seed)
    k = len(PITCH_TYPE_CLASSES)
    logits = rng.normal(0.0, 2.0, size=(n, k))
    z = logits - logits.max(axis=1, keepdims=True)
    e = np.exp(z)
    proba = e / e.sum(axis=1, keepdims=True)
    return proba.argmax(axis=1), proba


def test_temperature_reduces_ece_on_overconfident_predictor() -> None:
    y_true, y_proba = _make_overconfident_predictions(seed=1)
    cal = TemperatureCalibrator.fit(y_true, y_proba, class_labels=PITCH_TYPE_CLASSES)
    calibrated = cal.transform(y_proba)
    ece_raw = expected_calibration_error(y_true, y_proba)
    ece_cal = expected_calibration_error(y_true, calibrated)
    assert ece_cal < ece_raw, f"raw {ece_raw:.4f} cal {ece_cal:.4f}"
    # Uniform overconfidence is exactly what one scalar T corrects: expect a big cut.
    assert ece_cal < 0.5 * ece_raw, f"weak reduction: raw {ece_raw:.4f} cal {ece_cal:.4f}"
    # T > 1 flattens an overconfident predictor.
    assert cal.temperature > 1.0, f"expected flattening T>1, got {cal.temperature:.3f}"


def test_temperature_near_one_on_already_calibrated() -> None:
    """A well-calibrated input (confidence == accuracy) should recover T ~= 1.0."""
    y_true, y_proba = _make_overconfident_predictions(confidence=0.6, accuracy=0.6, seed=7)
    cal = TemperatureCalibrator.fit(y_true, y_proba, class_labels=PITCH_TYPE_CLASSES)
    assert cal.temperature == pytest.approx(1.0, abs=0.15), (
        f"already-calibrated input should give T~=1, got {cal.temperature:.3f}"
    )


def test_temperature_is_order_preserving() -> None:
    """The [183] honest-framing invariant: temperature never reorders classes.
    argmax and the full per-row ranking are identical before and after."""
    y_true, y_proba = _make_overconfident_predictions(n=1_000, seed=2)
    cal = TemperatureCalibrator.fit(y_true, y_proba, class_labels=PITCH_TYPE_CLASSES)
    calibrated = cal.transform(y_proba)
    # argmax unchanged on every row.
    assert np.array_equal(y_proba.argmax(axis=1), calibrated.argmax(axis=1))
    # full rank order unchanged on every row (argsort is stable, ties preserved).
    assert np.array_equal(y_proba.argsort(axis=1), calibrated.argsort(axis=1))


def test_temperature_preserves_full_ranking_on_distinct_interior() -> None:
    """Stress the full-ranking claim on ALL-DISTINCT probabilities (no ties) under a
    non-identity T in both directions. On strictly-interior orderings a non-monotone
    map WOULD reorder, so this is the assertion the docstring's ranking promise needs."""
    _, distinct = _make_distinct_predictions(n=500, seed=11)
    # Guard the premise: every row is genuinely tie-free, else argsort passes trivially.
    assert all(len(np.unique(row)) == distinct.shape[1] for row in distinct)
    for t in (0.4, 2.5):  # T<1 sharpens, T>1 flattens; neither may reorder.
        out = TemperatureCalibrator(class_labels=PITCH_TYPE_CLASSES, temperature=t).transform(
            distinct
        )
        assert np.array_equal(distinct.argsort(axis=1), out.argsort(axis=1))
        assert np.array_equal(distinct.argmax(axis=1), out.argmax(axis=1))


def test_temperature_rows_renormalise_to_one() -> None:
    y_true, y_proba = _make_overconfident_predictions(n=200, seed=3)
    cal = TemperatureCalibrator.fit(y_true, y_proba, class_labels=PITCH_TYPE_CLASSES)
    calibrated = cal.transform(y_proba)
    assert np.allclose(calibrated.sum(axis=1), 1.0, atol=1e-9)


def test_temperature_json_roundtrip_preserves_predictions(tmp_path: Path) -> None:
    y_true, y_proba = _make_overconfident_predictions(n=500, seed=4)
    cal = TemperatureCalibrator.fit(y_true, y_proba, class_labels=PITCH_TYPE_CLASSES)
    path = tmp_path / "calibrator.json"
    cal.to_json(path)
    loaded = TemperatureCalibrator.from_json(path)
    assert loaded.class_labels == cal.class_labels
    assert loaded.temperature == cal.temperature
    assert np.allclose(cal.transform(y_proba), loaded.transform(y_proba), atol=1e-12)


def test_temperature_identity_at_t_one() -> None:
    """T == 1 is the exact identity (softmax(log(p)) == p for a valid distribution)."""
    _, y_proba = _make_overconfident_predictions(n=50, seed=5)
    cal = TemperatureCalibrator(class_labels=PITCH_TYPE_CLASSES, temperature=1.0)
    assert np.allclose(cal.transform(y_proba), y_proba, atol=1e-12)


def test_temperature_fit_rejects_class_count_mismatch() -> None:
    y_true = np.array([0, 1, 2])
    bad = np.full((3, 2), 0.5)  # 2 classes, but 7 expected
    with pytest.raises(ValueError, match="7 classes"):
        TemperatureCalibrator.fit(y_true, bad, class_labels=PITCH_TYPE_CLASSES)


def test_temperature_transform_rejects_wrong_shape() -> None:
    cal = TemperatureCalibrator(class_labels=PITCH_TYPE_CLASSES, temperature=1.2)
    with pytest.raises(ValueError, match="7 classes"):
        cal.transform(np.ones((4, 3)) / 3)


def test_temperature_from_json_rejects_wrong_kind(tmp_path: Path) -> None:
    path = tmp_path / "calibrator.json"
    path.write_text('{"kind": "isotonic", "class_labels": ["FF"], "temperature": 1.0}')
    with pytest.raises(ValueError, match="temperature"):
        TemperatureCalibrator.from_json(path)


@pytest.mark.parametrize("bad_t", [0.0, -1.0])
def test_temperature_rejects_nonpositive(bad_t: float) -> None:
    """T <= 0 breaks order preservation - rejected at every construction path."""
    with pytest.raises(ValueError, match="temperature must be > 0"):
        TemperatureCalibrator(class_labels=PITCH_TYPE_CLASSES, temperature=bad_t)


def test_temperature_from_json_rejects_nonpositive(tmp_path: Path) -> None:
    path = tmp_path / "calibrator.json"
    path.write_text('{"kind": "temperature", "class_labels": ["FF"], "temperature": 0.0}')
    with pytest.raises(ValueError, match="temperature must be > 0"):
        TemperatureCalibrator.from_json(path)


def test_temperature_from_json_rejects_missing_field(tmp_path: Path) -> None:
    path = tmp_path / "calibrator.json"
    path.write_text('{"kind": "temperature", "class_labels": ["FF"]}')
    with pytest.raises(ValueError, match="missing required field"):
        TemperatureCalibrator.from_json(path)


def test_temperature_fit_warns_on_bound_saturation(caplog: pytest.LogCaptureFixture) -> None:
    """Degenerate calibration data (confident probs vs independent random labels) pins T to
    the upper search bound; the fit must WARN rather than silently persist a maximally-
    flattened T=10 calibrator - the deferred #348 note the trainer surfaces."""
    rng = np.random.default_rng(99)
    n, k = 3_000, len(PITCH_TYPE_CLASSES)
    predicted = rng.integers(0, k, n)
    proba = np.full((n, k), (1 - 0.9) / (k - 1))
    proba[np.arange(n), predicted] = 0.9
    y_true = rng.integers(0, k, n)  # independent of the confident prediction
    with caplog.at_level(logging.WARNING, logger="bullpen_training.pitch_type.temperature"):
        cal = TemperatureCalibrator.fit(y_true, proba, class_labels=PITCH_TYPE_CLASSES)
    # Pin at the warning's own >= _T_UPPER*0.99 (=9.9) threshold so the T assertion and the
    # "warning fired" assertion can never disagree in the (9.0, 9.9) band.
    assert cal.temperature >= 9.9, f"expected saturation at the upper bound, got {cal.temperature}"
    assert any("saturated" in r.message for r in caplog.records), "no saturation warning logged"
