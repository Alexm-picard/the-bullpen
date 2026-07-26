"""Pre-registration dry-run gate for the pitch-TYPE family (decisions [183], [184]).

Every check here is a HARD EXIT: if it raises, the snapshot must not be registered. The point
is to fail on the Mac in seconds rather than on the box after a multi-hour training run, or -
worse - to register something that only misbehaves once it is serving.

DECISION [184] IS THE REASON THIS FILE EXISTS. [184] put ``model_kind`` in the artifact's
``metadata.json`` rather than in a ``model_versions`` column, and said in its own text that the
trade is that "a metadata-only field is a convention unless enforced, so the gate is
load-bearing: the registration path must hard-fail a model whose metadata.json lacks a valid
model_kind". Until this gate runs, [184] is an unenforced convention. :func:`check_model_kind`
is that clause.

The checks, in order:
  1. metadata.json + feature_pipeline.json present
  2. model_kind present and == "pitch_type"                      <- decision [184]
  3. model_name is a known pitch-type family member
  4. rule 9: a primary head declares its co-registered baseline
  5. contract schema_hash RECOMPUTED from content, not trusted   <- rule 7
  6. that hash EQUALS the canonical /contracts pipeline's        <- rule 7
  7. metadata's feature_pipeline_hash agrees with the contract
  8. contract feature_order == PITCH_TYPE_FEATURE_COLUMNS
  9. contract output.labels == the canonical y7 class order
 10. contract-declared side-car lookups all present
 11. calibrator loads, is kind=temperature, T finite and > 0, labels == contract labels
 12. model_artifact names the file the registry stores (model.onnx) and its sha256 matches
 13. ONNX loads and scores [1,24] -> [1,7], exactly 2 outputs, probabilities at index 1
 14. two DISTINCT probes give different outputs (a constant graph is not a model)
 15. the raw probe output is already a probability distribution

Checks 5 and 6 look redundant and are not. 5 proves the snapshot's contract is internally
consistent; 6 proves it still matches production. A snapshot that drifted from /contracts but
re-stamped its own hash passes 5 and fails 6 - and without 6 it would sail through here and
then 422 FeatureSchemaMismatch at Java bootstrap registration, which is the box-side failure
this whole file exists to pre-empt.
"""

from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, cast

import numpy as np

from bullpen_training.pitch_type import PITCH_TYPE_CLASSES, PITCH_TYPE_FEATURE_COLUMNS
from bullpen_training.pitch_type.contract import (
    CONTRACT_PATH,
    assert_feature_order_matches,
    declared_lookup_paths,
    load_canonical_pipeline,
)
from bullpen_training.pitch_type.export_lr_onnx import nullable_column_indices
from bullpen_training.registry_client import feature_hasher

METADATA_FILE = "metadata.json"
FEATURE_PIPELINE_FILE = "feature_pipeline.json"
ARTIFACT_FILE = "model.onnx"
CALIBRATOR_FILE = "calibrator.json"

PRIMARY_MODEL = "pitch_type_pre"
BASELINE_MODEL = "pitch_type_lr_baseline"
FAMILY = frozenset({PRIMARY_MODEL, BASELINE_MODEL})

MODEL_KIND = "pitch_type"
"""Decision [184]'s declared kind. MUST equal ModelLoadValidator.PITCH_TYPE_KIND on the Java
side and the value pitch_type.persist writes - there is no shared source across the boundary,
so a rename on one side silently sends every pitch-type model into the batted-ball loader."""

# The raw ONNX output must already be a distribution. Temperature calibration renormalises, so
# a graph exported without its final softmax is INVISIBLE after calibration - the same reason
# ModelLoadValidator asserts pre-calibration.
_PROB_SUM_TOLERANCE = 1e-4


class RegisterGateError(RuntimeError):
    """A hard-exit check failed; the snapshot must NOT be registered."""


@dataclass
class GateReport:
    model_name: str
    snapshot_dir: Path
    ok: bool
    schema_hash: str
    model_kind: str
    n_features: int
    n_classes: int
    checks_passed: list[str] = field(default_factory=list)


