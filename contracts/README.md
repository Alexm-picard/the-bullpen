# `/contracts` — file-based Python↔Java boundary

This directory is the **single source of truth** for the contract between
`/training` (Python) and `/backend` (Java). See `docs/design.md` §5.

## Files

- `feature_pipeline.json` — canonical column order, dtypes, and transformations.
  `pipeline_version` + `schema_hash` are enforced by the `block-schema-hash-drift`
  pre-commit hook (`.githooks/pre-commit`) and by the registry's
  schema-hash check at model registration (CLAUDE.md discipline rule 7).

## How the schema hash works

`schema_hash` is `sha256` of the file content with the `schema_hash` field itself
zeroed out, serialized as canonical JSON (`sort_keys=True`, no whitespace).
This makes the hash stable across self-updates. Recompute via the snippet in
`.githooks/pre-commit`.

Mismatch at registration time is a HARD FAIL — there is no override flag.
