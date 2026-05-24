"""Canonical SHA-256 hash of a ``feature_pipeline.json``.

This module is the Python half of the Python <-> Java parity contract
enforced by rule 7 + decision [67]: the JVM registry refuses to register
a model whose feature-pipeline hash does not match the production
pipeline. Both sides must hash the same JSON document to the exact same
64-char hex digest.

The Java side lives in
``net.thebullpen.baseball.registry.FeatureSchemaHasher``
and ``net.thebullpen.baseball.registry.CanonicalJson``. If you change
the algorithm here, change it there too -- and rerun
``FeatureSchemaParityIT`` to confirm the fixtures still agree.

Algorithm:

1. Parse the JSON.
2. Replace the top-level ``schema_hash`` field with ``""`` so the hash
   stays stable across self-updates of that field (same trick used by
   ``.githooks/pre-commit``).
3. Serialize via ``json.dumps(sort_keys=True, separators=(",", ":"))``.
   This produces deterministic UTF-8 bytes (``ensure_ascii=True`` is
   the default, so non-ASCII becomes ``\\uXXXX`` escapes).
4. SHA-256 the bytes; return the lowercase-hex digest with no prefix.
"""

from __future__ import annotations

import copy
import hashlib
import json
from pathlib import Path
from typing import Any


def compute(feature_pipeline_path: str | Path) -> str:
    """Hash the feature pipeline JSON at ``feature_pipeline_path``."""
    path = Path(feature_pipeline_path)
    content = path.read_text(encoding="utf-8")
    return compute_from_content(content)


def compute_from_content(json_content: str) -> str:
    """Hash an in-memory JSON string (used by tests + parity fixtures)."""
    try:
        data = json.loads(json_content)
    except json.JSONDecodeError as exc:
        raise ValueError(f"registry: feature pipeline is not valid JSON: {exc}") from exc
    if not isinstance(data, dict):
        raise ValueError(
            f"registry: feature pipeline root must be a JSON object, got {type(data).__name__}"
        )
    canonical = _with_zeroed_schema_hash(data)
    canonical_blob = json.dumps(canonical, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(canonical_blob).hexdigest()


def _with_zeroed_schema_hash(root: dict[str, Any]) -> dict[str, Any]:
    """Deep copy with the top-level ``schema_hash`` field cleared.

    Mirrors ``CanonicalJson.withZeroedSchemaHash`` on the Java side --
    keep the two in sync.
    """
    out = copy.deepcopy(root)
    out["schema_hash"] = ""
    return out