def check_model_kind(metadata: dict[str, Any]) -> str:
    """Decision [184]'s enforcement clause.

    [184] chose artifact metadata over a registry column precisely because the bundle should
    carry its own shape - and accepted that the choice is only sound if registration refuses a
    bundle that omits it. A missing or wrong ``model_kind`` means the Java load gate resolves
    the batted-ball loader for a 24-feature model and 422s every promotion, so catching it here
    is the difference between a clear message now and a confusing failure on the box.
    """
    kind = metadata.get("model_kind")
    if kind is None:
        raise RegisterGateError(
            f"decision [184]: metadata.json has no model_kind. The Java load gate resolves the "
            f"loader from this field; without it this model routes to the batted-ball loader and "
            f"every promotion 422s. Expected {MODEL_KIND!r} - re-run pitch_type.persist, or you "
            f"are registering from raw training artifacts rather than the persisted bundle."
        )
    if kind != MODEL_KIND:
        raise RegisterGateError(
            f"decision [184]: metadata.json declares model_kind={kind!r}, expected {MODEL_KIND!r}"
        )
    return cast(str, kind)


def _check_calibrator(
    snapshot_dir: Path, contract_labels: list[str], metadata: dict[str, Any]
) -> None:
    # Resolve the SAME way LoadedPitchTypeModel does - metadata's calibrator.path pointer first,
    # canonical filename as fallback. Reading calibrator.json unconditionally would validate a
    # file the loader never opens, so a bundle whose pointer aims at a broken calibrator would
    # pass the gate and fail at load.
    cal_path = snapshot_dir / CALIBRATOR_FILE
    pointer = (metadata.get("calibrator") or {}).get("path")
    if isinstance(pointer, str) and pointer.strip():
        candidate = (snapshot_dir / pointer).resolve()
        if candidate.is_file():
            cal_path = candidate
    if not cal_path.is_file():
        raise RegisterGateError(f"calibrator file missing (expected {cal_path})")
    cal = cast(dict[str, Any], json.loads(cal_path.read_text()))
    kind = cal.get("kind")
    if kind != "temperature":
        # A pitch-OUTCOME isotonic calibrator has the SAME filename and would silently
        # mis-calibrate rather than fail.
        raise RegisterGateError(
            f"calibrator kind must be 'temperature' for the pitch-type family, got {kind!r}"
        )
    t = cal.get("temperature")
    if not isinstance(t, int | float) or not np.isfinite(float(t)) or float(t) <= 0.0:
        raise RegisterGateError(
            f"calibrator temperature must be finite and > 0 (the order-preservation invariant "
            f"decision [183] rests on), got {t!r}"
        )
    if list(cal.get("class_labels") or []) != contract_labels:
        raise RegisterGateError(
            f"calibrator class_labels {cal.get('class_labels')} != contract labels "
            f"{contract_labels}; calibrated probabilities would be packed under the wrong labels"
        )


def _probe_onnx(snapshot_dir: Path, n_features: int, n_classes: int) -> None:
    import onnxruntime as ort

    onnx_path = snapshot_dir / ARTIFACT_FILE
    if not onnx_path.is_file():
        raise RegisterGateError(
            f"missing {ARTIFACT_FILE} in {snapshot_dir}; the registry serves every model through "
            "ONNX Runtime, so a bundle with only model.pkl / model.lgb cannot be registered"
        )
    try:
        session = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])
    except Exception as exc:  # any ORT load failure is a hard exit
        raise RegisterGateError(f"ONNX runtime cannot load {onnx_path}: {exc}") from exc

    input_names = [i.name for i in session.get_inputs()]
    if len(input_names) != 1:
        raise RegisterGateError(
            f"pitch-type ONNX must declare exactly one input tensor, got {input_names}"
        )
    outputs = session.get_outputs()
    if len(outputs) != 2:
        # Both exports are zipmap=False -> (label, probabilities), and the Java reader takes
        # index 1. A different arity means the reader would grab the wrong tensor.
        raise RegisterGateError(
            f"pitch-type ONNX must emit exactly 2 outputs (label, probabilities) so the "
            f"contract's onnx_output_index=1 is the probability tensor; got "
            f"{[o.name for o in outputs]}"
        )

    declared_width = session.get_inputs()[0].shape
    if (
        len(declared_width) == 2
        and isinstance(declared_width[1], int)
        and (declared_width[1] != n_features)
    ):
        raise RegisterGateError(
            f"ONNX declares input width {declared_width[1]}, contract has {n_features} features"
        )
    # Two DISTINCT probes: one row can never distinguish a correct softmax from a graph that
    # ignores its input, and a NaN cold-start row is the only input exercising the LR baseline's
    # in-graph Imputer (the same pair ModelLoadValidator runs, for the same reason).
    dense = np.full((1, n_features), 0.37, dtype=np.float32)
    cold = np.zeros((1, n_features), dtype=np.float32)
    cold[0, list(nullable_column_indices())] = np.nan
    rows: list[np.ndarray] = []
    for probe in (dense, cold):
        try:
            out = session.run(None, {input_names[0]: probe})
        except Exception as exc:
            raise RegisterGateError(f"ONNX failed to score a probe row: {exc}") from exc
        rows.append(np.asarray(out[-1]))
    prob = rows[0]
    if prob.ndim != 2 or prob.shape[1] != n_classes:
        raise RegisterGateError(
            f"ONNX probability output must be [N,{n_classes}], got shape {prob.shape}"
        )
    if len(rows) == 2 and np.allclose(
        np.asarray(rows[0], dtype=np.float64), np.asarray(rows[1], dtype=np.float64)
    ):
        raise RegisterGateError(
            "ONNX returned an IDENTICAL distribution for two very different inputs, so the graph "
            "is ignoring its input. A constant output still looks like a valid distribution, "
            "which is why one probe row cannot catch this"
        )
    row = prob[0].astype(np.float64)
    if not np.all(np.isfinite(row)) or float(row.min()) < 0.0 or float(row.max()) > 1.0:
        raise RegisterGateError(
            f"ONNX raw output is not a probability distribution: {row.tolist()} - a graph "
            "exported without its final softmax looks exactly like this, and temperature "
            "calibration would renormalise it into something plausible downstream"
        )
    if abs(float(row.sum()) - 1.0) > _PROB_SUM_TOLERANCE:
        raise RegisterGateError(
            f"ONNX raw output does not sum to 1 (got {float(row.sum())}); the graph is not "
            "emitting a calibrated-ready distribution"
        )


