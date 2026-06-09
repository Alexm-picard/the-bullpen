"""W4b - pitch registration scaffolding: register->serve fixtures + dry-run gate.

Exercises the Mac-doable register->serve path WITHOUT the box:

  1. Build the tiny pre + post fixtures (deterministic ONNX + lookups + identity
     calibrator + a parity case).
  2. Assemble a canonical snapshot for pitch_outcome_pre and pitch_outcome_post
     in the EXACT filename layout the Java SnapshotStorage + LoadedPitchModel
     read.
  3. Run the register-model DRY-RUN gate and prove it (a) PASSES on a complete
     snapshot and (b) HARD-FAILS on a deliberately schema-mutated contract.
  4. Prove rule-9 discipline (pre != post; primary head needs its LR baseline)
     and that the post snapshot's feature_order is pre + the Tier-4 block (the
     same structural form the boundary leakage test pins).
"""

from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import onnxruntime as ort
import pandas as pd
import pytest

from bullpen_training.features import LABEL_CLASSES
from bullpen_training.pitch import (
    PITCH_FEATURE_COLUMNS,
    PITCH_FEATURE_COLUMNS_POST,
)
from bullpen_training.pitch import register_fixtures as fx
from bullpen_training.pitch.register_gate import RegisterGateError, run_gate
from bullpen_training.pitch.register_snapshot import (
    ARTIFACT_FILE,
    BATTER_TE_FILE,
    CALIBRATOR_FILE,
    FEATURE_PIPELINE_FILE,
    METADATA_FILE,
    PARK_ID_MAPPING_FILE,
    PITCH_TYPE_MAPPING_FILE,
    PITCHER_TE_FILE,
    TRAINING_DATA_FILE,
    PitchSnapshotInputs,
    feature_columns_for,
    write_snapshot,
)

_PRE_FILES = {
    ARTIFACT_FILE,
    METADATA_FILE,
    FEATURE_PIPELINE_FILE,
    CALIBRATOR_FILE,
    TRAINING_DATA_FILE,
    PITCHER_TE_FILE,
    BATTER_TE_FILE,
    PARK_ID_MAPPING_FILE,
}
_POST_FILES = _PRE_FILES | {PITCH_TYPE_MAPPING_FILE}


def _tiny_training_df(head: str) -> pd.DataFrame:
    cols = list(feature_columns_for(head))
    data: dict[str, np.ndarray] = {c: np.zeros(3, dtype=np.float32) for c in cols}
    data["label"] = np.array([0, 1, 2], dtype=np.int64)
    return pd.DataFrame(data)


def _assemble(head: str, fixtures_dir: Path, out_dir: Path) -> Path:
    built = fx.build_all(head, fixtures_dir)
    inputs = PitchSnapshotInputs(
        head=head,
        version="v_fixture",
        onnx_path=built["onnx"],
        calibrator_path=built["calibrator"],
        pitcher_te_path=built["pitcher_te"],
        batter_te_path=built["batter_te"],
        park_id_mapping_path=built["park_id_mapping"],
        pitch_type_mapping_path=built.get("pitch_type_mapping"),
        training_df=_tiny_training_df(head),
        baseline_model_name="pitch_outcome_lr_baseline",
        experiment_results_id=1,
    )
    return write_snapshot(inputs, out_dir)


# --- snapshot shape -------------------------------------------------------


@pytest.mark.parametrize(
    ("head", "expected_files"),
    [("pitch_outcome_pre", _PRE_FILES), ("pitch_outcome_post", _POST_FILES)],
)
def test_snapshot_emits_exact_canonical_filename_set(
    head: str, expected_files: set[str], tmp_path: Path
) -> None:
    out = _assemble(head, tmp_path / "fx", tmp_path / "snap")
    on_disk = {p.name for p in out.iterdir() if p.is_file()}
    assert on_disk == expected_files, (
        f"{head} snapshot filename set drifted: extra={on_disk - expected_files} "
        f"missing={expected_files - on_disk}"
    )


def test_metadata_carries_calibrator_path_and_hash(tmp_path: Path) -> None:
    out = _assemble("pitch_outcome_pre", tmp_path / "fx", tmp_path / "snap")
    meta = json.loads((out / METADATA_FILE).read_text())
    # decision [152]: LoadedPitchModel reads calibrator.path off metadata.
    assert meta["calibrator"]["path"] == CALIBRATOR_FILE
    # rule 7: metadata hash == the contract's schema_hash.
    contract = json.loads((out / FEATURE_PIPELINE_FILE).read_text())
    assert meta["feature_pipeline_hash"] == contract["schema_hash"]
    assert meta["model_name"] == "pitch_outcome_pre"


def test_post_feature_order_is_pre_plus_tier4(tmp_path: Path) -> None:
    out = _assemble("pitch_outcome_post", tmp_path / "fx", tmp_path / "snap")
    meta = json.loads((out / METADATA_FILE).read_text())
    order = meta["feature_order"]
    assert order[: len(PITCH_FEATURE_COLUMNS)] == list(PITCH_FEATURE_COLUMNS)
    assert order == list(PITCH_FEATURE_COLUMNS_POST)


