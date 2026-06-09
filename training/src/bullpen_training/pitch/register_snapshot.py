"""Pitch snapshot export/registration driver (W4b - pitch registration scaffolding).

This is the Mac-doable tooling that writes a CANONICAL snapshot directory for a
pitch head (``pitch_outcome_pre`` or ``pitch_outcome_post``) in EXACTLY the
filename layout the Java serving path reads (``SnapshotStorage`` +
``LoadedPitchModel`` + ``PitchOnnxModel``). The real full-data ONNX weights are a
box hand-off; this driver assembles the snapshot around whatever ``model.onnx``
it is handed - a tiny deterministic fixture (local register->serve exercise) or
the production booster export (box).

CANONICAL FILES emitted into ``<out_dir>`` (shared contract with full-stack's
W4a register-copy fix - keep this set in sync on both sides):

    model.onnx                # the ONNX graph (+ model.onnx.data if external)
    metadata.json             # carries calibrator.path (= "calibrator.json")
    feature_pipeline.json     # the per-head contract, copied from /contracts
    calibrator.json           # per-class isotonic breakpoints
    training_data.parquet     # snapshot of the training slice (eval/audit)
    pitcher_te.json           # Tier-2 pitcher target-encoding lookup  (pre+post)
    batter_te.json            # Tier-2 batter target-encoding lookup   (pre+post)
    park_id_mapping.json      # Tier-1 park_id -> int                  (pre+post)
    pitch_type_mapping.json   # Tier-4 pitch_type -> int               (POST only)

``metadata.json`` MUST carry ``calibrator.path`` because ``LoadedPitchModel``
resolves the calibrator from that pointer (decision [152], first-champion
incident class) before falling back to the canonical ``calibrator.json``. The
``feature_pipeline_hash`` (== contract ``schema_hash``) is the rule-7 gate the
registry checks at registration.

ONNX I/O names: the Java reader (``PitchOnnxModel``) resolves the input tensor
name from the loaded session rather than hardcoding it, so this driver does not
need to negotiate a name. By convention (and to match the committed backend
fixture + the LightGBM/LR export family) the input tensor is named ``"input"``
and the probability output ``"probabilities"``. The reader is name-agnostic;
this is documentation, not a load-bearing constraint.

This module performs NO training. It is purely the file-assembly + provenance
half of registration; the rule-7 / rule-9 hard-exit checks live in
``register_gate.py`` and run as a dry-run before a real registry insert.
"""

from __future__ import annotations

import hashlib
import json
import shutil
import subprocess
from collections.abc import Sequence
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from bullpen_training.features import LABEL_CLASSES
from bullpen_training.pitch import (
    PITCH_FEATURE_COLUMNS,
    PITCH_FEATURE_COLUMNS_POST,
)

REPO_ROOT = Path(__file__).resolve().parents[4]
CONTRACTS_DIR = REPO_ROOT / "contracts"

# Canonical filenames - mirror net.thebullpen.baseball.registry.SnapshotStorage.
ARTIFACT_FILE = "model.onnx"
ARTIFACT_EXTERNAL_DATA_FILE = "model.onnx.data"
METADATA_FILE = "metadata.json"
FEATURE_PIPELINE_FILE = "feature_pipeline.json"
CALIBRATOR_FILE = "calibrator.json"
TRAINING_DATA_FILE = "training_data.parquet"

# Tier-2 / Tier-1 lookups the Java feature pipeline resolves from the snapshot dir.
PITCHER_TE_FILE = "pitcher_te.json"
BATTER_TE_FILE = "batter_te.json"
PARK_ID_MAPPING_FILE = "park_id_mapping.json"
PITCH_TYPE_MAPPING_FILE = "pitch_type_mapping.json"  # POST only

# ONNX I/O name convention (documented; the Java reader is name-agnostic).
ONNX_INPUT_NAME = "input"
ONNX_OUTPUT_NAME = "probabilities"

# Per-head wiring: which contract, which feature tuple, which extra lookups,
# how many features the ONNX must accept on axis 1.
_CONTRACT_BY_HEAD: dict[str, Path] = {
    "pitch_outcome_pre": CONTRACTS_DIR / "feature_pipeline.json",
    "pitch_outcome_post": CONTRACTS_DIR / "feature_pipeline_post.json",
    # The LR baseline shares the pre Tier 1+2+3 feature set + contract (rule 9
    # partner; decision [37]). It registers as its own model_name.
    "pitch_outcome_lr_baseline": CONTRACTS_DIR / "feature_pipeline.json",
}

_FEATURES_BY_HEAD: dict[str, Sequence[str]] = {
    "pitch_outcome_pre": PITCH_FEATURE_COLUMNS,
    "pitch_outcome_post": PITCH_FEATURE_COLUMNS_POST,
    "pitch_outcome_lr_baseline": PITCH_FEATURE_COLUMNS,
}

# Which heads need the POST-only pitch_type lookup.
_POST_HEADS = frozenset({"pitch_outcome_post"})