def run_gate(
    snapshot_dir: Path, *, model_name: str, baseline_registered: bool = False
) -> GateReport:
    """Run every hard-exit check against ``snapshot_dir``. Raises :class:`RegisterGateError`."""
    snapshot_dir = Path(snapshot_dir)
    passed: list[str] = []

    if model_name not in FAMILY:
        raise RegisterGateError(
            f"{model_name!r} is not a pitch-type model; expected one of {sorted(FAMILY)}"
        )

    meta_path = snapshot_dir / METADATA_FILE
    fp_path = snapshot_dir / FEATURE_PIPELINE_FILE
    if not meta_path.is_file():
        raise RegisterGateError(f"missing {METADATA_FILE} in {snapshot_dir}")
    if not fp_path.is_file():
        raise RegisterGateError(f"missing {FEATURE_PIPELINE_FILE} in {snapshot_dir}")
    metadata = cast(dict[str, Any], json.loads(meta_path.read_text()))
    passed.append("metadata.json + feature_pipeline.json present")

    kind = check_model_kind(metadata)
    passed.append(f"decision [184]: model_kind={kind}")

    if metadata.get("model_name") != model_name:
        raise RegisterGateError(
            f"metadata model_name={metadata.get('model_name')!r} != registering {model_name!r}"
        )
    passed.append("metadata model_name matches")

    # Rule 9 first: it costs nothing (a caller-supplied flag, no I/O), so a sequencing mistake
    # should surface before the ONNX load rather than after it.
    if model_name == PRIMARY_MODEL:
        if not baseline_registered:
            raise RegisterGateError(
                f"rule 9: primary head {model_name} has no {BASELINE_MODEL} registered; decision "
                "[183]'s guardrail compares them and the first-champion gate binds to it"
            )
        passed.append(f"rule 9: {BASELINE_MODEL} is registered alongside this primary head")

    # Rule 7: recompute from the SNAPSHOT's own copied contract, never trust its declared field.
    # Wrapped so EVERY hard exit from this gate is a RegisterGateError - the contract helpers
    # raise bare RuntimeError, and a caller catching only RegisterGateError would otherwise get
    # an unhandled traceback for a check the gate is supposed to own.
    try:
        spec = load_canonical_pipeline(fp_path)
        assert_feature_order_matches(spec)
    except RegisterGateError:
        raise
    except RuntimeError as exc:
        raise RegisterGateError(str(exc)) from exc
    declared = cast(str, spec["schema_hash"])
    passed.append("rule 7: snapshot contract schema_hash recomputed from its own content")

    # The check above only proves the snapshot's contract is INTERNALLY consistent. Rule 7 is
    # about agreement with the CANONICAL pipeline: a snapshot that drifted from /contracts but
    # re-stamped its own hash is self-consistent and would sail through - then hard-fail at
    # Java bootstrap registration, which compares the submitted file against
    # CanonicalContracts.canonicalHashFor. That box-side 422 is exactly what this gate exists
    # to pre-empt, so compare here too.
    canonical = feature_hasher.compute(CONTRACT_PATH)
    if declared != canonical:
        raise RegisterGateError(
            f"rule 7: the snapshot's feature_pipeline.json has DRIFTED from the canonical "
            f"{CONTRACT_PATH.name} (snapshot={declared} canonical={canonical}). It is "
            f"self-consistent, so only this comparison catches it; registration would 422 with "
            f"FeatureSchemaMismatch on the box. Re-persist from the current contract."
        )
    passed.append("rule 7: snapshot contract matches the canonical /contracts pipeline")
    passed.append("contract feature_order == PITCH_TYPE_FEATURE_COLUMNS")

    # The input axis is guarded by feature_order; this guards the OUTPUT axis. Permuting the
    # contract labels AND the calibrator labels together is self-consistent (Java compares those
    # two to each other), but the ONNX columns are in PITCH_TYPE_CLASSES order - so the bundle
    # would serve a distribution with its labels silently backwards.
    contract_labels = [str(x) for x in spec["output"]["labels"]]
    if contract_labels != list(PITCH_TYPE_CLASSES):
        raise RegisterGateError(
            f"contract output.labels {contract_labels} != the canonical y7 order "
            f"{list(PITCH_TYPE_CLASSES)}; the ONNX columns follow the canonical order, so this "
            "bundle would serve correctly-shaped probabilities under the wrong labels"
        )
    passed.append("contract output.labels are the canonical y7 order")

    meta_hash = metadata.get("feature_pipeline_hash")
    if meta_hash != declared:
        raise RegisterGateError(
            f"metadata feature_pipeline_hash={meta_hash!r} != the snapshot contract's {declared!r}"
        )
    passed.append("metadata feature_pipeline_hash agrees with the contract")

    missing = [n for n in declared_lookup_paths(spec) if not (snapshot_dir / n).is_file()]
    if missing:
        raise RegisterGateError(
            f"bundle is missing contract-declared lookup file(s): {', '.join(missing)}"
        )
    passed.append("contract-declared lookups present")

    _check_calibrator(snapshot_dir, contract_labels, metadata)
    passed.append("calibrator is a valid temperature calibrator with matching labels")

    artifact = metadata.get("model_artifact") or {}
    if artifact.get("path") != ARTIFACT_FILE:
        raise RegisterGateError(
            f"metadata model_artifact.path={artifact.get('path')!r} but the registry stores and "
            f"serves {ARTIFACT_FILE!r}; run the ONNX export, which re-stamps this"
        )
    passed.append("model_artifact names the registered ONNX")

    # Name alone approves ANY 24->7 graph dropped into the bundle. Verifying the digest catches
    # "re-exported but never re-stamped", "copied a bundle and swapped the model", and
    # "registered from a half-finished export".
    declared_sha = artifact.get("sha256")
    onnx_file = snapshot_dir / ARTIFACT_FILE
    if isinstance(declared_sha, str) and onnx_file.is_file():
        actual_sha = hashlib.sha256(onnx_file.read_bytes()).hexdigest()
        if actual_sha != declared_sha:
            raise RegisterGateError(
                f"model.onnx on disk (sha {actual_sha[:16]}) is not the file metadata records "
                f"(sha {declared_sha[:16]}); the bundle's ONNX was swapped or the export did not "
                "re-stamp"
            )
        passed.append("model_artifact sha256 matches the ONNX on disk")

    n_features = len(PITCH_TYPE_FEATURE_COLUMNS)
    n_classes = len(PITCH_TYPE_CLASSES)
    _probe_onnx(snapshot_dir, n_features, n_classes)
    passed.append(f"ONNX loads + scores [1,{n_features}] -> [1,{n_classes}] as a distribution")

    return GateReport(
        model_name=model_name,
        snapshot_dir=snapshot_dir,
        ok=True,
        schema_hash=declared,
        model_kind=kind,
        n_features=n_features,
        n_classes=n_classes,
        checks_passed=passed,
    )


__all__ = ("MODEL_KIND", "GateReport", "RegisterGateError", "check_model_kind", "run_gate")
