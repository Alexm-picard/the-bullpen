"""Synthetic end-to-end gate for the pooled LR baseline -> tiled ONNX exporter (decision [142]).

Builds a tiny real sklearn pipeline + calibrators with the module's own save_lr_baseline_bundle,
runs export, and asserts the invariants the registry + Java serving depend on:

  * tiled output [None, n_parks, 5], every park slice identical (park-agnostic floor)
  * tiled ONNX matches pipeline.predict_proba (the parity gate)
  * identity feature_scaler (StandardScaler is baked into the graph)
  * calibrators replicated per park; metadata declares the batted-ball contract schema_hash
"""

from __future__ import annotations

import json
from pathlib import Path

import numpy as np
from sklearn.isotonic import IsotonicRegression
from sklearn.linear_model import LogisticRegression
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import StandardScaler

from bullpen_training.battedball.features_shared import FEATURE_NAMES, OUTCOME_NAMES
from bullpen_training.battedball.lr_baseline.export_onnx import export
from bullpen_training.battedball.lr_baseline.train import (
    LrBaselineBundle,
    save_lr_baseline_bundle,
)

NF = len(FEATURE_NAMES)
PARKS = ("BOS", "LAD", "NYY")


def _toy_bundle() -> LrBaselineBundle:
    r = np.random.default_rng(1)
    x = r.normal(size=(400, NF))
    y = r.integers(0, len(OUTCOME_NAMES), size=400)
    pipe = Pipeline([("scale", StandardScaler()), ("lr", LogisticRegression(max_iter=300))]).fit(
        x, y
    )
    cals = []
    for _ in range(len(OUTCOME_NAMES)):
        iso = IsotonicRegression(out_of_bounds="clip")
        iso.fit(np.sort(r.random(150)), np.sort(r.random(150)))
        cals.append(iso)
    return LrBaselineBundle(
        pipeline=pipe,
        calibrators=cals,
        feature_columns=FEATURE_NAMES,
        outcome_names=OUTCOME_NAMES,
        park_order=PARKS,
        train_summary={"train_rows": 400},
    )


def test_lr_baseline_export_tiles_and_keeps_parity(tmp_path: Path) -> None:
    model_dir = tmp_path / "lr_baseline_batted_ball" / "v1"
    save_lr_baseline_bundle(_toy_bundle(), model_dir)

    result = export(artifact_dir=model_dir)

    md = json.loads((model_dir / "metadata.json").read_text())
    cal = json.loads((model_dir / "calibrator.json").read_text())

    assert (model_dir / "model.onnx").exists()
    assert md["park_order"] == list(PARKS)
    assert md["onnx"]["output_shape"][1:] == [len(PARKS), len(OUTCOME_NAMES)]
    assert md["feature_scaler"]["means"] == [0.0] * NF
    assert len(md["schema_hash"]) == 64
    assert list(cal["parks"]) == list(PARKS)
    # park-agnostic: every park's calibrators are identical
    assert cal["parks"]["BOS"] == cal["parks"]["NYY"]
    assert result["parity_max_diff"] < 1e-5
