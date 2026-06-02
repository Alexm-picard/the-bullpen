"""Tests for the batted-ball spin model (Phase 1 physics overhaul)."""

from __future__ import annotations

import json

import numpy as np
import pytest

from bullpen_training.battedball.physics.spin import (
    DEFAULT_CALIBRATION,
    DEFAULT_COEFFS,
    PHYSICS_PRIOR_COEFFS,
    PhysicsCalibration,
    SpinCoeffs,
    batted_ball_spin,
    load_physics_calibration,
    load_spin_coeffs,
)


def test_physics_prior_is_physical_across_launch_angles() -> None:
    """The fixed physics-prior spin must stay in a realistic backspin band over
    the HR launch-angle range (no flooring to the clamp), rising then levelling."""
    rates = {
        la: batted_ball_spin(103.0, float(la), 0.0, PHYSICS_PRIOR_COEFFS)[0] for la in (15, 25, 35)
    }
    for la, r in rates.items():
        assert 1500.0 <= r <= 2400.0, f"LA={la} backspin {r:.0f} outside physical band"
    assert rates[25] > rates[15], "backspin should rise from LA 15 to 25"


def test_physics_calibration_default_is_legacy() -> None:
    assert DEFAULT_CALIBRATION.cd_scale == 1.0
    assert DEFAULT_CALIBRATION.spin == DEFAULT_COEFFS


def test_physics_calibration_roundtrip(tmp_path) -> None:
    c = PhysicsCalibration(
        spin=SpinCoeffs(b0=2000.0, b1_ev=8.0, b2_la=15.0, b3_la2=-0.3, k_side=0.5), cd_scale=1.06
    )
    assert PhysicsCalibration.from_dict(c.to_dict()) == c
    p = tmp_path / "physics_calibration.json"
    p.write_text(json.dumps(c.to_dict()))
    assert load_physics_calibration(p) == c
    assert load_physics_calibration(None) == DEFAULT_CALIBRATION
    assert load_physics_calibration(tmp_path / "missing.json") == DEFAULT_CALIBRATION


def test_physics_calibration_rejects_unknown_schema() -> None:
    with pytest.raises(ValueError, match="schema_version"):
        PhysicsCalibration.from_dict(
            {"schema_version": 999, "cd_scale": 1.0, "spin": DEFAULT_COEFFS.to_dict()}
        )


def test_default_coeffs_reproduce_legacy_flat_backspin() -> None:
    """Wiring the model in with defaults must be a no-op vs the 1800/180 prior."""
    for ev, la, spray in [(110, 28, 0), (95, 15, -35), (105, 35, 25)]:
        rate, tilt = batted_ball_spin(ev, la, spray)
        assert rate == pytest.approx(1800.0)
        assert tilt == pytest.approx(180.0)


def test_backspin_increases_with_ev() -> None:
    c = SpinCoeffs(b0=1500.0, b1_ev=10.0)
    lo, _ = batted_ball_spin(95.0, 25.0, 0.0, c)
    hi, _ = batted_ball_spin(110.0, 25.0, 0.0, c)
    assert hi > lo


def test_launch_angle_curve_applies() -> None:
    c = SpinCoeffs(b0=1000.0, b2_la=40.0, b3_la2=-0.5)
    r10, _ = batted_ball_spin(100.0, 10.0, 0.0, c)
    r30, _ = batted_ball_spin(100.0, 30.0, 0.0, c)
    assert r10 != r30  # the LA terms are live


def test_sidespin_tilts_axis_with_spray() -> None:
    c = SpinCoeffs(k_side=0.5)
    _, tilt_center = batted_ball_spin(105.0, 25.0, 0.0, c)
    _, tilt_pull = batted_ball_spin(105.0, 25.0, 30.0, c)
    assert tilt_center == pytest.approx(180.0)
    assert tilt_pull != pytest.approx(180.0)


def test_backspin_and_tilt_clamped() -> None:
    hot = SpinCoeffs(b0=99999.0)
    rate, _ = batted_ball_spin(110.0, 28.0, 0.0, hot)
    assert rate == pytest.approx(3500.0)  # clamp ceiling
    steep = SpinCoeffs(k_side=100.0)
    _, tilt = batted_ball_spin(110.0, 28.0, 40.0, steep)
    assert tilt == pytest.approx(240.0)  # clamp ceiling


def test_vectorised_matches_scalar() -> None:
    c = SpinCoeffs(b0=1600.0, b1_ev=8.0, b2_la=20.0, b3_la2=-0.4, k_side=0.3)
    ev = np.array([95.0, 105.0, 112.0])
    la = np.array([18.0, 26.0, 33.0])
    spray = np.array([-30.0, 0.0, 25.0])
    rate_v, tilt_v = batted_ball_spin(ev, la, spray, c)
    for i in range(3):
        r, t = batted_ball_spin(float(ev[i]), float(la[i]), float(spray[i]), c)
        assert r == pytest.approx(float(rate_v[i]))
        assert t == pytest.approx(float(tilt_v[i]))


def test_coeffs_roundtrip_dict_and_vector() -> None:
    c = SpinCoeffs(b0=1550.0, b1_ev=7.5, b2_la=18.0, b3_la2=-0.35, k_side=0.42)
    assert SpinCoeffs.from_dict(c.to_dict()) == c
    assert SpinCoeffs.from_vector(c.as_vector()) == c


def test_from_dict_rejects_unknown_schema() -> None:
    with pytest.raises(ValueError, match="schema_version"):
        SpinCoeffs.from_dict(
            {"schema_version": 999, "b0": 1, "b1_ev": 0, "b2_la": 0, "b3_la2": 0, "k_side": 0}
        )


def test_load_returns_default_when_absent(tmp_path) -> None:
    assert load_spin_coeffs(None) is DEFAULT_COEFFS
    assert load_spin_coeffs(tmp_path / "missing.json") is DEFAULT_COEFFS


def test_load_reads_fitted_coeffs(tmp_path) -> None:
    c = SpinCoeffs(b0=1400.0, b1_ev=6.0, b2_la=15.0, b3_la2=-0.3, k_side=0.5)
    p = tmp_path / "spin_model.json"
    p.write_text(json.dumps(c.to_dict()))
    assert load_spin_coeffs(p) == c
