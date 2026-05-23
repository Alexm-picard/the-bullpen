"""Python side of the Python↔Java parity test (Phase 1.4).

Loads the committed fixture (`tests/fixtures/parity_toy_001*.json`) and
re-runs every input row through Python's ONNX Runtime + the same
preprocess logic the Java side implements. Each prediction must match
the expected probability within `tolerance` (1e-6).

The Java mirror is `backend/.../inference/ToyParityTest.java`; both tests
read the same JSON files so the fixture is the contract.

This test is the canonical regression guard for the Python side of the
contract:

    feature_pipeline.json  (the spec)
        ↓
    Python preprocess()  ←——  asserted here
    Java FeaturePipeline ←——  asserted in Java parity test
        ↓
    model.onnx + ORT
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import cast

import numpy as np
import onnxruntime as ort
import pytest

from bullpen_training.battedball.parity_fixture import _preprocess

REPO_ROOT = Path(__file__).resolve().parents[2]
FIXTURE_DIR = REPO_ROOT / "tests" / "fixtures"
INPUT_PATH = FIXTURE_DIR / "parity_toy_001.json"
EXPECTED_PATH = FIXTURE_DIR / "parity_toy_001_expected.json"
ARTIFACT_DIR = REPO_ROOT / "artifacts" / "_toy" / "v0"
ONNX_PATH = ARTIFACT_DIR / "model.onnx"
PARK_LOOKUP_PATH = ARTIFACT_DIR / "park_hr_rate.json"

_REQUIRED_PATHS = (INPUT_PATH, EXPECTED_PATH, ONNX_PATH, PARK_LOOKUP_PATH)


pytestmark = pytest.mark.skipif(
    not all(p.exists() for p in _REQUIRED_PATHS),
    reason=(
        "Parity fixture or ONNX artifact missing — run the 1.4 export + "
        "parity_fixture pipeline locally to populate. CI sees these as "
        "produced upstream when the toy model + ONNX export job lands."
    ),
)


def _load_json(path: Path) -> dict[str, object]:
    return json.loads(path.read_text())


def test_input_and_expected_share_schema_hash() -> None:
    inp = _load_json(INPUT_PATH)
    exp = _load_json(EXPECTED_PATH)
    assert inp["schema_hash"] == exp["schema_hash"], (
        "input + expected fixtures came from different schema versions; "
        "regenerate both with parity_fixture.py"
    )


def test_python_onnx_matches_expected_for_every_row() -> None:
    inputs = _load_json(INPUT_PATH)
    expected = _load_json(EXPECTED_PATH)
    park_hr_rate: dict[str, float] = json.loads(PARK_LOOKUP_PATH.read_text())
    session = ort.InferenceSession(str(ONNX_PATH))
    tolerance = float(expected["tolerance"])  # type: ignore[arg-type]

    rows_in = inputs["rows"]
    rows_exp = expected["rows"]
    assert isinstance(rows_in, list) and isinstance(rows_exp, list)
    assert len(rows_in) == len(rows_exp)

    for raw, want in zip(rows_in, rows_exp, strict=True):
        assert isinstance(raw, dict)
        assert isinstance(want, dict)
        vector = _preprocess(raw, park_hr_rate)
        assert vector == want["feature_vector"], (
            f"preprocess drift on game_id={want['game_id']} "
            f"got {vector} wanted {want['feature_vector']}"
        )
        out = session.run(None, {"input": np.array([vector], dtype=np.float32)})
        probs = cast(np.ndarray, out[1] if len(out) > 1 else out[0])
        got = float(probs[0][1])
        wanted = float(want["onnx_probability"])  # type: ignore[arg-type]
        assert abs(got - wanted) < tolerance, (
            f"prob drift > {tolerance} on game_id={want['game_id']}: got {got} wanted {wanted}"
        )
