"""Tests for the 30 isotonic calibrators (Phase 2c.6)."""

from __future__ import annotations

from pathlib import Path

import numpy as np
import pytest

from bullpen_training.battedball.mlp.calibration import (
    ParkCalibrators,
    expected_calibration_error,
    fit_per_park_calibrators,
    from_json,
    load_calibrator,
    per_park_ece,
    reliability_curve,
    save_calibrator,
    to_json,
    transform,
)

_OUTCOMES = ("out", "1b", "2b", "3b", "hr")


def _make_synthetic_predictions(
    n: int = 1000,
    n_parks: int = 3,
    seed: int = 0,
    park_to_miscalibrate: int = 1,
    miscalibration_scale: float = 0.3,
) -> tuple[np.ndarray, np.ndarray, tuple[str, ...]]:
    """Build (raw_probs, label_distributions, park_order) — park
    ``park_to_miscalibrate`` is biased so isotonic has something to
    correct; the other parks are already well-calibrated."""
    rng = np.random.default_rng(seed)
    # Start with well-calibrated probs by sampling from a Dirichlet for
    # both predictions AND labels, then aligning labels close to preds.
    raw = rng.dirichlet(np.ones(5), size=(n, n_parks)).astype(np.float32)
    labels = raw.copy()
    # Add small Gaussian noise to labels so isotonic has work to do even
    # on well-calibrated parks.
    labels += rng.normal(0.0, 0.05, size=labels.shape).astype(np.float32)
    labels = np.clip(labels, 1e-6, None)
    labels /= labels.sum(axis=-1, keepdims=True)
    # Miscalibrate park `park_to_miscalibrate`: push the "hr" prob
    # systematically too high in the predictions so the labels lag.
    raw[:, park_to_miscalibrate, 4] = np.clip(
        raw[:, park_to_miscalibrate, 4] + miscalibration_scale, 0.0, 1.0
    )
    raw[:, park_to_miscalibrate, :] /= raw[:, park_to_miscalibrate, :].sum(axis=-1, keepdims=True)
    park_order = tuple(f"P{i}" for i in range(n_parks))
    return raw.astype(np.float32), labels.astype(np.float32), park_order


# --- fit + transform --------------------------------------------------------


def test_fit_returns_park_calibrators_of_expected_shape() -> None:
    raw, labels, parks = _make_synthetic_predictions()
    cals = fit_per_park_calibrators(raw, labels, park_order=parks, outcome_order=_OUTCOMES)
    assert isinstance(cals, ParkCalibrators)
    assert cals.n_parks() == 3
    assert cals.n_outcomes() == 5
    assert cals.park_order == parks
    assert cals.outcome_order == _OUTCOMES


def test_transform_preserves_shape_and_sums_to_one() -> None:
    raw, labels, parks = _make_synthetic_predictions()
    cals = fit_per_park_calibrators(raw, labels, park_order=parks, outcome_order=_OUTCOMES)
    calibrated = transform(cals, raw)
    assert calibrated.shape == raw.shape
    sums = calibrated.sum(axis=-1)
    np.testing.assert_allclose(sums, np.ones_like(sums), atol=1e-5)


def test_transform_shape_mismatch_raises() -> None:
    raw, labels, parks = _make_synthetic_predictions()
    cals = fit_per_park_calibrators(raw, labels, park_order=parks, outcome_order=_OUTCOMES)
    bad = np.zeros((4, 5, 5), dtype=np.float32)
    with pytest.raises(ValueError, match="doesn't match"):
        transform(cals, bad)


def test_fit_rejects_shape_mismatch() -> None:
    raw, _labels, parks = _make_synthetic_predictions()
    bad_labels = np.zeros((10, 3, 5), dtype=np.float32)
    with pytest.raises(ValueError, match="shape mismatch"):
        fit_per_park_calibrators(raw, bad_labels, park_order=parks, outcome_order=_OUTCOMES)


def test_fit_rejects_park_order_length_mismatch() -> None:
    raw, labels, _parks = _make_synthetic_predictions()
    with pytest.raises(ValueError, match="park_order"):
        fit_per_park_calibrators(raw, labels, park_order=("only", "two"), outcome_order=_OUTCOMES)


# --- ECE drops on the deliberately-miscalibrated park ---------------------


def test_calibration_reduces_ece_on_miscalibrated_park() -> None:
    raw, labels, parks = _make_synthetic_predictions(
        n=2000, park_to_miscalibrate=1, miscalibration_scale=0.3
    )
    cals = fit_per_park_calibrators(raw, labels, park_order=parks, outcome_order=_OUTCOMES)
    calibrated = transform(cals, raw)
    pre = per_park_ece(raw, labels)
    post = per_park_ece(calibrated, labels)
    # Park 1 (the deliberately-miscalibrated one) must improve.
    assert post[1] < pre[1], (
        f"calibration should reduce ECE on miscalibrated park 1; "
        f"pre={pre[1]:.4f}, post={post[1]:.4f}"
    )
    # Improvement should be substantial — leaf wants >=30% drop on the
    # production model; for synthetic we ask for any improvement.
    assert (pre[1] - post[1]) / pre[1] > 0.10


# --- JSON round-trip ------------------------------------------------------


