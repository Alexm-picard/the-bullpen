"""Tests for the shared isotonic primitives (road-to-9s M-task 23).

The load-bearing property is BYTE-STABILITY of the serialized calibrator shape:
``calibrator.json`` is a Java-contract file (``IsotonicCalibratorJava`` /
``BattedBallCalibrators.load``), so the dict produced by
:func:`isotonic_to_dict` must be exactly what the five pre-refactor inline
copies produced - same keys, same ORDER (json.dumps follows dict insertion
order), same value shapes. The containers' own schema tests
(``tests/battedball/mlp/test_calibration.py`` and the export tests) run
unmodified against the refactored code as the before/after contract pin; this
module pins the primitive layer directly.
"""

from __future__ import annotations

import numpy as np
import pytest

from bullpen_training.eval.calibration import (
    apply_per_class_isotonic,
    fit_isotonic,
    isotonic_from_dict,
    isotonic_to_dict,
)

# The historical per-class entry shape, verbatim from the five former copies.
EXPECTED_KEY_ORDER = [
    "outcome",
    "x_thresholds",
    "y_thresholds",
    "y_min",
    "y_max",
    "out_of_bounds",
]


def _fitted_unit_iso():
    """A deterministic fit whose breakpoints are hand-checkable (see the golden test)."""
    x = np.array([0.0, 0.25, 0.5, 0.75, 1.0])
    y = np.array([0.0, 0.0, 1.0, 1.0, 1.0])
    return fit_isotonic(x, y, y_min=0.0, y_max=1.0)


def test_golden_breakpoints_are_the_hand_checkable_step() -> None:
    # y is already monotone in x, so isotonic preserves it and collapses the
    # duplicate-y runs: thresholds keep the run endpoints.
    d = isotonic_to_dict(_fitted_unit_iso(), "hr")
    assert d["x_thresholds"] == [0.0, 0.25, 0.5, 1.0]
    assert d["y_thresholds"] == [0.0, 0.0, 1.0, 1.0]


def test_to_dict_preserves_the_historical_key_order() -> None:
    d = isotonic_to_dict(_fitted_unit_iso(), "hr")
    assert list(d.keys()) == EXPECTED_KEY_ORDER


def test_to_dict_values_match_the_historical_shape() -> None:
    d = isotonic_to_dict(_fitted_unit_iso(), "hr")
    assert d["outcome"] == "hr"
    assert isinstance(d["x_thresholds"], list)
    assert isinstance(d["y_thresholds"], list)
    assert all(isinstance(v, float) for v in d["x_thresholds"])
    assert all(isinstance(v, float) for v in d["y_thresholds"])
    assert d["y_min"] == 0.0
    assert d["y_max"] == 1.0
    assert d["out_of_bounds"] == "clip"


def test_to_dict_serializes_unset_clamps_as_none() -> None:
    # The LR baseline fits without y_min/y_max - those serialize as null, as before.
    x = np.array([0.0, 0.5, 1.0])
    y = np.array([0.1, 0.4, 0.9])
    d = isotonic_to_dict(fit_isotonic(x, y), "out")
    assert d["y_min"] is None
    assert d["y_max"] is None


def test_round_trip_dict_is_identical() -> None:
    original = isotonic_to_dict(_fitted_unit_iso(), "1b")
    rebuilt = isotonic_to_dict(isotonic_from_dict(original), "1b")
    assert rebuilt == original


def test_round_trip_dict_is_identical_without_clamps() -> None:
    x = np.array([0.0, 0.3, 0.6, 1.0])
    y = np.array([0.05, 0.2, 0.5, 0.8])
    original = isotonic_to_dict(fit_isotonic(x, y), "2b")
    rebuilt = isotonic_to_dict(isotonic_from_dict(original), "2b")
    assert rebuilt == original


