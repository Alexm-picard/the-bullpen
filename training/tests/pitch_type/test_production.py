"""Unit test for the pitch_type_pre production orchestrator + persistence (no ClickHouse).

A synthetic loader drives `train_and_persist(skip_cv=True)` into a tmp artifacts dir; asserts
the canonical files land and are well-formed - the temperature calibrator, the pitch_type_pre
contract copy, the park map, metadata provenance, the parquet snapshot, and eval/.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import ClassVar

import numpy as np
import pandas as pd

from bullpen_training.pitch_type import PITCH_TYPE_CLASSES, PITCH_TYPE_FEATURE_COLUMNS
from bullpen_training.pitch_type.production import train_and_persist

_ARS = ("ars_FF", "ars_SI", "ars_FC", "ars_SL", "ars_CU", "ars_CH", "ars_OFF", "ars_FF_by_count")


def _frame(n: int = 1_200, seed: int = 0) -> pd.DataFrame:
    """A synthetic 24-feature frame + y7 label with a learnable ars_FF signal (enough rows for
    min_data_in_leaf=200 to grow trees)."""
    rng = np.random.default_rng(seed)
    df = pd.DataFrame()
    df["balls"] = rng.integers(0, 4, n).astype("int8")
    df["strikes"] = rng.integers(0, 3, n).astype("int8")
    df["outs"] = rng.integers(0, 3, n).astype("int8")
    df["inning"] = rng.integers(1, 10, n).astype("int8")
    df["base_state"] = rng.integers(0, 8, n).astype("int8")
    df["stand_i"] = rng.integers(0, 2, n).astype("int8")
    df["throws_i"] = rng.integers(0, 2, n).astype("int8")
    df["park_i"] = rng.integers(0, 3, n).astype("int16")
    df["times_through_order"] = rng.integers(1, 4, n).astype("float32")
    df["at_bat_number_in_game"] = rng.integers(1, 40, n).astype("float32")
    df["times_faced_today"] = rng.integers(0, 4, n).astype("float32")
    for c in _ARS:
        df[c] = rng.random(n).astype("float32")
    df["pitcher_prior_n"] = rng.integers(0, 500, n).astype("int32")
    df["prev1_pt_i"] = rng.integers(-1, 7, n).astype("int8")
    df["prev2_pt_i"] = rng.integers(-1, 7, n).astype("int8")
    df["prev1_missing"] = (df["prev1_pt_i"] == -1).astype("int8")
    df["pitches_into_outing"] = rng.integers(0, 100, n).astype("int16")
    k = len(PITCH_TYPE_CLASSES)
    df["label"] = (df["ars_FF"] * k).astype("int8").clip(0, k - 1)
    assert set(PITCH_TYPE_FEATURE_COLUMNS).issubset(df.columns)
    return df


class _SyntheticLoader:
    """A ClickHouse-free (start_year, end_year, fold_id) -> frame loader with a park map."""

    park_id_mapping: ClassVar[dict[str, int]] = {"PARK00": 0, "PARK01": 1, "PARK02": 2}

    def __call__(self, start_year: int, end_year: int, fold_id: int) -> pd.DataFrame:
        return _frame(n=1_200, seed=start_year)


def test_train_and_persist_writes_canonical_files(tmp_path: Path) -> None:
    out = train_and_persist(
        _SyntheticLoader(),
        version="v1",
        artifacts_dir=tmp_path,
        skip_cv=True,
        num_boost_round=40,
        early_stopping_rounds=8,
    )
    for name in (
        "model.lgb",
        "calibrator.json",
        "feature_pipeline.json",
        "park_id_mapping.json",
        "metadata.json",
        "training_data.parquet",
    ):
        assert (out / name).exists(), f"missing canonical file: {name}"
    assert (out / "eval" / "metrics.json").exists()

    meta = json.loads((out / "metadata.json").read_text())
    assert meta["model_name"] == "pitch_type_pre"
    assert meta["calibrator"]["kind"] == "temperature"
    assert meta["hyperparams"]["num_class"] == 7
    assert meta["training_data_window"] == "2015-2023" and meta["val_window"] == "2024"
    # feature_pipeline_hash in metadata matches the copied contract's declared hash.
    fp = json.loads((out / "feature_pipeline.json").read_text())
    assert fp["model_name"] == "pitch_type_pre"
    assert meta["feature_pipeline_hash"] == fp["schema_hash"]


def test_persisted_calibrator_and_park_map_are_well_formed(tmp_path: Path) -> None:
    out = train_and_persist(
        _SyntheticLoader(),
        version="v3",
        artifacts_dir=tmp_path,
        skip_cv=True,
        num_boost_round=30,
        early_stopping_rounds=6,
    )
    cal = json.loads((out / "calibrator.json").read_text())
    assert cal["kind"] == "temperature"
    assert cal["temperature"] > 0
    assert list(cal["class_labels"]) == list(PITCH_TYPE_CLASSES)

    pm = json.loads((out / "park_id_mapping.json").read_text())
    assert pm["park_id"] == {"PARK00": 0, "PARK01": 1, "PARK02": 2}
    assert pm["missing_value"] == -1

    snap = pd.read_parquet(out / "training_data.parquet")
    assert "label" in snap.columns
    assert set(PITCH_TYPE_FEATURE_COLUMNS).issubset(snap.columns)


def test_out_dir_layout(tmp_path: Path) -> None:
    out = train_and_persist(
        _SyntheticLoader(),
        version="v9",
        artifacts_dir=tmp_path,
        skip_cv=True,
        num_boost_round=30,
        early_stopping_rounds=6,
    )
    assert out == tmp_path / "pitch_type_pre" / "v9"
