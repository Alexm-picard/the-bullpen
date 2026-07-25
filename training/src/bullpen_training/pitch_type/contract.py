"""Verified reads of the pitch-type feature contract (CLAUDE.md rule 7).

The contract at /contracts/feature_pipeline_pitchtype.json carries a self-declared
``schema_hash``. Trusting that field verbatim is exactly the failure rule 7 exists to
prevent: a contract whose declared hash has drifted from its content would be copied
into a model bundle AND stamped into metadata.json, and the mismatch would only surface
at REGISTRATION - after a multi-hour box training run is already spent.

:func:`load_canonical_pipeline` recomputes the hash from the file content and hard-fails
on drift, so both consumers (the ONNX export and the production persist) fail loud
BEFORE the artifact is written.

:func:`assert_declared_lookups_present` closes the other half: the contract declares the
side-car lookup files a bundle must ship (``categorical_lookup`` steps and the
calibrator). A bundle missing one is unloadable at serving, so persist refuses to report
success without them rather than silently emitting an incomplete bundle.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any, cast

from bullpen_training.registry_client import feature_hasher

REPO_ROOT = Path(__file__).resolve().parents[4]
CONTRACT_PATH = REPO_ROOT / "contracts" / "feature_pipeline_pitchtype.json"


def load_canonical_pipeline(contract_path: Path = CONTRACT_PATH) -> dict[str, Any]:
    """Read the pitch-type contract and verify its declared schema_hash. Drift is a hard fail."""
    spec = cast(dict[str, Any], json.loads(contract_path.read_text()))
    declared = spec["schema_hash"]
    recomputed = feature_hasher.compute(contract_path)
    if declared != recomputed:
        raise RuntimeError(
            f"{contract_path.name} schema_hash is stale (declared={declared} "
            f"computed={recomputed}); re-run the recompute snippet in .githooks/pre-commit."
        )
    return spec


def declared_lookup_paths(spec: dict[str, Any]) -> tuple[str, ...]:
    """Every side-car file the contract says a bundle must ship, in declaration order.

    Covers ``categorical_lookup`` preprocess steps (pitch-type: ``park_id_mapping.json``
    for ``park_i``) plus the calibrator (``calibrator.json``). Derived from the contract
    rather than hardcoded, so a future contract that adds a lookup automatically tightens
    the completeness check instead of silently going unenforced.
    """
    paths: list[str] = []
    preprocess = cast(dict[str, Any], spec.get("preprocess", {}))
    for step in preprocess.values():
        lookup = cast(dict[str, Any], step).get("lookup_path")
        if isinstance(lookup, str) and lookup not in paths:
            paths.append(lookup)
    calibrator = cast(dict[str, Any], spec.get("calibrator", {}))
    cal_lookup = calibrator.get("lookup_path")
    if isinstance(cal_lookup, str) and cal_lookup not in paths:
        paths.append(cal_lookup)
    return tuple(paths)


def assert_declared_lookups_present(out_dir: Path, spec: dict[str, Any]) -> None:
    """Fail loud if the bundle is missing any lookup file its own contract declares."""
    missing = [name for name in declared_lookup_paths(spec) if not (out_dir / name).exists()]
    if missing:
        raise ValueError(
            f"bundle at {out_dir} is missing contract-declared lookup file(s): "
            f"{', '.join(missing)}. The copied feature_pipeline.json declares them, so a "
            "bundle without them cannot be loaded for serving. This usually means the "
            "trainer's feature loader did not expose its park_id_mapping."
        )


__all__ = (
    "CONTRACT_PATH",
    "assert_declared_lookups_present",
    "declared_lookup_paths",
    "load_canonical_pipeline",
)
