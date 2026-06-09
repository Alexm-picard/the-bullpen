"""Tiny deterministic pitch register->serve fixtures (W4b).

Builds the small artifacts that let the register->serve path be exercised locally
without the box: a deterministic ONNX graph, the Tier-2/Tier-1 (+ POST Tier-4)
lookups, an identity calibrator, and a one-row parity case. Mirrors the backend
fixture pattern at ``backend/src/test/resources/onnx/make_pitch_outcome_fixture.py``
(Gather the first ``n_classes`` features off axis 1, then Softmax) so a known
input vector yields a deterministic, assertable 5-class distribution.

The ONNX is parameterized by feature width: pre = 31 -> 5, post = 41 -> 5. The
input tensor is named ``"input"`` and the output ``"probabilities"`` by
convention (the Java reader resolves the input name from the session, so this is
documentation, not a constraint).

Everything here is pure-Python (onnx + numpy) and writes byte-stable files, so
the committed fixtures are reproducible: re-running on any machine yields the
same bytes for the ONNX + JSON. The TE lookups carry two real entity rows + a
prior so the Java feature pipeline has something to resolve; the park + pitch
mappings carry a couple of entries each.
"""

from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper

from bullpen_training.features import LABEL_CLASSES
from bullpen_training.pitch import (
    PITCH_FEATURE_COLUMNS,
    PITCH_FEATURE_COLUMNS_POST,
)

ONNX_INPUT_NAME = "input"
ONNX_OUTPUT_NAME = "probabilities"

_N_FEATURES_PRE = len(PITCH_FEATURE_COLUMNS)  # 31
_N_FEATURES_POST = len(PITCH_FEATURE_COLUMNS_POST)  # 41
_N_CLASSES = len(LABEL_CLASSES)  # 5


def n_features_for(head: str) -> int:
    if head == "pitch_outcome_post":
        return _N_FEATURES_POST
    return _N_FEATURES_PRE


def build_onnx(head: str, dst: Path) -> Path:
    """Write a deterministic [N, n_features] -> [N, n_classes] ONNX to ``dst``.

    Slices the first ``n_classes`` input features (Gather, axis 1) and applies
    Softmax across the class axis - identical shape contract to the backend
    pitch fixture, so the register->serve probe is deterministic.
    """
    n_features = n_features_for(head)
    indices = numpy_helper.from_array(
        np.arange(_N_CLASSES, dtype=np.int64), name="class_slice_indices"
    )
    gather = helper.make_node(
        "Gather",
        inputs=[ONNX_INPUT_NAME, "class_slice_indices"],
        outputs=["sliced"],
        axis=1,
    )
    softmax = helper.make_node(
        "Softmax",
        inputs=["sliced"],
        outputs=[ONNX_OUTPUT_NAME],
        axis=1,
    )
    graph = helper.make_graph(
        nodes=[gather, softmax],
        name=f"{head}_register_fixture",
        inputs=[
            helper.make_tensor_value_info(ONNX_INPUT_NAME, TensorProto.FLOAT, ["N", n_features])
        ],
        outputs=[
            helper.make_tensor_value_info(ONNX_OUTPUT_NAME, TensorProto.FLOAT, ["N", _N_CLASSES])
        ],
        initializer=[indices],
    )
    model = helper.make_model(graph, opset_imports=[helper.make_operatorsetid("", 13)])
    model.ir_version = 9  # ORT-Java bundled runtime targets IR <= 9
    onnx.checker.check_model(model)
    dst.parent.mkdir(parents=True, exist_ok=True)
    onnx.save(model, str(dst))
    return dst


def identity_calibrator_json() -> dict[str, object]:
    """Per-class identity isotonic (y = x on [0,1]) - same shape the Java
    IsotonicCalibratorJava reads, matching the backend fixture's calibrator."""
    return {
        "class_labels": list(LABEL_CLASSES),
        "breakpoints": [
            {
                "class": label,
                "x_thresholds": [0.0, 1.0],
                "y_thresholds": [0.0, 1.0],
            }
            for label in LABEL_CLASSES
        ],
    }


def write_identity_calibrator(dst: Path) -> Path:
    dst.parent.mkdir(parents=True, exist_ok=True)
    dst.write_text(json.dumps(identity_calibrator_json(), indent=2) + "\n")
    return dst


