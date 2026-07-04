"""Synthetic end-to-end gate for the LightGBM Option-A baseline -> ONNX exporter (decision [46]).

Mirrors ``lgbm_per_park/test_export_onnx.py``: builds one real-but-tiny booster with the module's
own ``save_baseline`` and runs ``export``, asserting the invariants the registry + Java serving
depend on:

  * one ``model.onnx`` (16 float inputs -> 5-class softmax), passing ``onnx.checker`` (the export
    runs it) and a ``sha256`` in the return value
  * ONNX output matches the native booster's ``predict`` (the parity gate). Unlike the per-park
    exporter, this one has no built-in parity self-check, so the ORT round-trip is done here.
  * the metadata declares the batted-ball LGBM contract's 64-hex schema_hash (rule 7)
  * a missing ``model.txt`` is a hard ``FileNotFoundError``

A plain (non-categorical) synthetic booster is used deliberately, exactly as the per-park test does:
this guards the convert + checker + schema-hash plumbing in CI. The ``park_id`` categorical-split
conversion fidelity is the one thing the module docstring defers to the desktop Python<->Java parity
check, not this cheap deterministic guard.
"""

from __future__ import annotations

from pathlib import Path

import lightgbm as lgb
import numpy as np
import pytest
from sklearn.isotonic import IsotonicRegression

from bullpen_training.battedball.features_shared import OUTCOME_NAMES
from bullpen_training.battedball.lgbm_baseline.dataset import FEATURE_COLUMNS
from bullpen_training.battedball.lgbm_baseline.export_onnx import export
from bullpen_training.battedball.lgbm_baseline.train import (
    LgbmBaselineBundle,
    save_baseline,
)

OUTCOMES = tuple(OUTCOME_NAMES)
NF = len(FEATURE_COLUMNS)


def _toy_bundle(seed: int) -> LgbmBaselineBundle:
    r = np.random.default_rng(seed)
    x = r.normal(size=(500, NF)).astype(np.float64)
    # park_id (the trailing feature) as a small integer code range - treated as numeric here so the
    # ONNX conversion is tight; the categorical path is a desktop-verify concern.
    x[:, -1] = r.integers(0, 5, size=500).astype(np.float64)
    y = r.integers(0, len(OUTCOMES), size=500)
    booster = lgb.train(
        {
            "objective": "multiclass",
            "num_class": len(OUTCOMES),
            "num_leaves": 7,
            "deterministic": True,
            "verbose": -1,
        },
        lgb.Dataset(x, label=y),
        num_boost_round=15,
    )
    calibrators = []
    for _ in range(len(OUTCOMES)):
        iso = IsotonicRegression(out_of_bounds="clip", y_min=0.0, y_max=1.0)
        iso.fit(np.sort(r.random(150)), np.sort(r.random(150)))
        calibrators.append(iso)
    return LgbmBaselineBundle(
        booster=booster,
        calibrators=calibrators,
        feature_columns=FEATURE_COLUMNS,
        outcome_names=OUTCOMES,
        park_categories=["A", "B", "C", "D", "E"],
        train_summary={"rows": 500},
    )


def test_export_writes_checked_onnx_with_parity_and_schema_hash(tmp_path: Path) -> None:
    artifact_dir = tmp_path / "batted_ball_lgbm_baseline_v1"
    bundle = _toy_bundle(seed=1)
    save_baseline(bundle, artifact_dir)

    result = export(artifact_dir=artifact_dir)  # default = the real committed LGBM contract

    onnx_path = artifact_dir / "model.onnx"
    assert onnx_path.exists()
    assert len(result["schema_hash"]) == 64
    assert len(result["onnx_sha256"]) == 64

    # The export ran onnx.checker; now assert ONNX-vs-native parity over a fresh batch.
    import onnxruntime as ort

    sess = ort.InferenceSession(str(onnx_path))
    # convert_lightgbm(zipmap=False) emits ['label' (int64), 'probabilities' (float)]; take the
    # float output BY NAME so ORT's broadly-typed run() result stays pyright-clean (the same
    # np.asarray(sess.run([name], ...)[0]) idiom the lgbm_per_park exporter uses).
    prob_name = next(o.name for o in sess.get_outputs() if o.type == "tensor(float)")
    rng = np.random.default_rng(99)
    xf = rng.normal(size=(32, NF)).astype(np.float32)
    xf[:, -1] = rng.integers(0, 5, size=32).astype(np.float32)
    prob = np.asarray(sess.run([prob_name], {"input": xf})[0], dtype=np.float64)
    want = np.asarray(bundle.booster.predict(xf.astype(np.float64)), dtype=np.float64)
    assert prob.shape == want.shape
    assert float(np.max(np.abs(prob - want))) < 1e-5


def test_export_raises_when_no_booster(tmp_path: Path) -> None:
    (tmp_path / "empty").mkdir()
    with pytest.raises(FileNotFoundError):
        export(artifact_dir=tmp_path / "empty")
