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
  9. canonical contract output.labels == the in-code y7 order (see note below)
 10. contract-declared side-car lookups all present
 11. calibrator loads, is kind=temperature, T finite and > 0, labels == contract labels
 12. model_artifact names the file the registry stores (model.onnx); model_artifact AND
     calibrator both declare a sha256 that matches the bytes on disk (REQUIRED, not
     verified-if-present: a conditional digest check is bypassed by omitting the field)
 13. ONNX loads and scores [1,24] -> [1,7], exactly 2 outputs, probabilities at index 1
 14. two DISTINCT probes give different outputs (a constant graph is not a model)
 15. the raw probe output is already a probability distribution

Checks 5 and 6 look redundant and are not. 5 proves the snapshot's contract is internally
consistent; 6 proves it still matches production. A snapshot that drifted from /contracts but
re-stamped its own hash passes 5 and fails 6 - and without 6 it would sail through here and
then 422 FeatureSchemaMismatch at Java bootstrap registration, which is the box-side failure
this whole file exists to pre-empt.

Check 9 is NOT a snapshot-drift check and cannot be reached by mutating a snapshot: output.labels
lives inside the hashed document, so a permuted snapshot fails check 5 (or check 6, if its hash
was re-stamped) first. It reads the snapshot's copy of the file, which check 6 has already proved
byte-identical to canonical, so this is a canonical read by transitivity - a server-side twin
running the checks in a different order could not rely on that. Like
``assert_feature_order_matches`` on the input axis, it guards the CANONICAL contract against the
in-code tuple - it fires when /contracts and PITCH_TYPE_CLASSES are edited out of agreement.
"""

from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, cast

import numpy as np

from bullpen_training.pitch_type import (
    PITCH_TYPE_CLASSES,
    PITCH_TYPE_FEATURE_COLUMNS,
    nullable_column_indices,
)
from bullpen_training.pitch_type.contract import (
    CONTRACT_PATH,
    assert_feature_order_matches,
    declared_lookup_paths,
    load_canonical_pipeline,
)
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


def _load_json(path: Path, label: str) -> Any:
    """Parse JSON, turning every corrupt-file failure into a RegisterGateError.

    Truncated, binary, or array-shaped files are half-finished-export and interrupted-sync
    shapes - the exact cases this gate exists to catch - so they must surface as a gate refusal
    with a message, not as a raw JSONDecodeError/UnicodeDecodeError traceback.
    """
    try:
        loaded = json.loads(path.read_text())
    except (ValueError, OSError, UnicodeDecodeError) as exc:
        raise RegisterGateError(f"{label} is unreadable or malformed: {exc}") from exc
    if not isinstance(loaded, dict):
        raise RegisterGateError(f"{label} must be a JSON object, got {type(loaded).__name__}")
    return loaded


def _calibrator_path(snapshot_dir: Path, metadata: dict[str, Any]) -> Path:
    """Resolve the calibrator the SAME way LoadedPitchTypeModel does.

    metadata's ``calibrator.path`` pointer wins, canonical filename is the fallback. Reading
    calibrator.json unconditionally would validate a file the loader never opens, so a bundle
    whose pointer aims at a broken calibrator would pass the gate and fail at load.
    """
    cal = metadata.get("calibrator")
    if not isinstance(cal, dict):
        # Java would fall back cleanly here (.path() yields a MissingNode). The gate is
        # deliberately STRICTER: a calibrator entry that is not an object cannot carry the
        # digest this gate requires, so approving it means approving an unverified calibrator.
        raise RegisterGateError("metadata.calibrator is missing or not an object")
    pointer = cal.get("path")
    if not (isinstance(pointer, str) and pointer.strip()):
        return snapshot_dir / CALIBRATOR_FILE

    # A DECLARED pointer must resolve to a regular file INSIDE the bundle, and failing that is a
    # hard exit rather than a fallback. Silently falling back approves the wrong file twice over:
    # a bundle can ship one calibrator, point metadata at another (with a matching digest), and
    # have the gate validate the pointed-to file while the box loads the shipped one - so the
    # SERVED temperature is one nothing ever checked. Containment matters most for the
    # server-side twin, where this pointer arrives in a registration payload: without it, "../"
    # or an absolute path is a traversal primitive that makes the server hash and parse an
    # operator-chosen file outside the snapshot.
    root = snapshot_dir.resolve()
    candidate = (snapshot_dir / pointer).resolve()
    if not candidate.is_relative_to(root):
        raise RegisterGateError(
            f"metadata.calibrator.path={pointer!r} resolves outside the snapshot ({candidate}); "
            "the bundle must be self-contained"
        )
    if not candidate.is_file():
        raise RegisterGateError(
            f"metadata.calibrator.path={pointer!r} does not name a file in the snapshot. Java "
            "would fall back to calibrator.json, so the gate would be validating a different "
            "file than the one that actually gets served."
        )
    return candidate


def _assert_sha256(holder: Any, path: Path, label: str) -> None:
    """Require a declared sha256 and verify it against the bytes on disk."""
    if not isinstance(holder, dict):
        raise RegisterGateError(f"metadata.{label} is missing or not an object")
    declared = holder.get("sha256")
    if not isinstance(declared, str) or len(declared) != 64:
        raise RegisterGateError(
            f"metadata.{label}.sha256 is missing or not a 64-char hex digest (got "
            f"{declared!r}). It is required, not optional: a skipped digest check would let a "
            f"swapped artifact through by omitting one field."
        )
    if not path.is_file():
        raise RegisterGateError(f"metadata.{label} names {path.name}, which is not in the bundle")
    actual = hashlib.sha256(path.read_bytes()).hexdigest()
    if actual != declared.lower():
        raise RegisterGateError(
            f"{path.name} on disk (sha {actual[:16]}) is not the file metadata.{label} records "
            f"(sha {declared[:16]}); the artifact was swapped or the export did not re-stamp"
        )


def _check_calibrator(
    snapshot_dir: Path, contract_labels: list[str], metadata: dict[str, Any]
) -> None:
    cal_path = _calibrator_path(snapshot_dir, metadata)
    if not cal_path.is_file():
        raise RegisterGateError(f"calibrator file missing (expected {cal_path})")
    cal = cast(dict[str, Any], _load_json(cal_path, "calibrator"))
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

    # No missing-file branch here: the model_artifact digest check already required the file to
    # exist, so a guard would be unreachable and its message could never be shown.
    onnx_path = snapshot_dir / ARTIFACT_FILE
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
    # EVERY probe row, not just the dense one. The cold-start row is the whole reason a second
    # probe exists (it is the only input exercising the LR baseline's in-graph Imputer), so
    # validating only rows[0] would approve a graph whose cold-start path is broken while its
    # dense path is fine. NaN also makes the identical-output check above evaluate False, so
    # nothing else would catch such a row either.
    #
    # MEASURED LIMIT, so nobody reads more into this than it gives: ORT's Softmax maps an
    # all-NaN logit row to a UNIFORM distribution rather than propagating NaN. A dropped
    # in-graph Imputer therefore serves a silent uniform prior on cold-start rows - a valid
    # distribution, so no assertion here can see it. What this loop does catch is a cold-start
    # row that is not a distribution at all.
    for label, raw in (("dense", rows[0]), ("cold-start", rows[1])):
        row = np.asarray(raw, dtype=np.float64)[0]
        if not np.all(np.isfinite(row)) or float(row.min()) < 0.0 or float(row.max()) > 1.0:
            raise RegisterGateError(
                f"ONNX raw output for the {label} probe row is not a probability distribution: "
                f"{row.tolist()} - a graph exported without its final softmax looks exactly like "
                "this, and temperature calibration would renormalise it into something plausible "
                "downstream, so this must be asserted PRE-calibration or not at all"
            )
        total = float(row.sum())
        if abs(total - 1.0) > _PROB_SUM_TOLERANCE:
            raise RegisterGateError(
                f"ONNX raw output for the {label} probe row does not sum to 1 (got {total}); the "
                "graph is not emitting a calibrated-ready distribution"
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
    metadata = cast(dict[str, Any], _load_json(meta_path, METADATA_FILE))
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
    except (ValueError, KeyError, TypeError, OSError, RuntimeError) as exc:
        # A truncated / half-synced contract raises JSONDecodeError (a ValueError) or KeyError,
        # neither of which is a RuntimeError - so without this the module's "every hard exit is
        # a RegisterGateError" contract breaks on exactly the interrupted-export bundles it
        # exists to catch, and the driver PR that catches RegisterGateError gets a traceback.
        raise RegisterGateError(
            f"{FEATURE_PIPELINE_FILE} is unreadable or malformed: {exc}"
        ) from exc
    declared = cast(str, spec["schema_hash"])
    passed.append("rule 7: snapshot contract schema_hash recomputed from its own content")
    passed.append("contract feature_order == PITCH_TYPE_FEATURE_COLUMNS")

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

    # OUTPUT-axis twin of assert_feature_order_matches, and like it this guards the CANONICAL
    # contract against the in-code tuple - NOT snapshot drift. A permuted snapshot cannot reach
    # here: output.labels is inside the hashed document, so it fails the canonical-hash check
    # above first. What this catches is someone editing /contracts (or PITCH_TYPE_CLASSES) so
    # the two disagree, which would ship a contract whose labels no longer describe the ONNX
    # column order the trainer produces.
    contract_labels = [str(x) for x in spec["output"]["labels"]]
    if contract_labels != list(PITCH_TYPE_CLASSES):
        raise RegisterGateError(
            f"canonical contract output.labels {contract_labels} != the in-code y7 order "
            f"{list(PITCH_TYPE_CLASSES)}; the ONNX columns follow the in-code order, so a model "
            "built against this contract would serve probabilities under the wrong labels"
        )
    passed.append("canonical contract output.labels == the in-code y7 order")

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

    artifact = metadata.get("model_artifact")
    if not isinstance(artifact, dict):
        raise RegisterGateError("metadata.model_artifact is missing or not an object")
    if artifact.get("path") != ARTIFACT_FILE:
        raise RegisterGateError(
            f"metadata model_artifact.path={artifact.get('path')!r} but the registry stores and "
            f"serves {ARTIFACT_FILE!r}; run the ONNX export, which re-stamps this"
        )
    passed.append("model_artifact names the registered ONNX")

    # Name alone approves ANY 24->7 graph dropped into the bundle. Verifying the digest catches
    # "re-exported but never re-stamped", "copied a bundle and swapped the model", and
    # "registered from a half-finished export".
    #
    # The digest is REQUIRED, not verified-if-present. A conditional check is not a check: it
    # would let a swapped ONNX through by simply omitting the field, which is a strictly easier
    # bypass than matching the hash. model_artifact.path is already a hard fail when missing;
    # anything weaker here would be an inconsistency an attacker or a half-written exporter
    # walks straight through. This same reasoning carries to the server-side twin.
    _assert_sha256(artifact, snapshot_dir / ARTIFACT_FILE, "model_artifact")
    passed.append("model_artifact sha256 matches the ONNX on disk")

    # The calibrator carries a digest too, and decision [183]'s order-preservation invariant
    # rests on it, so verifying one artifact and not the other would be arbitrary.
    _assert_sha256(
        metadata.get("calibrator"), _calibrator_path(snapshot_dir, metadata), "calibrator"
    )
    passed.append("calibrator sha256 matches the file on disk")

    # The Parquet snapshot is decision [68]'s bitwise-reproducibility record. It ships in the
    # bundle and carries its own digest, so leaving it unverified would be the same
    # arbitrariness that verifying the calibrator just fixed.
    snap = metadata.get("snapshot")
    snap_name = snap.get("path") if isinstance(snap, dict) else None
    _assert_sha256(snap, snapshot_dir / str(snap_name), "snapshot")
    passed.append("snapshot parquet sha256 matches the file on disk")

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