def _te_lookup(entity_col: str) -> dict[str, object]:
    """Two real entity rows + a prior, matching FeaturePipelinePitchPre.loadTeLookup
    (entity_col / prior{5 classes} / rows[{entity_col, te_*}])."""
    return {
        "entity_col": entity_col,
        "prior": {
            "ball": 0.36,
            "called_strike": 0.17,
            "swinging_strike": 0.11,
            "foul": 0.17,
            "in_play": 0.19,
        },
        "smoothing_k": 20.0,
        "rows": [
            {
                entity_col: 545361,
                "te_ball": 0.33,
                "te_called_strike": 0.19,
                "te_swinging_strike": 0.12,
                "te_foul": 0.18,
                "te_in_play": 0.18,
            },
            {
                entity_col: 605141,
                "te_ball": 0.30,
                "te_called_strike": 0.21,
                "te_swinging_strike": 0.10,
                "te_foul": 0.20,
                "te_in_play": 0.19,
            },
        ],
    }


def write_pitcher_te(dst: Path) -> Path:
    dst.parent.mkdir(parents=True, exist_ok=True)
    dst.write_text(json.dumps(_te_lookup("pitcher_id"), indent=2) + "\n")
    return dst


def write_batter_te(dst: Path) -> Path:
    dst.parent.mkdir(parents=True, exist_ok=True)
    dst.write_text(json.dumps(_te_lookup("batter_id"), indent=2) + "\n")
    return dst


def write_park_id_mapping(dst: Path) -> Path:
    payload = {
        "park_id": {"NYY": 0, "BOS": 1, "LAD": 2},
        "missing_value": -1,
    }
    dst.parent.mkdir(parents=True, exist_ok=True)
    dst.write_text(json.dumps(payload, indent=2) + "\n")
    return dst


def write_pitch_type_mapping(dst: Path) -> Path:
    payload = {
        "pitch_type": {"FF": 0, "SL": 1, "CH": 2, "CU": 3},
        "missing_value": -1,
    }
    dst.parent.mkdir(parents=True, exist_ok=True)
    dst.write_text(json.dumps(payload, indent=2) + "\n")
    return dst


def parity_case(head: str) -> dict[str, object]:
    """A deterministic input vector + the expected raw ONNX distribution.

    The fixture ONNX softmaxes the first 5 feature slots; we set those to a
    known ramp so the expected distribution is computable in the test (and in
    Java) without rerunning the model. The remaining slots are 0.0.
    """
    n_features = n_features_for(head)
    vec = np.zeros(n_features, dtype=np.float32)
    ramp = np.array([0.0, 0.5, 1.0, 1.5, 2.0], dtype=np.float32)
    vec[:_N_CLASSES] = ramp
    expo = np.exp(ramp - ramp.max())
    expected = expo / expo.sum()
    return {
        "head": head,
        "feature_vector": [float(x) for x in vec],
        "expected_probabilities": {
            label: float(p) for label, p in zip(LABEL_CLASSES, expected, strict=True)
        },
    }


def write_parity_case(head: str, dst: Path) -> Path:
    dst.parent.mkdir(parents=True, exist_ok=True)
    dst.write_text(json.dumps(parity_case(head), indent=2) + "\n")
    return dst


def build_all(head: str, fixtures_dir: Path) -> dict[str, Path]:
    """Build the full fixture set for a head into ``fixtures_dir``.

    Returns a map of logical-name -> path for the snapshot driver to consume.
    """
    suffix = "post" if head == "pitch_outcome_post" else "pre"
    out: dict[str, Path] = {}
    out["onnx"] = build_onnx(head, fixtures_dir / f"pitch_{suffix}_fixture.onnx")
    out["calibrator"] = write_identity_calibrator(fixtures_dir / f"pitch_{suffix}_calibrator.json")
    out["pitcher_te"] = write_pitcher_te(fixtures_dir / "pitch_pitcher_te.json")
    out["batter_te"] = write_batter_te(fixtures_dir / "pitch_batter_te.json")
    out["park_id_mapping"] = write_park_id_mapping(fixtures_dir / "pitch_park_id_mapping.json")
    out["parity"] = write_parity_case(head, fixtures_dir / f"pitch_{suffix}_parity.json")
    if head == "pitch_outcome_post":
        out["pitch_type_mapping"] = write_pitch_type_mapping(
            fixtures_dir / "pitch_pitch_type_mapping.json"
        )
    return out


__all__ = (
    "ONNX_INPUT_NAME",
    "ONNX_OUTPUT_NAME",
    "build_all",
    "build_onnx",
    "identity_calibrator_json",
    "n_features_for",
    "parity_case",
    "write_batter_te",
    "write_identity_calibrator",
    "write_parity_case",
    "write_park_id_mapping",
    "write_pitch_type_mapping",
    "write_pitcher_te",
)