def contract_path_for(head: str) -> Path:
    """The canonical /contracts feature-pipeline file for a pitch head."""
    try:
        return _CONTRACT_BY_HEAD[head]
    except KeyError as exc:
        raise ValueError(
            f"unknown pitch head {head!r}; expected one of {sorted(_CONTRACT_BY_HEAD)}"
        ) from exc


def feature_columns_for(head: str) -> Sequence[str]:
    """The in-code feature tuple for a pitch head (pre=31, post=41)."""
    return _FEATURES_BY_HEAD[head]


def _git_commit_sha() -> str:
    try:
        return subprocess.check_output(
            ["git", "rev-parse", "HEAD"], cwd=str(REPO_ROOT), text=True
        ).strip()
    except (subprocess.CalledProcessError, FileNotFoundError):
        return "unknown"


def _sha256_of_path(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(8192), b""):
            h.update(chunk)
    return h.hexdigest()


def _read_contract_schema_hash(head: str) -> str:
    spec = json.loads(contract_path_for(head).read_text())
    return str(spec["schema_hash"])


@dataclass(frozen=True)
class PitchSnapshotInputs:
    """Everything the driver assembles into a canonical pitch snapshot.

    The ONNX + lookups + calibrator are paths to pre-built artifacts (a tiny
    fixture locally, or the box-trained export). The driver copies them under
    canonical names, writes metadata.json, copies the per-head contract, and
    writes the training_data parquet.
    """

    head: str
    version: str
    onnx_path: Path
    calibrator_path: Path
    pitcher_te_path: Path
    batter_te_path: Path
    park_id_mapping_path: Path
    # POST only - the Tier-4 pitch_type -> int lookup. None for pre / LR.
    pitch_type_mapping_path: Path | None = None
    # External ONNX weights (model.onnx.data) when the graph is too big for a
    # single proto. None for the tiny fixtures + most LightGBM exports.
    onnx_external_data_path: Path | None = None
    # The training-slice snapshot. A path to an existing parquet (box) or None
    # to let the caller pass a DataFrame via training_df.
    training_data_path: Path | None = None
    training_df: Any | None = None  # pandas.DataFrame, kept Any to avoid an import
    training_data_window: str = "2015-2023"
    val_window: str = "2024"
    # Provenance carried into metadata; opaque to the driver.
    hyperparams: dict[str, Any] | None = None
    eval_metrics_summary: dict[str, Any] | None = None
    experiment_results_id: int | None = None
    # The rule-9 baseline partner's registered model_name (informational in
    # metadata; the gate enforces presence separately).
    baseline_model_name: str | None = None


def write_snapshot(inputs: PitchSnapshotInputs, out_dir: Path) -> Path:
    """Assemble a canonical pitch snapshot at ``out_dir`` and return it.

    Idempotent: re-running overwrites the canonical files. Raises if a head is
    unknown, if a required source artifact is missing, or - for the post head -
    if the pitch_type lookup is absent.
    """
    head = inputs.head
    contract = contract_path_for(head)  # validates head
    out_dir.mkdir(parents=True, exist_ok=True)

    # 1. ONNX graph (+ external data if present).
    _require(inputs.onnx_path, "onnx")
    shutil.copyfile(inputs.onnx_path, out_dir / ARTIFACT_FILE)
    if inputs.onnx_external_data_path is not None:
        _require(inputs.onnx_external_data_path, "onnx external data")
        shutil.copyfile(inputs.onnx_external_data_path, out_dir / ARTIFACT_EXTERNAL_DATA_FILE)

    # 2. Calibrator.
    _require(inputs.calibrator_path, "calibrator")
    cal_dst = out_dir / CALIBRATOR_FILE
    shutil.copyfile(inputs.calibrator_path, cal_dst)

    # 3. Per-head contract -> feature_pipeline.json (head-agnostic on-disk name).
    shutil.copyfile(contract, out_dir / FEATURE_PIPELINE_FILE)

    # 4. Tier-2 / Tier-1 lookups (pre + post share the TE + park lookups).
    _require(inputs.pitcher_te_path, "pitcher_te")
    shutil.copyfile(inputs.pitcher_te_path, out_dir / PITCHER_TE_FILE)
    _require(inputs.batter_te_path, "batter_te")
    shutil.copyfile(inputs.batter_te_path, out_dir / BATTER_TE_FILE)
    _require(inputs.park_id_mapping_path, "park_id_mapping")
    shutil.copyfile(inputs.park_id_mapping_path, out_dir / PARK_ID_MAPPING_FILE)

    # 5. POST-only pitch_type lookup. Non-negotiable for the post head -
    # FeaturePipelinePitchPost.load resolves it from the snapshot dir.
    if head in _POST_HEADS:
        if inputs.pitch_type_mapping_path is None:
            raise ValueError(
                f"{head} requires pitch_type_mapping_path; the post pipeline "
                "resolves pitch_type_mapping.json from the snapshot dir"
            )
        _require(inputs.pitch_type_mapping_path, "pitch_type_mapping")
        shutil.copyfile(inputs.pitch_type_mapping_path, out_dir / PITCH_TYPE_MAPPING_FILE)
    elif inputs.pitch_type_mapping_path is not None:
        raise ValueError(
            f"{head} is not a post head but pitch_type_mapping_path was given; "
            "the pre/LR pipelines never read pitch_type_mapping.json"
        )

    # 6. Training-data snapshot.
    snapshot_path, training_data_hash = _write_training_snapshot(out_dir, inputs)

    # 7. metadata.json (carries calibrator.path - decision [152]).
    _write_metadata(
        out_dir,
        inputs,
        training_data_hash=training_data_hash,
        training_snapshot=snapshot_path,
    )
    return out_dir


