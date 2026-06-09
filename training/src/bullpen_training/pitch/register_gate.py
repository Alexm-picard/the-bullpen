"""register-model DRY-RUN gate for pitch snapshots (W4b).

Runs the register-model skill's HARD-EXIT checks against an assembled pitch
snapshot directory BEFORE any registry insert. This is the Python-side mirror of
the JVM registry's registration-time discipline (rule 7 schema-hash, rule 9
two-heads + baseline), so a snapshot that would be refused by the registry is
caught locally on the Mac authoring box - no box, no ClickHouse, no JVM.

Checks (each maps to a register-model skill "Hard exit"):

  1. ONNX runtime can load model.onnx                 (skill: "ONNX runtime cannot load")
  2. Calibrator file present and loads                (skill: "Calibrator file missing or fails")
  3. feature_pipeline.json schema_hash recomputes     (rule 7 self-consistency)
  4. metadata feature_pipeline_hash == contract hash  (rule 7 - HARD FAIL on mismatch)
  5. metadata carries calibrator.path -> the file     (decision [152] - LoadedPitchModel reads it)
  6. all required Tier-2/Tier-1 (and POST Tier-4) lookups present
  7. metadata feature_order == the head's in-code tuple (pre=31, post=41), and
     for the POST head, == pre order + the Tier-4 block (rule 9 structural form)
  8. rule 9: pre and post are DIFFERENT model_names; a primary head carries its
     LR baseline partner (presence asserted by the caller passing the registered
     baseline model_name, mirrored into metadata)

A clean pass returns a ``GateReport`` with ``ok=True``; any failure raises
``RegisterGateError`` with the failing check named (HARD FAIL, no partial
registration).
"""

from __future__ import annotations

import copy
import hashlib
import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import numpy as np
import onnxruntime as ort

from bullpen_training.features import LABEL_CLASSES
from bullpen_training.features.tier_4_postpitch import TIER4_COLUMNS
from bullpen_training.pitch import PITCH_FEATURE_COLUMNS
from bullpen_training.pitch.isotonic import IsotonicCalibrator
from bullpen_training.pitch.register_snapshot import (
    ARTIFACT_FILE,
    BATTER_TE_FILE,
    CALIBRATOR_FILE,
    FEATURE_PIPELINE_FILE,
    METADATA_FILE,
    PARK_ID_MAPPING_FILE,
    PITCH_TYPE_MAPPING_FILE,
    PITCHER_TE_FILE,
    contract_path_for,
    feature_columns_for,
)

# Tier-4 columns as they appear in the contract feature_order: pitch_type ->
# pitch_type_int (the integer encoding), matching the boundary leakage test.
_TIER4_CONTRACT_FEATURES: tuple[str, ...] = tuple(
    "pitch_type_int" if c == "pitch_type" else c for c in TIER4_COLUMNS
)

_POST_HEADS = frozenset({"pitch_outcome_post"})


class RegisterGateError(RuntimeError):
    """A hard-exit check failed; the snapshot must NOT be registered."""


@dataclass
class GateReport:
    head: str
    snapshot_dir: Path
    ok: bool
    schema_hash: str
    onnx_input_names: list[str]
    n_features: int
    n_classes: int
    checks_passed: list[str] = field(default_factory=list)


