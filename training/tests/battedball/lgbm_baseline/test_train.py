"""Tests for the LightGBM Option-A baseline (Phase 2c.8).

The ClickHouse-backed `load_lgbm_dataset` is exercised by the smoke
training run; tests here use synthetic DataFrames so they run on CI
without docker.
"""

from __future__ import annotations

from pathlib import Path

import numpy as np
import pandas as pd
import pytest

from bullpen_training.battedball.lgbm_baseline.dataset import (
    FEATURE_COLUMNS,
    LABEL_COLUMN,
    PARK_FEATURE,
    outcome_int_to_name,
    outcome_name_to_int,
)
from bullpen_training.battedball.lgbm_baseline.train import (
    LgbmBaselineBundle,
    load_baseline,
    predict_proba,
    predict_proba_calibrated,
    save_baseline,
    train_lgbm,
)


def _synthetic_df(n: int = 800, seed: int = 0) -> pd.DataFrame:
    """Build a (BIP x park) flattened synthetic DataFrame.

    Encodes a learnable signal: high launch_speed + park 'COL' -> HR;
    low launch_speed + park 'SF' -> OUT. The booster should beat a
    uniform prior on this.
    """
    rng = np.random.default_rng(seed)
    parks = ["COL", "SF", "NYY", "BOS", "STL"]
    rows: list[dict[str, object]] = []
    for _ in range(n):
        park = rng.choice(parks)
        ls = rng.normal(88.0, 12.0)
        la = rng.normal(15.0, 18.0)
        # Outcome rule: COL + 95+/20+ -> HR (4), SF + <85 -> OUT (0),
        # else mix into 1B/2B/3B.
        if park == "COL" and ls > 95 and la > 20:
            label = 4
        elif park == "SF" and ls < 85:
            label = 0
        else:
            label = int(rng.choice([0, 1, 2, 3], p=[0.55, 0.30, 0.12, 0.03]))
        row: dict[str, object] = {
            "launch_speed_mph": float(ls),
            "launch_angle_deg": float(la),
            "spray_angle_deg": float(rng.normal(0.0, 20.0)),
            "hit_distance_ft": float(rng.normal(250.0, 80.0)),
            "stand_R": 1.0,
            "stand_L": 0.0,
            "outs": float(rng.integers(0, 3)),
            "park_id": park,
            LABEL_COLUMN: label,
        }
        for i in range(8):
            row[f"base_state_{i}"] = 1.0 if i == 0 else 0.0
        rows.append(row)
    df = pd.DataFrame.from_records(rows)
    df["park_id"] = df["park_id"].astype("category")
    return df


# --- outcome int <-> name helpers --------------------------------------


@pytest.mark.parametrize(("idx", "name"), [(0, "out"), (1, "1b"), (2, "2b"), (3, "3b"), (4, "hr")])
def test_outcome_int_name_round_trip(idx: int, name: str) -> None:
    assert outcome_int_to_name(idx) == name
    assert outcome_name_to_int(name) == idx


# --- dataset shape guards ---------------------------------------------


def test_synthetic_df_has_expected_columns() -> None:
    df = _synthetic_df(50)
    for col in FEATURE_COLUMNS:
        assert col in df.columns, f"missing feature column {col}"
    assert LABEL_COLUMN in df.columns
    assert isinstance(df[PARK_FEATURE].dtype, pd.CategoricalDtype)


# --- training basics --------------------------------------------------


def test_train_lgbm_returns_bundle_with_5_calibrators() -> None:
    train = _synthetic_df(400, seed=1)
    val = _synthetic_df(150, seed=2)
    bundle = train_lgbm(train, val, num_boost_round=40, early_stopping=10)
    assert isinstance(bundle, LgbmBaselineBundle)
    assert len(bundle.calibrators) == 5
    assert bundle.outcome_names == ("out", "1b", "2b", "3b", "hr")
    assert PARK_FEATURE in bundle.feature_columns
    assert set(bundle.park_categories) == set(train[PARK_FEATURE].cat.categories)


def test_predict_proba_has_correct_shape_and_sums() -> None:
    train = _synthetic_df(400, seed=10)
    bundle = train_lgbm(train, num_boost_round=30)
    probs = predict_proba(bundle, train.head(50))
    assert probs.shape == (50, 5)
    # LightGBM multiclass returns softmax by default.
    np.testing.assert_allclose(probs.sum(axis=-1), np.ones(50), atol=1e-5)


