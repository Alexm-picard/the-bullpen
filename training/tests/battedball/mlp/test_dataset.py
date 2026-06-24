"""Tests for the dataset + feature scaler (Phase 2c.5).

The ClickHouse-backed ``load_rows`` is exercised by the smoke training
run; these tests cover the pure-Python helpers (one-hots, spray-angle
transform, scaler fit/transform/round-trip) so the unit suite has no
docker dependency.
"""

from __future__ import annotations

import numpy as np
import pytest

from bullpen_training.battedball.mlp.dataset import (
    BBIPDataset,
    FeatureScaler,
    _BipRow,
    _hc_to_spray_deg,
    base_state_one_hot,
    stand_one_hot,
)

# --- one-hot encoders ----------------------------------------------------


@pytest.mark.parametrize(("stand", "expected"), [("R", [1.0, 0.0]), ("L", [0.0, 1.0])])
def test_stand_one_hot(stand: str, expected: list[float]) -> None:
    np.testing.assert_array_equal(stand_one_hot(stand), np.array(expected, dtype=np.float32))


def test_stand_one_hot_unknown_falls_back_to_R() -> None:
    np.testing.assert_array_equal(stand_one_hot("?"), np.array([1.0, 0.0], dtype=np.float32))


@pytest.mark.parametrize("state", list(range(8)))
def test_base_state_one_hot_in_range(state: int) -> None:
    v = base_state_one_hot(state)
    assert v.shape == (8,)
    assert v.sum() == 1.0
    assert v[state] == 1.0


def test_base_state_out_of_range_is_zero_vec() -> None:
    np.testing.assert_array_equal(base_state_one_hot(99), np.zeros(8, dtype=np.float32))


# --- spray transform -----------------------------------------------------


def test_spray_dead_center_is_zero() -> None:
    assert _hc_to_spray_deg(125.42, 50.0) == pytest.approx(0.0, abs=0.01)


def test_spray_lf_side_is_positive() -> None:
    assert _hc_to_spray_deg(50.0, 80.0) > 0


def test_spray_rf_side_is_negative() -> None:
    assert _hc_to_spray_deg(200.0, 80.0) < 0


# --- FeatureScaler -------------------------------------------------------


def _fake_features(n: int = 100) -> np.ndarray:
    """A synthetic (n, 15) feature matrix with sensible continuous-vs-one-hot
    shape — matches the real ordering in FEATURE_NAMES."""
    rng = np.random.default_rng(0)
    out = np.zeros((n, 15), dtype=np.float32)
    out[:, 0] = rng.normal(88.0, 10.0, size=n)  # launch_speed
    out[:, 1] = rng.normal(15.0, 20.0, size=n)  # launch_angle
    out[:, 2] = rng.normal(0.0, 25.0, size=n)  # spray_angle
    out[:, 3] = rng.normal(200.0, 80.0, size=n)  # hit_distance
    # 4-5: stand one-hot
    flips = rng.integers(0, 2, size=n)
    out[np.arange(n), 4 + flips] = 1.0
    # 6-13: base_state one-hot
    states = rng.integers(0, 8, size=n)
    out[np.arange(n), 6 + states] = 1.0
    out[:, 14] = rng.integers(0, 3, size=n)  # outs
    return out


def test_fit_then_transform_centers_continuous_features() -> None:
    feats = _fake_features(500)
    scaler = FeatureScaler.fit(feats)
    transformed = scaler.transform(feats)
    # Continuous columns -> mean ~ 0, std ~ 1 post-transform.
    for col in (0, 1, 2, 3, 14):
        assert transformed[:, col].mean() == pytest.approx(0.0, abs=1e-5)
        assert transformed[:, col].std() == pytest.approx(1.0, abs=1e-4)


def test_fit_leaves_one_hot_columns_unchanged() -> None:
    feats = _fake_features(50)
    scaler = FeatureScaler.fit(feats)
    transformed = scaler.transform(feats)
    np.testing.assert_array_equal(transformed[:, 4:14], feats[:, 4:14])


def test_to_dict_round_trip_keys() -> None:
    feats = _fake_features(20)
    d = FeatureScaler.fit(feats).to_dict()
    assert set(d) == {"means", "stds", "is_continuous"}
    assert len(d["means"]) == 15
    assert len(d["stds"]) == 15
    assert len(d["is_continuous"]) == 15


def test_fit_handles_zero_variance_safely() -> None:
    """If a continuous column has zero variance (all same value), the
    scaler should clamp std to 1 to avoid /0 — keeps transform finite."""
    feats = _fake_features(10)
    feats[:, 0] = 100.0  # constant column
    scaler = FeatureScaler.fit(feats)
    # Transform shouldn't produce NaN/inf for the constant column.
    transformed = scaler.transform(feats)
    assert np.isfinite(transformed).all()


# --- BBIPDataset + scaler ------------------------------------------------


def _toy_row(seed: int = 0) -> _BipRow:
    rng = np.random.default_rng(seed)
    features = _fake_features(1)[0]
    labels = rng.dirichlet(np.ones(5), size=30).astype(np.float32)
    carry = rng.uniform(150.0, 420.0, size=30).astype(np.float32)
    return _BipRow(features=features, labels=labels, carry=carry, home_park_id="NYY")


def test_dataset_returns_raw_features_when_no_scaler() -> None:
    rows = [_toy_row(i) for i in range(3)]
    ds = BBIPDataset(rows)
    x, y, carry = ds[0]
    np.testing.assert_array_equal(x, rows[0].features)
    assert y.shape == (30, 5)
    # The _BipRow path surfaces the row's per-park carry verbatim.
    assert carry.shape == (30,)
    np.testing.assert_array_equal(carry, rows[0].carry)


def test_dataset_applies_scaler_when_provided() -> None:
    rows = [_toy_row(i) for i in range(50)]
    ds_raw = BBIPDataset(rows)
    scaler = FeatureScaler.fit(ds_raw.all_features())
    ds = BBIPDataset(rows, scaler=scaler)
    x, _y, _carry = ds[0]
    raw = rows[0].features
    expected = (raw - scaler.means) / scaler.stds
    np.testing.assert_allclose(x, expected, atol=1e-6)


def test_dataset_array_path_surfaces_carry_and_nans_where_absent() -> None:
    """The dense-array path returns the carry row when given one, and an
    all-NaN row when carry is omitted (outcome-only / legacy callers)."""
    feats = _fake_features(4)
    labels = np.random.default_rng(1).dirichlet(np.ones(5), size=(4, 30)).astype(np.float32)
    carry = np.full((4, 30), np.nan, dtype=np.float32)
    carry[0, 0] = 410.0  # one backfilled (BIP, park); the rest still NULL -> NaN
    ds = BBIPDataset(feats, labels, carry=carry)
    _x, _y, c = ds[0]
    assert c.shape == (30,)
    assert c[0] == pytest.approx(410.0)
    assert np.isnan(c[1])

    ds_nocarry = BBIPDataset(feats, labels)
    _x2, _y2, c2 = ds_nocarry[0]
    assert c2.shape == (30,)
    assert bool(np.isnan(c2).all())


def test_all_features_stacks_rows() -> None:
    rows = [_toy_row(i) for i in range(7)]
    arr = BBIPDataset(rows).all_features()
    assert arr.shape == (7, 15)