def _recompute_schema_hash(spec: dict[str, Any]) -> str:
    canonical = copy.deepcopy(spec)
    canonical["schema_hash"] = ""
    blob = json.dumps(canonical, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(blob).hexdigest()


def run_gate(
    snapshot_dir: Path,
    *,
    head: str,
    baseline_registered: bool,
) -> GateReport:
    """Run all hard-exit checks against ``snapshot_dir``.

    ``head`` is the registered model_name (``pitch_outcome_pre`` /
    ``pitch_outcome_post``). ``baseline_registered`` asserts the rule-9 LR
    baseline partner is (or will be) registered in the same operation; the
    caller knows this from the registry, the gate only enforces that a primary
    head declares it.
    """
    snapshot_dir = Path(snapshot_dir)
    passed: list[str] = []
    contract = contract_path_for(head)  # validates head name

    # --- metadata + contract present ----------------------------------------
    meta_path = snapshot_dir / METADATA_FILE
    fp_path = snapshot_dir / FEATURE_PIPELINE_FILE
    if not meta_path.is_file():
        raise RegisterGateError(f"missing {METADATA_FILE} in {snapshot_dir}")
    if not fp_path.is_file():
        raise RegisterGateError(f"missing {FEATURE_PIPELINE_FILE} in {snapshot_dir}")
    metadata = json.loads(meta_path.read_text())
    fp_spec = json.loads(fp_path.read_text())
    passed.append("metadata+contract present")

    # --- rule 9: model_name is this head, and pre != post -------------------
    meta_name = metadata.get("model_name")
    if meta_name != head:
        raise RegisterGateError(
            f"rule 9: metadata model_name {meta_name!r} != requested head {head!r}; "
            "pre and post must register as two separate model_names"
        )
    # The contract's model_name identifies which canonical /contracts file the snapshot
    # carries. For pre/post that equals the head; the LR baseline shares the pre contract
    # (model_name pitch_outcome_pre, decision [37]), so compare against the head's CANONICAL
    # contract model_name, not the head itself. This still rejects a wrong contract for the
    # head (e.g. the post contract under the pre head) since that mismatches the canonical too.
    expected_contract_name = json.loads(contract.read_text()).get("model_name")
    if fp_spec.get("model_name") != expected_contract_name:
        raise RegisterGateError(
            f"rule 9: contract model_name {fp_spec.get('model_name')!r} is not the "
            f"canonical contract {expected_contract_name!r} for head {head!r}"
        )
    passed.append("rule 9 head identity (pre != post)")

    # --- check 3: contract schema_hash self-consistency (rule 7) ------------
    declared = str(fp_spec["schema_hash"])
    recomputed = _recompute_schema_hash(fp_spec)
    if declared != recomputed:
        raise RegisterGateError(
            f"rule 7: {FEATURE_PIPELINE_FILE} schema_hash is stale "
            f"(declared={declared} computed={recomputed})"
        )
    passed.append("rule 7 contract schema_hash self-consistent")

    # --- check 4: snapshot contract == canonical /contracts contract --------
    canonical_spec = json.loads(contract.read_text())
    if _recompute_schema_hash(canonical_spec) != recomputed:
        raise RegisterGateError(
            "rule 7: snapshot feature_pipeline.json does not match the canonical "
            f"/contracts contract for {head}"
        )
    passed.append("rule 7 snapshot contract == canonical contract")

    # --- check 4b: metadata feature_pipeline_hash == contract hash ----------
    meta_hash = metadata.get("feature_pipeline_hash")
    if meta_hash != declared:
        raise RegisterGateError(
            f"rule 7: metadata feature_pipeline_hash {meta_hash!r} != contract "
            f"schema_hash {declared!r} - HARD FAIL"
        )
    passed.append("rule 7 metadata hash == contract hash")

    # --- check 7: feature_order == the head's in-code tuple -----------------
    expected_features = list(feature_columns_for(head))
    contract_order = list(fp_spec["feature_order"])
    if contract_order != expected_features:
        raise RegisterGateError(f"contract feature_order drifted from the in-code tuple for {head}")
    meta_order = list(metadata.get("feature_order", []))
    if meta_order != expected_features:
        raise RegisterGateError(f"metadata feature_order drifted from the in-code tuple for {head}")
    # POST structural form (rule 9): post == pre order + Tier-4 block.
    if head in _POST_HEADS:
        pre = list(PITCH_FEATURE_COLUMNS)
        if contract_order[: len(pre)] != pre:
            raise RegisterGateError(
                "rule 9: post feature_order does not start with the pre order verbatim"
            )
        tail = contract_order[len(pre) :]
        if tail != list(_TIER4_CONTRACT_FEATURES):
            raise RegisterGateError(
                "rule 9: post head's extra columns are not exactly the Tier-4 block "
                f"in order: got {tail}"
            )
    passed.append("feature_order matches in-code tuple (+ rule 9 post form)")

    # --- check 5: calibrator pointer + load ---------------------------------
    cal_rel = (metadata.get("calibrator") or {}).get("path")
    if not cal_rel:
        raise RegisterGateError(
            "metadata.calibrator.path missing - LoadedPitchModel resolves the "
            "calibrator from this pointer (decision [152])"
        )
    cal_path = (snapshot_dir / cal_rel).resolve()
    if not cal_path.is_file():
        # fall back to the canonical name the way the Java loader does
        cal_path = snapshot_dir / CALIBRATOR_FILE
    if not cal_path.is_file():
        raise RegisterGateError(f"calibrator file missing (expected {cal_path})")
    calibrator = IsotonicCalibrator.from_json(cal_path)
    if tuple(calibrator.class_labels) != tuple(LABEL_CLASSES):
        raise RegisterGateError(
            f"calibrator class_labels {calibrator.class_labels} != {LABEL_CLASSES}"
        )
    passed.append("calibrator present, loads, labels match")

    # --- check 6: required lookups present ----------------------------------
    required = [PITCHER_TE_FILE, BATTER_TE_FILE, PARK_ID_MAPPING_FILE]
    if head in _POST_HEADS:
        required.append(PITCH_TYPE_MAPPING_FILE)
    for name in required:
        if not (snapshot_dir / name).is_file():
            raise RegisterGateError(
                f"missing required Tier-2/Tier-4 lookup {name} in {snapshot_dir}; "
                "the Java feature pipeline resolves it from the snapshot dir"
            )
    passed.append("required lookups present")

    # --- check 1: ONNX loads + accepts the declared feature width -----------
    n_features = len(expected_features)
    n_classes = len(LABEL_CLASSES)
    onnx_path = snapshot_dir / ARTIFACT_FILE
    if not onnx_path.is_file():
        raise RegisterGateError(f"missing {ARTIFACT_FILE} in {snapshot_dir}")
    try:
        session = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])
    except Exception as exc:  # - any ORT load failure is a hard exit
        raise RegisterGateError(f"ONNX runtime cannot load {onnx_path}: {exc}") from exc
    input_names = [i.name for i in session.get_inputs()]
    if len(input_names) != 1:
        raise RegisterGateError(
            f"pitch ONNX must declare exactly one input tensor, got {input_names}"
        )
    # Probe inference with a deterministic zero vector of the contract width.
    probe = np.zeros((1, n_features), dtype=np.float32)
    outputs = session.run(None, {input_names[0]: probe})
    prob = outputs[-1]
    prob = np.asarray(prob)
    if prob.ndim != 2 or prob.shape[1] != n_classes:
        raise RegisterGateError(
            f"ONNX probability output must be [N,{n_classes}], got shape {prob.shape}"
        )
    passed.append(f"ONNX loads + scores [N,{n_features}]->[N,{n_classes}]")

    # --- check 8: rule 9 baseline partner -----------------------------------
    is_primary = head in ("pitch_outcome_pre", "pitch_outcome_post")
    if is_primary:
        if not baseline_registered:
            raise RegisterGateError(
                f"rule 9: primary head {head} has no LR baseline registered; "
                "register pitch_outcome_lr_baseline in the same operation"
            )
        if not metadata.get("baseline_model_name"):
            raise RegisterGateError(
                "rule 9: metadata.baseline_model_name missing for a primary head"
            )
    passed.append("rule 9 baseline partner present")

    return GateReport(
        head=head,
        snapshot_dir=snapshot_dir,
        ok=True,
        schema_hash=declared,
        onnx_input_names=input_names,
        n_features=n_features,
        n_classes=n_classes,
        checks_passed=passed,
    )


__all__ = ("GateReport", "RegisterGateError", "run_gate")