def test_predict_proba_calibrated_renormalises_per_row() -> None:
    train = _synthetic_df(400, seed=11)
    val = _synthetic_df(150, seed=12)
    bundle = train_lgbm(train, val, num_boost_round=40)
    probs = predict_proba_calibrated(bundle, val.head(40))
    assert probs.shape == (40, 5)
    np.testing.assert_allclose(probs.sum(axis=-1), np.ones(40), atol=1e-5)
    assert (probs >= 0).all() and (probs <= 1).all()


def test_park_id_actually_drives_predictions() -> None:
    """A 110 mph / 30 deg blast at COL should predict more HR than the
    same input at SF — the headline 'park_id matters' check."""
    train = _synthetic_df(800, seed=20)
    bundle = train_lgbm(train, num_boost_round=80)
    canonical_row: dict[str, object] = {
        "launch_speed_mph": 110.0,
        "launch_angle_deg": 30.0,
        "spray_angle_deg": 0.0,
        "hit_distance_ft": 410.0,
        "stand_R": 1.0,
        "stand_L": 0.0,
        "outs": 1.0,
        "park_id": "COL",
    }
    for i in range(8):
        canonical_row[f"base_state_{i}"] = 1.0 if i == 0 else 0.0
    coors_df = pd.DataFrame([{**canonical_row, "park_id": "COL"}])
    oracle_df = pd.DataFrame([{**canonical_row, "park_id": "SF"}])
    p_coors = predict_proba(bundle, coors_df)
    p_oracle = predict_proba(bundle, oracle_df)
    assert p_coors[0, 4] > p_oracle[0, 4], (
        f"COL HR prob {p_coors[0, 4]:.3f} should beat SF {p_oracle[0, 4]:.3f}"
    )


def test_train_rejects_missing_label_column() -> None:
    train = _synthetic_df(50).drop(columns=[LABEL_COLUMN])
    with pytest.raises(ValueError, match=LABEL_COLUMN):
        train_lgbm(train, num_boost_round=10)


def test_train_rejects_missing_feature_column() -> None:
    train = _synthetic_df(50).drop(columns=["launch_speed_mph"])
    with pytest.raises(ValueError, match="missing feature columns"):
        train_lgbm(train, num_boost_round=10)


# --- persistence round-trip -------------------------------------------


def test_save_load_round_trip_preserves_predictions(tmp_path: Path) -> None:
    train = _synthetic_df(500, seed=30)
    val = _synthetic_df(150, seed=31)
    bundle = train_lgbm(train, val, num_boost_round=50)
    save_baseline(bundle, tmp_path / "v1")
    reloaded = load_baseline(tmp_path / "v1")

    sample = val.head(20).copy()
    p_a_raw = predict_proba(bundle, sample)
    p_b_raw = predict_proba(reloaded, sample)
    np.testing.assert_allclose(p_a_raw, p_b_raw, atol=1e-6)

    p_a_cal = predict_proba_calibrated(bundle, sample)
    p_b_cal = predict_proba_calibrated(reloaded, sample)
    np.testing.assert_allclose(p_a_cal, p_b_cal, atol=1e-6)


def test_save_writes_expected_files(tmp_path: Path) -> None:
    train = _synthetic_df(150, seed=40)
    bundle = train_lgbm(train, num_boost_round=20)
    save_baseline(bundle, tmp_path / "v1")
    for name in ("model.txt", "calibrator.json", "metadata.json"):
        assert (tmp_path / "v1" / name).exists(), f"missing {name}"


def test_metadata_records_park_categories_and_feature_columns(tmp_path: Path) -> None:
    train = _synthetic_df(150, seed=50)
    bundle = train_lgbm(train, num_boost_round=20)
    save_baseline(bundle, tmp_path / "v1")
    import json

    md = json.loads((tmp_path / "v1" / "metadata.json").read_text())
    assert md["model_name"] == "batted_ball_lgbm_baseline"
    assert md["framework"] == "lightgbm"
    assert md["feature_columns"] == list(FEATURE_COLUMNS)
    assert md["categorical_features"] == [PARK_FEATURE]
    assert set(md["park_categories"]) == set(train[PARK_FEATURE].cat.categories)
    assert md["outcome_names"] == ["out", "1b", "2b", "3b", "hr"]


def test_predict_handles_park_id_as_plain_string(tmp_path: Path) -> None:
    """Inference callers might pass park_id as a plain string column
    rather than a pandas Categorical — predict_proba re-encodes."""
    train = _synthetic_df(300, seed=60)
    bundle = train_lgbm(train, num_boost_round=40)
    raw = train.head(10).copy()
    raw[PARK_FEATURE] = raw[PARK_FEATURE].astype(str)
    probs = predict_proba(bundle, raw)
    assert probs.shape == (10, 5)
