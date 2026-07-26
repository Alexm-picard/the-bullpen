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
 12. model_artifact names the file the registry stores (model.onnx); model_artifact,
     calibrator, snapshot, and the training artifact (model.lgb for the primary, model.pkl
     for the baseline - derived from model_name, NOT from which key metadata happens to
     carry) each declare a sha256 that matches the bytes on disk. Both the digest and the
     entry are REQUIRED, never verified-if-present: either conditional is bypassed by
     omitting a field. Every declared path must BE the canonical bundle filename, and no
     bundle member may be a symlink (registration copies by canonical name and by content,
     so anything else is validated here and never served)
 13. ONNX loads and scores [1,24] -> [1,7], exactly 2 outputs, probabilities at index 1
 14. two DISTINCT probes give different outputs (a constant graph is not a model)
 15. BOTH probe rows are already probability distributions
 16. the cold-start row is not UNIFORM (an Imputer-stripped LR export scores dense rows
     bit-identically to a healthy one, so this is the only check that can see it)

Checks 5 and 6 look redundant and are not. 5 proves the snapshot's contract is internally
consistent; 6 proves it still matches production. A snapshot that drifted from /contracts but
re-stamped its own hash passes 5 and fails 6 - and without 6 it would sail through here and
then 422 FeatureSchemaMismatch at Java bootstrap registration, which is the box-side failure
this whole file exists to pre-empt.

Check 9 is NOT a snapshot-drift check and cannot be reached by mutating a snapshot: output.labels
lives inside the hashed document, so a permuted snapshot fails check 5 (or check 6, if its hash
was re-stamped) first. It reads the snapshot's copy of the file, which check 6 has already proved
equal to canonical under the hasher's canonical-JSON normalisation, so this is a canonical read
by transitivity - a server-side twin
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
SNAPSHOT_FILE = "training_data.parquet"

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

# Max deviation from 1/n below which a cold-start row counts as uniform. Measured: LR baseline
# 0.4209 and LightGBM 0.4274 on real bundles, against 6.4e-9 for an Imputer-stripped graph
# (float32(1/7) vs 1/7, not exactly zero). That is 157x above the broken side and 4.2e5 below
# the legitimate side. A false positive here is fail-safe: it refuses a registration.
_UNIFORM_TOLERANCE = 1e-6


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
    except (ValueError, OSError, RecursionError) as exc:  # UnicodeDecodeError is a ValueError
        raise RegisterGateError(f"{label} is unreadable or malformed: {exc}") from exc
    if not isinstance(loaded, dict):
        raise RegisterGateError(f"{label} must be a JSON object, got {type(loaded).__name__}")
    return loaded


def _assert_real_file(path: Path, label: str) -> None:
    """A bundle must ship its own bytes for this file.

    ``is_file()`` follows symlinks, so without this a symlinked bundle member passes every
    subsequent check while the bytes live elsewhere. The box-side consequence is the quiet
    kind: a tarball transfer leaves the link dangling, SnapshotRestoreService copies declared
    lookups under ``if (Files.isRegularFile(...))`` with a log.warn and no failure, so
    registration SUCCEEDS with the file absent and FeaturePipelinePitchType.loadParkLookup
    then fails at load. Registration-succeeds-load-fails is exactly what this gate exists to
    pre-empt on the Mac.

    For the server-side twin it is worse than the digest disclosure: ``Files.isRegularFile``
    follows the link, so ``placeArtifacts`` copies the link TARGET'S CONTENT into the
    registered snapshot. tar preserves symlinks, so an uploaded
    ``park_id_mapping.json -> /etc/passwd`` becomes arbitrary server-side file content inside
    a registered artifact.
    """
    if path.is_symlink():
        raise RegisterGateError(
            f"the bundle's {label} is a SYMLINK, so it does not ship its own bytes. A tarball "
            "transfer leaves it dangling, and registration copies it by content - so the "
            "registered snapshot would silently differ from what was gated here."
        )
    if not path.is_file():
        raise RegisterGateError(f"missing {label} in {path.parent}")


