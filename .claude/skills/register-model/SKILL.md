---
name: register-model
description: The full intake procedure for registering a new model (or new model version) in The Bullpen registry. Trigger when the user says "register a model", "add a new model", "register version X", or after a training run produces a new ONNX artifact ready for shadow routing.
---

# register-model

Standard intake for new models. Every model entering the registry must pass these checks. Discipline rules 7 and 9 are non-negotiable.

## Inputs you need from the user

- Path to the ONNX artifact (typically `/training/artifacts/<run_id>/model.onnx`)
- Path to the metadata JSON (typically alongside the ONNX file)
- Model role: `pre_pitch_outcome`, `post_pitch_outcome`, `batted_ball`, or `lr_baseline_<role>`
- Initial routing state: should always be `SHADOW` (never `LIVE` at registration time)
- Park ID (for per-park batted-ball heads) or `null`

If the model role is a primary model (not LR baseline), confirm the LR baseline for the same role is already registered or will be registered in the same operation. Rule 9 partner: no primary model without its baseline.

## Procedure

1. **Validate the ONNX file** — load it through ONNX Runtime Java in a test context. Confirm input names, dtypes, and output shape match the metadata.
2. **Compute the feature schema hash** from `/contracts/feature_pipeline.json` and compare against the metadata's declared hash. **HARD FAIL** if they differ — do not proceed. Rule 7.
3. **Verify the calibrator** — the metadata must reference a calibration file (e.g., isotonic regression coefficients) and that file must exist and load.
4. **Verify rolling-CV evaluation evidence** — the metadata must include the `experiment_results` row id from a passing rolling-origin CV run. Use the `run-rolling-cv` skill if missing.
5. **Insert into the registry**:
   - `INSERT INTO models (...)` via the SQLite registry
   - state = `SHADOW`
   - schema_hash = computed hash
   - linked LR baseline id (if primary)
   - calibrator path, ONNX path, metadata path, registered_at = now
6. **Smoke-load on the api process** — restart `api` profile JAR locally and verify the ONNX session loads at startup without error.
7. **Confirm shadow routing** — query the A/B router config and confirm the new model is in the shadow set (logged predictions, not user-visible).

## Hard exits

Refuse and roll back if:
- Schema hash mismatch (rule 7)
- LR baseline missing for a primary model (rule 9)
- Calibrator file missing or fails to load
- No `experiment_results` row referenced
- ONNX runtime cannot load the file
- Model role tries to register `pre_pitch` and `post_pitch` as the same row (rule 9)

## Output to user

```
REGISTERED:
  model_id: <id>
  role: <role>
  state: SHADOW
  schema_hash: <hash>
  experiment_results_id: <id>
  baseline_model_id: <id-or-null>
FILES TOUCHED:
  <paths>
NEXT STEPS:
  - Monitor /actuator/metrics for shadow prediction logs in ClickHouse (15 min)
  - When ready for promotion, use the promote-model skill
```

Hand off a draft `decisions.md` entry to the `decision-recorder` agent only if this registration represents a material choice (new model family, new feature set), not for routine version bumps.