def test_from_dict_transform_matches_fitted_transform() -> None:
    iso = _fitted_unit_iso()
    rebuilt = isotonic_from_dict(isotonic_to_dict(iso, "hr"))
    probe = np.linspace(0.0, 1.0, 21)
    np.testing.assert_allclose(rebuilt.transform(probe), iso.transform(probe), atol=1e-12)


def test_from_dict_clips_out_of_bounds_inputs() -> None:
    d = {
        "outcome": "hr",
        "x_thresholds": [0.2, 0.8],
        "y_thresholds": [0.1, 0.9],
        "y_min": 0.0,
        "y_max": 1.0,
        "out_of_bounds": "clip",
    }
    iso = isotonic_from_dict(d)
    out = iso.transform(np.array([0.0, 1.0]))
    np.testing.assert_allclose(out, [0.1, 0.9])


def test_fit_isotonic_kwargs_pass_through() -> None:
    x = np.array([0.0, 0.5, 1.0])
    y = np.array([0.0, 0.5, 1.0])
    default = fit_isotonic(x, y)
    clamped = fit_isotonic(x, y, y_min=0.0, y_max=1.0, increasing=True)
    assert default.y_min is None and default.y_max is None
    assert clamped.y_min == 0.0 and clamped.y_max == 1.0
    assert default.out_of_bounds == "clip"
    assert clamped.out_of_bounds == "clip"


def test_fit_isotonic_defaults_to_increasing_true_not_auto() -> None:
    # Regression test for the M-task-23 audit catch: sklearn's default is
    # increasing=True and every historical fit site used it. An "auto" default
    # would fit a DECREASING calibrator on negatively-correlated data - a
    # wrong-direction output change. Pin the shared fit to the increasing
    # behavior even where "auto" would flip.
    x = np.array([0.0, 0.25, 0.5, 0.75, 1.0])
    y_neg = np.array([1.0, 0.8, 0.5, 0.2, 0.0])  # perfectly DECREASING target
    iso = fit_isotonic(x, y_neg)
    assert iso.increasing_ is np.True_ or iso.increasing_ is True
    # Under increasing=True a decreasing target collapses toward its mean - the
    # transform must still be monotone NON-DECREASING, never a decreasing fit.
    out = iso.transform(x)
    assert (np.diff(out) >= 0).all()


def test_apply_per_class_isotonic_renormalises_and_is_float32() -> None:
    rng = np.random.default_rng(7)
    raw = rng.dirichlet(np.ones(5), size=64)
    calibrators = [
        fit_isotonic(raw[:, c], rng.uniform(0, 1, size=64), y_min=0.0, y_max=1.0) for c in range(5)
    ]
    out = apply_per_class_isotonic(calibrators, raw)
    assert out.shape == raw.shape
    assert out.dtype == np.float32
    np.testing.assert_allclose(out.sum(axis=-1), 1.0, atol=1e-5)
    assert (out > 0).all()  # the 1e-9 floor means never exactly 0 pre-renorm


def test_apply_per_class_isotonic_survives_all_zero_rows() -> None:
    # A calibrator grid that maps everything to 0 would NaN without the eps
    # floor; the shared helper must renormalise to a valid distribution.
    zero_map = {
        "outcome": "out",
        "x_thresholds": [0.0, 1.0],
        "y_thresholds": [0.0, 0.0],
        "y_min": 0.0,
        "y_max": 1.0,
        "out_of_bounds": "clip",
    }
    calibrators = [isotonic_from_dict(zero_map) for _ in range(3)]
    out = apply_per_class_isotonic(calibrators, np.full((4, 3), 1.0 / 3.0))
    assert not np.isnan(out).any()
    np.testing.assert_allclose(out.sum(axis=-1), 1.0, atol=1e-6)


def test_apply_per_class_isotonic_rejects_mismatched_width() -> None:
    calibrators = [isotonic_from_dict(isotonic_to_dict(_fitted_unit_iso(), "o"))]
    with pytest.raises(ValueError, match="3 classes"):
        apply_per_class_isotonic(calibrators, np.full((2, 3), 1.0 / 3.0))