def _bundle_file(snapshot_dir: Path, declared: Any, canonical: str, label: str) -> Path:
    """Resolve a metadata-declared bundle path, requiring it to BE the canonical file.

    Containment is not sufficient, and this is the subtle part. Registration rewrites the
    bundle between this gate and the loader: SnapshotRestoreService copies the calibrator by
    canonical name only (``sourceDir.resolve(SnapshotStorage.CALIBRATOR_FILE)``), and the extra
    copy-list is driven off ``preprocess`` lookups, which ignore ``calibrator.path``. So a
    pointer at some OTHER in-bundle file is validated here, dropped at registration, dangles in
    the registered snapshot, and LoadedPitchTypeModel falls back to calibrator.json - serving a
    file this gate never looked at. Demonstrated: served T=9.9 while the gate validated T=0.232.

    Equality alone is NOT enough either, because it resolves the leaf on both sides: if the
    canonical file is ITSELF a symlink out of the bundle, both sides resolve to the same
    out-of-bundle real path and the comparison is a tautology. So the canonical file must also
    not be a symlink. That is the shape a bundle is most likely to acquire by accident here
    (ADR-0007's portable-drive mirror, a multi-GB parquet), and the box-side consequence is
    quiet: snapshots arrive as a tarball, the link dangles, and SnapshotRestoreService's
    ``if (Files.isRegularFile(calibrator))`` SKIPS it - registration succeeds with no
    calibrator at all and the failure only shows up at load.

    For the server-side twin the same hole is an arbitrary-file read: ``_assert_sha256`` reads
    the target and puts 16 hex chars of its digest in the refusal message, and tar preserves
    symlinks, so ``calibrator.json -> /etc/passwd`` in an uploaded archive would be a
    disclosure primitive driven by an attacker-controlled field.
    """
    canonical_path = snapshot_dir / canonical
    # Also a tautology guard: every comparison below resolves through this path, so if it is
    # itself a symlink both sides resolve equal and the check proves nothing.
    _assert_real_file(canonical_path, canonical)
    target = canonical_path.resolve()
    if not isinstance(declared, str) or not declared.strip():
        return snapshot_dir / canonical
    try:
        candidate = (snapshot_dir / declared).resolve()
    except (ValueError, OSError) as exc:
        # e.g. an embedded null byte; must not escape as a raw ValueError.
        raise RegisterGateError(
            f"metadata.{label}.path={declared!r} is not a usable path: {exc}"
        ) from exc
    if candidate != target:
        raise RegisterGateError(
            f"metadata.{label}.path={declared!r} does not name the bundle's {canonical}. "
            f"Registration copies that file by canonical name and the loader falls back to it, "
            f"so anything else would be validated here and never served."
        )
    return candidate


def _calibrator_path(snapshot_dir: Path, metadata: dict[str, Any]) -> Path:
    """Resolve the calibrator the loader will actually open."""
    cal = metadata.get("calibrator")
    if not isinstance(cal, dict):
        # Java would fall back cleanly here (.path() yields a MissingNode). The gate is
        # deliberately STRICTER: a calibrator entry that is not an object cannot carry the
        # digest this gate requires, so approving it means approving an unverified calibrator.
        raise RegisterGateError("metadata.calibrator is missing or not an object")
    return _bundle_file(snapshot_dir, cal.get("path"), CALIBRATOR_FILE, "calibrator")


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
    actual = hashlib.sha256(path.read_bytes()).hexdigest()
    if actual != declared.lower():
        raise RegisterGateError(
            f"{path.name} on disk (sha {actual[:16]}) is not the file metadata.{label} records "
            f"(sha {declared[:16]}); the artifact was swapped or the export did not re-stamp"
        )