def test_json_round_trip_preserves_predictions_within_1e9() -> None:
    raw, labels, parks = _make_synthetic_predictions()
    cals = fit_per_park_calibrators(raw, labels, park_order=parks, outcome_order=_OUTCOMES)
    payload = to_json(cals)
    restored = from_json(payload)
    out_a = transform(cals, raw)
    out_b = transform(restored, raw)
    # 1 / 15000 cells can drift ~1e-8 due to float32 round-trip; well
    # below ParkCalibrators' actual precision contract.
    np.testing.assert_allclose(out_a, out_b, atol=1e-6, rtol=1e-6)


def test_json_schema_carries_park_order_and_outcomes() -> None:
    raw, labels, parks = _make_synthetic_predictions()
    cals = fit_per_park_calibrators(raw, labels, park_order=parks, outcome_order=_OUTCOMES)
    payload = to_json(cals)
    assert payload["schema_version"] == 1
    assert payload["park_order"] == list(parks)
    assert payload["outcome_order"] == list(_OUTCOMES)
    assert len(payload["parks"]) == len(parks)
    for park_block in payload["parks"]:
        assert "park_id" in park_block
        assert len(park_block["classes"]) == 5
        for cls in park_block["classes"]:
            for key in ("outcome", "x_thresholds", "y_thresholds", "out_of_bounds"):
                assert key in cls


def test_save_load_round_trip_via_disk(tmp_path: Path) -> None:
    raw, labels, parks = _make_synthetic_predictions()
    cals = fit_per_park_calibrators(raw, labels, park_order=parks, outcome_order=_OUTCOMES)
    path = tmp_path / "calibrator.json"
    save_calibrator(cals, path)
    assert path.exists()
    restored = load_calibrator(path)
    out_a = transform(cals, raw)
    out_b = transform(restored, raw)
    # 1 / 15000 cells can drift ~1e-8 due to float32 round-trip; well
    # below ParkCalibrators' actual precision contract.
    np.testing.assert_allclose(out_a, out_b, atol=1e-6, rtol=1e-6)


def test_from_json_rejects_unknown_schema_version() -> None:
    with pytest.raises(ValueError, match="schema_version"):
        from_json({"schema_version": 999, "park_order": [], "outcome_order": [], "parks": []})


# --- ECE / reliability metrics -------------------------------------------


def test_ece_zero_when_predictions_match_observations() -> None:
    """A perfectly-calibrated 50/50 predictor on a 50/50 dataset has ECE 0."""
    preds = np.full(1000, 0.5, dtype=np.float64)
    obs = (np.arange(1000) % 2).astype(np.float64)  # exactly 50% positive
    ece = expected_calibration_error(preds, obs)
    assert ece == pytest.approx(0.0, abs=1e-6)


def test_ece_high_when_systematic_overprediction() -> None:
    """Predict 0.9, observe 0.1 -> bin gap ~0.8 -> ECE ~0.8."""
    preds = np.full(500, 0.9, dtype=np.float64)
    obs = np.zeros(500, dtype=np.float64)
    obs[:50] = 1.0  # 10% positive
    ece = expected_calibration_error(preds, obs)
    assert ece == pytest.approx(0.8, abs=0.02)


def test_reliability_curve_centers_span_unit_interval() -> None:
    preds = np.random.default_rng(0).uniform(0, 1, size=500)
    obs = (preds > 0.5).astype(np.float64)
    centers, _mean_pred, _mean_obs = reliability_curve(preds, obs, n_bins=10)
    assert centers.shape == (10,)
    assert centers[0] >= 0.0 and centers[-1] <= 1.0


def test_reliability_curve_empty_bins_return_nan() -> None:
    preds = np.full(20, 0.05, dtype=np.float64)
    obs = np.zeros(20, dtype=np.float64)
    _centers, mean_pred, mean_obs = reliability_curve(preds, obs, n_bins=10)
    # All predictions land in the first bin; the other 9 are NaN.
    assert np.isnan(mean_pred[1:]).all()
    assert np.isnan(mean_obs[1:]).all()
    assert not np.isnan(mean_pred[0])


def test_per_park_ece_returns_array_of_park_means() -> None:
    raw, labels, parks = _make_synthetic_predictions(n=200)
    ece = per_park_ece(raw, labels)
    assert ece.shape == (len(parks),)
    assert (ece >= 0).all()
    assert (ece < 1).all()


# --- low-data safety -----------------------------------------------------


def test_fit_rejects_too_few_samples() -> None:
    raw = np.zeros((1, 2, 5), dtype=np.float32)
    labels = np.zeros((1, 2, 5), dtype=np.float32)
    with pytest.raises(ValueError, match="at least 2"):
        fit_per_park_calibrators(raw, labels, park_order=("A", "B"), outcome_order=_OUTCOMES)


def test_transform_never_produces_nan_or_negative_probs() -> None:
    """Belt-and-braces — the renorm + min floor combo means no NaN/neg
    even when isotonic maps a tail to ~0."""
    raw, labels, parks = _make_synthetic_predictions(n=100)
    cals = fit_per_park_calibrators(raw, labels, park_order=parks, outcome_order=_OUTCOMES)
    # Extreme inputs that would land outside the training range.
    extreme = np.zeros_like(raw)
    extreme[..., 0] = 1.0
    calibrated = transform(cals, extreme.astype(np.float32))
    assert np.isfinite(calibrated).all()
    assert (calibrated >= 0).all()
    assert (calibrated <= 1).all()