# --- the snapshot actually serves (register->serve, name-agnostic ONNX) ---


@pytest.mark.parametrize("head", ["pitch_outcome_pre", "pitch_outcome_post"])
def test_fixture_onnx_serves_the_parity_case(head: str, tmp_path: Path) -> None:
    out = _assemble(head, tmp_path / "fx", tmp_path / "snap")
    parity = json.loads((tmp_path / "fx" / _parity_name(head)).read_text())
    session = ort.InferenceSession(str(out / ARTIFACT_FILE), providers=["CPUExecutionProvider"])
    name = session.get_inputs()[0].name  # resolved, not hardcoded
    vec = np.asarray([parity["feature_vector"]], dtype=np.float32)
    prob_tensor = np.asarray(session.run(None, {name: vec})[-1])
    probs = prob_tensor[0]
    got = {label: float(p) for label, p in zip(LABEL_CLASSES, probs, strict=True)}
    for label, expected in parity["expected_probabilities"].items():
        assert got[label] == pytest.approx(expected, abs=1e-6)


def _parity_name(head: str) -> str:
    return "pitch_post_parity.json" if head == "pitch_outcome_post" else "pitch_pre_parity.json"


# --- the register-model DRY-RUN gate: PASS + HARD-FAIL --------------------


@pytest.mark.parametrize("head", ["pitch_outcome_pre", "pitch_outcome_post"])
def test_gate_passes_on_complete_snapshot(head: str, tmp_path: Path) -> None:
    out = _assemble(head, tmp_path / "fx", tmp_path / "snap")
    report = run_gate(out, head=head, baseline_registered=True)
    assert report.ok
    assert report.n_classes == len(LABEL_CLASSES)
    assert report.n_features == len(feature_columns_for(head))
    assert len(report.onnx_input_names) == 1


def test_gate_hard_fails_on_schema_mutated_contract(tmp_path: Path) -> None:
    """Mutate the snapshot contract's feature_order so its content no longer
    matches the declared schema_hash - the rule-7 gate must HARD-FAIL."""
    out = _assemble("pitch_outcome_pre", tmp_path / "fx", tmp_path / "snap")
    contract_path = out / FEATURE_PIPELINE_FILE
    spec = json.loads(contract_path.read_text())
    # Drop a feature - schema content now disagrees with the declared hash.
    spec["feature_order"] = spec["feature_order"][:-1]
    contract_path.write_text(json.dumps(spec, indent=2) + "\n")

    with pytest.raises(RegisterGateError) as exc:
        run_gate(out, head="pitch_outcome_pre", baseline_registered=True)
    assert "schema_hash" in str(exc.value) or "feature_order" in str(exc.value)


def test_gate_hard_fails_when_metadata_hash_mismatches(tmp_path: Path) -> None:
    out = _assemble("pitch_outcome_pre", tmp_path / "fx", tmp_path / "snap")
    meta_path = out / METADATA_FILE
    meta = json.loads(meta_path.read_text())
    meta["feature_pipeline_hash"] = "deadbeef" * 8
    meta_path.write_text(json.dumps(meta, indent=2) + "\n")
    with pytest.raises(RegisterGateError, match="feature_pipeline_hash"):
        run_gate(out, head="pitch_outcome_pre", baseline_registered=True)


def test_gate_hard_fails_when_calibrator_missing(tmp_path: Path) -> None:
    out = _assemble("pitch_outcome_pre", tmp_path / "fx", tmp_path / "snap")
    (out / CALIBRATOR_FILE).unlink()
    with pytest.raises(RegisterGateError, match="calibrator"):
        run_gate(out, head="pitch_outcome_pre", baseline_registered=True)


def test_gate_hard_fails_when_post_pitch_type_mapping_missing(tmp_path: Path) -> None:
    out = _assemble("pitch_outcome_post", tmp_path / "fx", tmp_path / "snap")
    (out / PITCH_TYPE_MAPPING_FILE).unlink()
    with pytest.raises(RegisterGateError, match="pitch_type_mapping"):
        run_gate(out, head="pitch_outcome_post", baseline_registered=True)


def test_gate_hard_fails_when_baseline_absent_rule9(tmp_path: Path) -> None:
    out = _assemble("pitch_outcome_pre", tmp_path / "fx", tmp_path / "snap")
    with pytest.raises(RegisterGateError, match="baseline"):
        run_gate(out, head="pitch_outcome_pre", baseline_registered=False)


def test_gate_hard_fails_when_head_name_mismatch_rule9(tmp_path: Path) -> None:
    """A pre snapshot must not register under the post model_name (rule 9:
    two heads = two separate models, never one masked model)."""
    out = _assemble("pitch_outcome_pre", tmp_path / "fx", tmp_path / "snap")
    with pytest.raises(RegisterGateError, match="rule 9"):
        run_gate(out, head="pitch_outcome_post", baseline_registered=True)