def _check_calibrator(
    snapshot_dir: Path, contract_labels: list[str], metadata: dict[str, Any]
) -> None:
    # No presence guard: every path here comes through _bundle_file -> _assert_real_file, so a
    # guard would be unreachable and its message could never be shown.
    cal_path = _calibrator_path(snapshot_dir, metadata)
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
    # exist (and, via _bundle_file, that it is the canonical non-symlink bundle file), so a
    # guard would be unreachable and its message could never be shown.
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
    # ignores its input, and the NaN cold-start row is the only input that reaches the LR
    # baseline's in-graph Imputer at all (the same pair ModelLoadValidator runs, for the same
    # reason). Reaching it is not the same as VERIFYING it - see the uniformity check below.
    dense = np.full((1, n_features), 0.37, dtype=np.float32)
    cold = np.zeros((1, n_features), dtype=np.float32)
    try:
        # NULLABLE_FEATURE_COLUMNS is a second name list that must track the canonical order;
        # a drift raises a bare ValueError from tuple.index, which would escape run_gate and
        # break the module's every-hard-exit-is-a-RegisterGateError contract.
        nullable = list(nullable_column_indices())
    except ValueError as exc:
        raise RegisterGateError(
            f"NULLABLE_FEATURE_COLUMNS no longer matches the canonical feature order: {exc}"
        ) from exc
    cold[0, nullable] = np.nan
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
    # MEASURED, and the reason the uniformity check below exists. A BARE Softmax on an all-NaN
    # row returns NaN, same as numpy - the uniform output comes from ai.onnx.ml.LinearClassifier
    # with post_transform=SOFTMAX, which is what the LR baseline's graph actually uses
    # (Imputer -> Scaler -> LinearClassifier -> Normalizer; there is no bare Softmax op in it).
    # So a dropped in-graph Imputer does not crash and does not emit NaN: it silently serves a
    # uniform prior on every cold-start row, a perfectly valid distribution and therefore
    # invisible to the is-this-a-distribution assertions in this loop.
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

    # A UNIFORM cold-start row means the graph learned nothing from the row's non-null features,
    # which is what an Imputer-stripped LR export looks like: dense rows score bit-identically
    # to the healthy graph, so no dense assertion and no two-rows-differ check can see it.
    # Measured margin on real bundles is ~0.42 against 6.4e-9 for the stripped graph. The
    # nearest legitimate construction anyone found is an exactly-balanced LR at C=1e-9 (2.3e-8);
    # this project uses C=1.0, and a false positive is fail-safe.
    #
    # This matters for the BASELINE specifically. A baseline serving a uniform 1/7 prior on
    # every cold-start row (a pitcher's first career pitch, NULL ARS) is a silently degraded
    # denominator for decision [183]'s log-loss guardrail, which would flatter the primary
    # head's margin. Rule 9 makes the baseline a first-class registry row; it has to be as
    # trustworthy as the primary.
    cold_row = np.asarray(rows[1], dtype=np.float64)[0]
    if float(np.abs(cold_row - 1.0 / n_classes).max()) < _UNIFORM_TOLERANCE:
        raise RegisterGateError(
            f"ONNX returns a UNIFORM {1.0 / n_classes:.6f} distribution for the cold-start probe "
            "row, so the graph is ignoring that row's non-null features. An LR export that lost "
            "its in-graph Imputer looks exactly like this: dense rows are unaffected, and ORT "
            "turns the resulting all-NaN logits into a valid-looking uniform row rather than NaN"
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
    _assert_real_file(meta_path, METADATA_FILE)
    _assert_real_file(fp_path, FEATURE_PIPELINE_FILE)
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
        if isinstance(exc, RuntimeError) and not isinstance(exc, RecursionError):
            # The contract helpers raise with their own precise text (stale hash, feature_order
            # drift). Those files are perfectly readable, so prefixing them "unreadable or
            # malformed" would misdescribe the most likely real rule-7 refusal there is.
            raise RegisterGateError(str(exc)) from exc
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

    # park_id_mapping.json is a SERVING-path file (it encodes park_i), so the same
    # ship-your-own-bytes rule applies to it as to the artifacts.
    for name in declared_lookup_paths(spec):
        _assert_real_file(snapshot_dir / name, f"contract-declared lookup {name}")
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
    _assert_sha256(
        artifact,
        _bundle_file(snapshot_dir, artifact.get("path"), ARTIFACT_FILE, "model_artifact"),
        "model_artifact",
    )
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
    if not isinstance(snap, dict):
        raise RegisterGateError("metadata.snapshot is missing or not an object")
    _assert_sha256(
        snap, _bundle_file(snapshot_dir, snap.get("path"), SNAPSHOT_FILE, "snapshot"), "snapshot"
    )
    passed.append("snapshot parquet sha256 matches the file on disk")

    # Which extra artifact a bundle ships is decided by WHICH MODEL it is, and model_name is
    # already validated above. Keying this off "is the entry present in metadata" would be the
    # same omit-to-bypass hole rejected for the digests themselves: delete the key, skip the
    # check. The training artifact is not on the serving path, but it is what a retrain reloads.
    # Explicit mapping rather than an if/else: a third family member added to FAMILY would
    # otherwise silently inherit the LR shape and be asked for a model.pkl it never ships.
    # One mapping for everything that varies by family member, so a third member cannot
    # silently inherit another's shape - it has to be declared here or the gate refuses.
    # TreeEnsemble is ai.onnx.ml v5's replacement for TreeEnsembleClassifier; onnxmltools emits
    # the latter today, and accepting both means an opset bump does not refuse a valid primary.
    family_shape = {
        PRIMARY_MODEL: (
            "lightgbm_artifact",
            "model.lgb",
            frozenset({"TreeEnsembleClassifier", "TreeEnsemble"}),
        ),
        BASELINE_MODEL: ("sklearn_artifact", "model.pkl", frozenset({"LinearClassifier"})),
    }
    if model_name not in family_shape:
        raise RegisterGateError(
            f"no bundle shape is declared for {model_name!r}; add it to family_shape rather "
            "than letting the bundle register unchecked"
        )
    extra_key, extra, expected_ops = family_shape[model_name]
    entry = metadata.get(extra_key)
    entry_path = entry.get("path") if isinstance(entry, dict) else None
    _assert_sha256(entry, _bundle_file(snapshot_dir, entry_path, extra, extra_key), extra_key)
    passed.append(f"{extra_key} sha256 matches the {extra} on disk")

    # ORT resolves model.onnx.data as graph weights and SnapshotRestoreService copies it when
    # present, so a swapped sidecar changes the served model while model_artifact.sha256 stays
    # valid - the same omit-to-bypass class closed above. Neither pitch-type graph uses external
    # data (both are ~1 KB), so its presence means something unexpected assembled this bundle.
    # Cheap filename pre-filter; the graph-level test that actually covers this is below,
    # after ORT has parsed the file.
    if (snapshot_dir / (ARTIFACT_FILE + ".data")).exists():
        raise RegisterGateError(
            f"bundle contains {ARTIFACT_FILE}.data (ONNX external-data weights); its digest is "
            "not covered by model_artifact.sha256, so it could change the served model invisibly"
        )

    n_features = len(PITCH_TYPE_FEATURE_COLUMNS)
    n_classes = len(PITCH_TYPE_CLASSES)
    _probe_onnx(snapshot_dir, n_features, n_classes)

    # Rule 9 is about two genuinely DIFFERENT models, not two rows. If a bundle were assembled
    # (or fat-fingered) so both rows carry the same graph family, decision [183]'s log-loss
    # guardrail would compare a model against itself and always look non-inferior. The digests
    # catch the accidental version; this catches the disguised one.
    #
    # Deliberately LAST: it runs after every probe check so it can never mask the refusal a
    # more specific check would have produced.
    import onnx as onnx_mod

    # Loaded only now, so ORT has already proved the file parses: a corrupt graph refuses with
    # the ONNX-load message instead of leaking a raw protobuf DecodeError from here.
    # load_external_data=False means this never reads a sidecar it is about to refuse.
    model = onnx_mod.load(str(snapshot_dir / ARTIFACT_FILE), load_external_data=False)
    graph = model.graph

    # The filename check above misses the real case: saving with location="weights.bin"
    # produces a sidecar SnapshotRestoreService never copies (it copies only ARTIFACT_FILE +
    # ".data"), so registration succeeds and the box fails at load. Ask the graph instead.
    external = [
        i.name for i in graph.initializer if i.data_location == onnx_mod.TensorProto.EXTERNAL
    ]
    if external:
        raise RegisterGateError(
            f"the ONNX keeps initializer(s) {external} in EXTERNAL data. Neither pitch-type "
            "exporter does that, model_artifact.sha256 does not cover the sidecar, and "
            "registration only copies a sidecar named after the artifact - so the box would "
            "register successfully and then fail at load."
        )
    passed.append("ONNX carries its weights inline, not as external data")

    ops = {n.op_type for n in graph.node}
    foreign = {op for k, (_, _, marks) in family_shape.items() if k != model_name for op in marks}
    if not (ops & expected_ops):
        raise RegisterGateError(
            f"{model_name} must be served by a graph containing one of {sorted(expected_ops)}, "
            f"but its ONNX has {sorted(ops)}. Rule 9's two rows are only a guardrail if they are "
            "two different models; a same-family pair makes [183]'s comparison self-referential."
        )
    # Presence alone is defeated by appending a dead node of the expected type, so the OTHER
    # family's marker must be absent too. The real exporters emit disjoint op sets
    # (primary: Cast/Identity/Mul/TreeEnsembleClassifier; baseline:
    # Imputer/LinearClassifier/Normalizer/Scaler), so this cannot refuse a legitimate bundle.
    if ops & foreign:
        raise RegisterGateError(
            f"{model_name}'s ONNX also contains {sorted(ops & foreign)}, which belongs to the "
            "other rule-9 row. A graph carrying both families' markers is disguised, not shared."
        )
    passed.append(f"rule 9: graph family is {sorted(ops & expected_ops)}, as this row requires")
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
