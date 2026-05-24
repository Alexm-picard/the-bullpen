"""Python side of the Python↔Java parity test for pitch_outcome_pre (Phase 2a.8).

Loads the committed fixture (`tests/fixtures/parity_pitch_pre_001*.json`)
and re-runs every input row through Python's ONNX Runtime + isotonic
calibrator + the same preprocess logic the Java side implements. Each
output must match the expected value within `tolerance` (1e-6) at every
stage (feature vector, raw probs, calibrated probs).

The Java mirror is `backend/.../inference/PitchPreParityTest.java`; both
sides read the same JSON files so the fixture is the contract.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import cast

import numpy as np
import onnxruntime as ort
import pytest

from bullpen_training.pitch.isotonic import IsotonicCalibrator
from bullpen_training.pitch.parity_fixture import (
    _load_park_mapping,
    _load_te_lookup,
    _preprocess,
)

REPO_ROOT = Path(__file__).resolve().parents[2]
CONTRACT_PATH = REPO_ROOT.parent / "contracts" / "feature_pipeline.json"
FIXTURE_DIR = REPO_ROOT / "tests" / "fixtures"
INPUT_PATH = FIXTURE_DIR / "parity_pitch_pre_001.json"
EXPECTED_PATH = FIXTURE_DIR / "parity_pitch_pre_001_expected.json"
ARTIFACT_DIR = REPO_ROOT / "artifacts" / "pitch_outcome_pre" / "v1"
ONNX_PATH = ARTIFACT_DIR / "model.onnx"
CALIBRATOR_PATH = ARTIFACT_DIR / "calibrator.json"
PARK_PATH = ARTIFACT_DIR / "park_id_mapping.json"
PITCHER_TE_PATH = ARTIFACT_DIR / "pitcher_te.json"
BATTER_TE_PATH = ARTIFACT_DIR / "batter_te.json"

_REQUIRED_PATHS = (
    INPUT_PATH,
    EXPECTED_PATH,
    ONNX_PATH,
    CALIBRATOR_PATH,
    PARK_PATH,
    PITCHER_TE_PATH,
    BATTER_TE_PATH,
    CONTRACT_PATH,
)

pytestmark = pytest.mark.skipif(
    not all(p.exists() for p in _REQUIRED_PATHS),
    reason=(
        "Parity fixture or production artifact missing — run "
        "`bullpen_training.pitch.production --model lightgbm --version v1` "
        "then `bullpen_training.pitch.export_pre_onnx` then "
        "`bullpen_training.pitch.parity_fixture` to populate."
    ),
)


def _load_json(path: Path) -> dict[str, object]:
    return json.loads(path.read_text())


def test_input_and_expected_share_schema_hash() -> None:
    inp = _load_json(INPUT_PATH)
    exp = _load_json(EXPECTED_PATH)
    assert inp["schema_hash"] == exp["schema_hash"], (
        "input + expected fixtures came from different schema versions; "
        "regenerate both with parity_fixture"
    )


def test_fixture_schema_hash_matches_contract() -> None:
    contract = _load_json(CONTRACT_PATH)
    inp = _load_json(INPUT_PATH)
    assert inp["schema_hash"] == contract["schema_hash"], (
        f"fixture schema_hash {inp['schema_hash']!r} != "
        f"contract schema_hash {contract['schema_hash']!r}; "
        "regenerate the fixture"
    )


def test_python_pipeline_matches_expected_for_every_row() -> None:
    inputs = _load_json(INPUT_PATH)
    expected = _load_json(EXPECTED_PATH)
    tolerance = float(expected["tolerance"])  # type: ignore[arg-type]

    park_id_to_int, park_missing = _load_park_mapping(PARK_PATH)
    pitcher_te, pitcher_prior = _load_te_lookup(PITCHER_TE_PATH)
    batter_te, batter_prior = _load_te_lookup(BATTER_TE_PATH)
    calibrator = IsotonicCalibrator.from_json(CALIBRATOR_PATH)
    session = ort.InferenceSession(str(ONNX_PATH))

    rows_in = inputs["rows"]
    rows_exp = expected["rows"]
    assert isinstance(rows_in, list) and isinstance(rows_exp, list)
    assert len(rows_in) == len(rows_exp)

    for raw, want in zip(rows_in, rows_exp, strict=True):
        assert isinstance(raw, dict)
        assert isinstance(want, dict)
        vector = _preprocess(
            raw,
            park_id_to_int=park_id_to_int,
            park_missing=park_missing,
            pitcher_te=pitcher_te,
            pitcher_prior=pitcher_prior,
            batter_te=batter_te,
            batter_prior=batter_prior,
        )
        want_vec = want["feature_vector"]
        assert isinstance(want_vec, list)
        for i, (g, w) in enumerate(zip(vector, want_vec, strict=True)):
            # null on the wire ↔ NaN in memory (Tier 3 missing values)
            if w is None and np.isnan(g):
                continue
            if w is None or (isinstance(g, float) and np.isnan(g)):
                raise AssertionError(
                    f"feature[{i}] one-side-NaN on game_id={want['game_id']}: got {g} wanted {w}"
                )
            assert (
                abs(g - w) < tolerance
            ), f"feature[{i}] drift on game_id={want['game_id']}: got {g} wanted {w}"

        out = session.run(None, {"input": np.array([vector], dtype=np.float32)})
        raw_probs = cast(np.ndarray, out[1] if len(out) > 1 else out[0])[0]
        want_raw = want["raw_probabilities"]
        assert isinstance(want_raw, list)
        for i, (g, w) in enumerate(zip(raw_probs.tolist(), want_raw, strict=True)):
            assert (
                abs(g - w) < tolerance
            ), f"raw_probs[{i}] drift on game_id={want['game_id']}: got {g} wanted {w}"

        calibrated = calibrator.transform(np.array([raw_probs], dtype=np.float64))[0]
        want_cal = want["calibrated_probabilities"]
        assert isinstance(want_cal, list)
        for i, (g, w) in enumerate(zip(calibrated.tolist(), want_cal, strict=True)):
            assert (
                abs(g - w) < tolerance
            ), f"calibrated[{i}] drift on game_id={want['game_id']}: got {g} wanted {w}"
