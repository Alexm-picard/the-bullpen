"""Tests for the batted-ball MLP recalibration experiment (per-park temperature scaling).

The full-data H2 run (#115) missed the absolute ECE < 0.02 bar at 0.0346 POST-isotonic, so the
driver gained per-park temperature scaling (via --mlp-calibration). These pin the temperature math
(identity at T=1, softening at T>1, per-park routing + cold-park default), the val-only fit, and
that all three calibration strategies wire end-to-end through _mlp_factory and emit valid probs.
"""

from __future__ import annotations

from pathlib import Path

import numpy as np
import pytest

from bullpen_training.eval.promotion import driver
from bullpen_training.eval.promotion.driver import (
    _MIN_PARK_VAL_ROWS,
    _fit_per_park_temperature,
    _fit_temperature,
    _temperature_scale,
)
from bullpen_training.eval.promotion.sample_loader import (
    N_CLASSES,
    ParquetSampleLoader,
    feature_cols_for,
    generate_sample_dataset,
)


def _entropy(p: np.ndarray) -> float:
    return float(-(p * np.log(np.clip(p, 1e-12, 1.0))).sum())


# --- temperature math ---------------------------------------------------------


def test_temperature_scale_identity_at_T1() -> None:
    rng = np.random.default_rng(0)
    p = rng.dirichlet(np.ones(5), size=32).astype(np.float64)
    out = _temperature_scale(p, np.array(["BOS"] * 32), {"BOS": 1.0}, 1.0)
    np.testing.assert_allclose(out, p, atol=1e-9)


def test_temperature_scale_T_gt_1_softens_and_stays_a_distribution() -> None:
    p = np.array([[0.9, 0.05, 0.02, 0.02, 0.01]])
    soft = _temperature_scale(p, np.array(["BOS"]), {"BOS": 3.0}, 1.0)
    assert soft.sum() == pytest.approx(1.0)
    assert soft[0].max() < p[0].max()  # less confident
    assert _entropy(soft[0]) > _entropy(p[0])  # higher entropy


def test_temperature_scale_per_park_routing_and_cold_default() -> None:
    p = np.array([[0.9, 0.05, 0.02, 0.02, 0.01], [0.9, 0.05, 0.02, 0.02, 0.01]])
    # BOS softened at T=5; ZZZ absent -> default_t=1.0 (unchanged).
    out = _temperature_scale(p, np.array(["BOS", "ZZZ"]), {"BOS": 5.0}, 1.0)
    assert out[0].max() < p[0].max()
    np.testing.assert_allclose(out[1], p[1], atol=1e-9)


# --- temperature fit (val-only) -----------------------------------------------


def test_fit_temperature_softens_an_overconfident_model() -> None:
    # Model always predicts class 0 at 0.9, but the truth is a 50/50 coin between classes 0 and 1:
    # the NLL-minimising temperature must soften (T > 1).
    rng = np.random.default_rng(1)
    n = 4000
    probs = np.tile([0.9, 0.04, 0.03, 0.02, 0.01], (n, 1)).astype(np.float64)
    y = rng.integers(0, 2, n)
    assert _fit_temperature(np.log(probs), y) > 1.0


def test_fit_per_park_temperature_small_park_falls_back_to_pooled() -> None:
    rng = np.random.default_rng(2)
    n_big, n_small = _MIN_PARK_VAL_ROWS + 50, 10
    probs = np.tile([0.8, 0.1, 0.05, 0.03, 0.02], (n_big + n_small, 1)).astype(np.float64)
    parks = np.array(["BIG"] * n_big + ["SMALL"] * n_small)
    y = rng.integers(0, 5, n_big + n_small)
    temps, pooled_t = _fit_per_park_temperature(probs, parks, y)
    assert "BIG" in temps  # >= _MIN_PARK_VAL_ROWS rows -> own T
    assert "SMALL" not in temps  # too few rows -> uses the pooled T at apply time
    assert pooled_t > 0.0


# --- end-to-end through _mlp_factory ------------------------------------------


@pytest.mark.parametrize(
    "strategy", ["isotonic", "per_park_temperature", "per_park_temperature_isotonic"]
)
def test_mlp_factory_wires_each_calibration_strategy(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, strategy: str
) -> None:
    """All three calibration strategies train through _mlp_factory and emit a valid 5-class
    distribution. Tiny epochs keep it fast; isotonic carries no temperatures, the temperature
    strategies do (and fit at least one per-park T at 250 val rows/park)."""
    monkeypatch.setattr(driver, "_MLP_EPOCHS", 2)
    monkeypatch.setattr(driver, "_MLP_CALIBRATION", strategy)
    generate_sample_dataset(tmp_path, "batted_ball_mlp", rows_per_year=1500, years=[2015, 2016])
    loader = ParquetSampleLoader(tmp_path, "batted_ball_mlp")
    train, val = loader(2015, 2015, 0), loader(2016, 2016, 0)

    factory = driver._mlp_factory(feature_cols_for("batted_ball_mlp"), N_CLASSES["batted_ball_mlp"])
    pred = factory(train, val)
    proba = pred.predict_proba(val)

    assert proba.shape == (len(val), 5)
    np.testing.assert_allclose(proba.sum(axis=1), 1.0, atol=1e-5)
    if strategy == "isotonic":
        assert pred._temps is None
    else:
        assert pred._temps is not None
        assert len(pred._temps) >= 1  # >= one park hit _MIN_PARK_VAL_ROWS and fit its own T


def test_artifact_filename_encodes_nondefault_calibration(monkeypatch: pytest.MonkeyPatch) -> None:
    """A non-default calibration gets its own full-data filename so a recalibration run never clobbers
    the committed isotonic H2 row (#115); isotonic + other models keep the original name."""
    # isotonic (default) -> the #115 filename, unchanged.
    assert (
        driver._artifact_filename("batted_ball_mlp", "full")
        == "batted_ball_mlp_experiment_results_full.json"
    )
    monkeypatch.setattr(driver, "_MLP_CALIBRATION", "per_park_temperature")
    assert (
        driver._artifact_filename("batted_ball_mlp", "full")
        == "batted_ball_mlp_experiment_results_full_per_park_temperature.json"
    )
    # the calibration knob only affects batted_ball_mlp.
    assert (
        driver._artifact_filename("pitch_outcome_pre", "full")
        == "pitch_outcome_pre_experiment_results_full.json"
    )