def _write_training_snapshot(out_dir: Path, inputs: PitchSnapshotInputs) -> tuple[Path, str]:
    dst = out_dir / TRAINING_DATA_FILE
    if inputs.training_data_path is not None:
        _require(inputs.training_data_path, "training_data")
        shutil.copyfile(inputs.training_data_path, dst)
    elif inputs.training_df is not None:
        inputs.training_df.to_parquet(dst, index=False)
    else:
        raise ValueError(
            "one of training_data_path / training_df is required for the "
            "training_data.parquet snapshot"
        )
    return dst, _sha256_of_path(dst)


def _write_metadata(
    out_dir: Path,
    inputs: PitchSnapshotInputs,
    *,
    training_data_hash: str,
    training_snapshot: Path,
) -> Path:
    head = inputs.head
    onnx_dst = out_dir / ARTIFACT_FILE
    cal_dst = out_dir / CALIBRATOR_FILE

    payload: dict[str, Any] = {
        "model_name": head,
        "model_version": inputs.version,
        "phase": "2b" if head in _POST_HEADS else "2a",
        "head": "post" if head in _POST_HEADS else "pre",
        "feature_order": list(feature_columns_for(head)),
        "class_labels": list(LABEL_CLASSES),
        "training_data_window": inputs.training_data_window,
        "val_window": inputs.val_window,
        "training_data_hash": training_data_hash,
        "training_data_snapshot": {
            "path": training_snapshot.name,
            "sha256": training_data_hash,
        },
        "model_artifact": {
            "path": onnx_dst.name,
            "sha256": _sha256_of_path(onnx_dst),
        },
        # LoadedPitchModel.readCalibratorRelPath reads calibrator.path; this is
        # the load-bearing field (decision [152]). The on-disk name is canonical.
        "calibrator": {
            "kind": "isotonic_per_class",
            "path": cal_dst.name,
            "sha256": _sha256_of_path(cal_dst),
        },
        # Rule-7 gate: must equal the contract schema_hash.
        "feature_pipeline_hash": _read_contract_schema_hash(head),
        "onnx_io": {
            "input_name": ONNX_INPUT_NAME,
            "output_name": ONNX_OUTPUT_NAME,
            "note": (
                "names are a convention; PitchOnnxModel resolves the input "
                "name from the loaded session (decision [152])"
            ),
        },
        "git_commit": _git_commit_sha(),
        "registered_at": datetime.now(UTC).isoformat(timespec="seconds"),
    }
    if inputs.onnx_external_data_path is not None:
        payload["model_artifact"]["external_data"] = ARTIFACT_EXTERNAL_DATA_FILE
    if inputs.hyperparams is not None:
        payload["hyperparams"] = inputs.hyperparams
    if inputs.eval_metrics_summary is not None:
        payload["eval_metrics_summary"] = inputs.eval_metrics_summary
    if inputs.experiment_results_id is not None:
        payload["experiment_results_id"] = inputs.experiment_results_id
    if inputs.baseline_model_name is not None:
        payload["baseline_model_name"] = inputs.baseline_model_name

    meta_path = out_dir / METADATA_FILE
    meta_path.write_text(json.dumps(payload, indent=2, sort_keys=False) + "\n")
    return meta_path


def _require(path: Path, what: str) -> None:
    if not Path(path).is_file():
        raise FileNotFoundError(f"register-snapshot: missing {what} source at {path}")


__all__ = (
    "ARTIFACT_EXTERNAL_DATA_FILE",
    "ARTIFACT_FILE",
    "BATTER_TE_FILE",
    "CALIBRATOR_FILE",
    "FEATURE_PIPELINE_FILE",
    "METADATA_FILE",
    "ONNX_INPUT_NAME",
    "ONNX_OUTPUT_NAME",
    "PARK_ID_MAPPING_FILE",
    "PITCHER_TE_FILE",
    "PITCH_TYPE_MAPPING_FILE",
    "TRAINING_DATA_FILE",
    "PitchSnapshotInputs",
    "contract_path_for",
    "feature_columns_for",
    "write_snapshot",
)
