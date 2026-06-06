"""Synthetic end-to-end gate for the per-park LightGBM -> combined ONNX exporter (decision [142]).

Builds a few real-but-tiny per-park boosters with the module's own ``save_per_park_bundle`` and
runs ``export``, asserting the invariants the registry + Java serving depend on:

  * one combined ONNX, output ``[None, n_parks, 5]`` (park axis = sorted park order)
  * combined output matches stacking the native booster predictions (the parity gate)
  * identity ``feature_scaler`` in metadata (LightGBM is scale-invariant; the shared Java
    FeaturePipelineBattedBall applies the scaler, so identity => raw passthrough)
  * the merged calibrator's park axis aligns with the ONNX / metadata park_order
  * the metadata declares the batted-ball contract's schema_hash (rule 7)

Real 30-park scale runs on the desktop (the boosters live there, ADR-0006); this is the cheap,
deterministic guard that the combine + merge plumbing stays correct in CI.
"""

from __future__ import annotations

import json
from pathlib import Path

import lightgbm as lgb
import numpy as np
import pytest
from sklearn.isotonic import IsotonicRegression

from bullpen_training.battedball.lgbm_per_park.dataset import FEATURE_COLUMNS
from bullpen_training.battedball.lgbm_per_park.export_onnx import export
from bullpen_training.battedball.lgbm_per_park.train import (
    LgbmPerParkBundle,
    save_per_park_bundle,
)

OUTCOMES = ("out", "1b", "2b", "3b", "hr")
NF = len(FEATURE_COLUMNS)


def _toy_bundle(park_id: str, seed: int) -> LgbmPerParkBundle:
    r = np.random.default_rng(seed)
    x = r.normal(size=(400, NF)).astype(np.float64)
    y = r.integers(0, len(OUTCOMES), size=400)
    booster = lgb.train(
        {"objective": "multiclass", "num_class": len(OUTCOMES), "verbose": -1, "num_leaves": 7},
        lgb.Dataset(x, label=y),
        num_boost_round=12,
    )
    cals = []
    for _ in range(len(OUTCOMES)):
        iso = IsotonicRegression(out_of_bounds="clip")
        iso.fit(np.sort(r.random(150)), np.sort(r.random(150)))
        cals.append(iso)
    return LgbmPerParkBundle(
        park_id=park_id,
        booster=booster,
        calibrators=cals,
        feature_columns=FEATURE_COLUMNS,
        outcome_names=OUTCOMES,
        train_summary={"rows": 400},
    )


def test_export_combines_sorts_and_keeps_parity(tmp_path: Path) -> None:
    root = tmp_path / "battedball_lgbm_per_park_v1"
    # deliberately out of sorted order to prove the exporter imposes the canonical sort
    for park_id, seed in [("NYY", 2), ("BOS", 1), ("LAD", 3)]:
        save_per_park_bundle(_toy_bundle(park_id, seed), root / park_id)

    result = export(artifact_dir=root)

    md = json.loads((root / "metadata.json").read_text())
    cal = json.loads((root / "calibrator.json").read_text())

    assert (root / "model.onnx").exists()
    assert md["park_order"] == ["BOS", "LAD", "NYY"]  # sorted, not input order
    assert md["onnx"]["output"] == "park_outcome_probs"
    assert md["onnx"]["output_shape"][1:] == [3, len(OUTCOMES)]
    assert md["feature_scaler"]["means"] == [0.0] * NF
    assert md["feature_scaler"]["stds"] == [1.0] * NF
    assert len(md["schema_hash"]) == 64
    assert cal["park_order"] == md["park_order"]
    assert list(cal["parks"]) == ["BOS", "LAD", "NYY"]
    assert result["parity_max_diff"] < 1e-5


def test_export_raises_when_no_boosters(tmp_path: Path) -> None:
    (tmp_path / "empty").mkdir()
    with pytest.raises(FileNotFoundError):
        export(artifact_dir=tmp_path / "empty")
