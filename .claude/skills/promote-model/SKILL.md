---
name: promote-model
description: Standard gate for promoting a model from SHADOW to CHAMPION in the registry. Trigger when the user says "promote model X", "ship model X", or wants to move a shadow model to serving. Enforces CLAUDE.md discipline rules 5, 6, 9.
---

# promote-model

The highest-blast-radius operation in the system. No promotion happens without every check below passing.

Promotion is a single authenticated HTTP call to the registry admin API:
`POST /v1/admin/registry/{modelName}/promote/{versionId}` (HTTP Basic, role ADMIN;
`RegistryAdminController`). The state change happens server-side in
`RegistryService.transitionStage` - it is atomic (archive prior champion, set new champion, wire
routing) and runs the rule-5/9 gates. **Never hand-edit the SQLite `model_versions` table.** Stages
are `SHADOW` / `CANDIDATE` -> `CHAMPION`; the displaced champion goes to `ARCHIVED`. There is no
`LIVE` / `PREVIOUS_LIVE` state.

## Hard rule

Rule 6: **No auto-promotion of retrained models.** This skill is human-invoked only. If something
automated is calling this skill, refuse.

## Pre-promotion checklist

Some of these the service enforces (it 409/422s the POST); others are human judgment. Run them all
BEFORE the call so a failure surfaces with context, not as a bare HTTP error.

1. **Pre-declared promotion criteria exist** (rule 5) - primary metric, sample size, threshold,
   guardrails - on the model's criteria (`criteria_for(model_name)`).
2. **A passing `experiment_results` row** (rule 5; service-enforced by `assertPromotionCriteriaMet`):
   - A fresh, passing row for this challenger vs the CURRENT champion within
     `PROMOTION_EVIDENCE_MAX_AGE`. A stale pass, or a pass against a since-replaced champion, does
     not count (decision [72] / B2).
   - The durable human/audit record is the committed
     `training/data/eval/promotion/{model}_experiment_results_full.json` (the full-box H2 row).
   - **Bootstrap exemption:** if the model has only ONE ever-registered version (the first champion),
     the service SKIPS this gate - there is no prior champion to produce a challenger-vs-champion
     row, so the human's full-box evidence IS the gate. Promoting on a _failed_ primary is never
     exempt (that is the [154] threshold bypass).
3. **Rule-9 baseline registered** (service-enforced by `assertBaselineRegistered`): the co-registered
   LR baseline for this primary (`BASELINE_FOR_PRIMARY`) must exist non-archived, or the POST 409s
   (`BaselineMissing`). Check `GET /v1/admin/registry/{baseline_name}`.
4. **Artifact loads** (service-enforced, INC-2 load gate, decision [151]): the endpoint
   `loadValidator.validate()`s the ONNX before the write; a missing/unloadable artifact -> 422.
   Sanity-confirm the snapshot is on disk.
5. **Shadow traffic sanity** (human): ClickHouse `prediction_log` shows shadow predictions for this
   challenger in the expected volume for the declared window.
6. **Human approval / confirm token** (rule 6): explicit user confirmation in the conversation -
   type the model name + version id back.
7. **Not in a live-game window** (rule 3): if 16:00-24:00 ET April-October with a live game, refuse
   unless explicitly overridden.

## Procedure

1. Resolve the candidate: `GET /v1/admin/registry/{modelName}` -> record the version `id`, confirm
   it is `SHADOW`, and note the version count (drives the bootstrap exemption in check 2).
2. Run the checklist. Print each as OK / BLOCK with evidence. On any BLOCK, stop with the specific
   failure - do not POST.
3. On all OK, prompt the user: "Confirm promotion by typing the model name + version id back."
4. On confirmation, POST the transition:
   ```bash
   curl -u "$ADMIN_BASIC" -X POST \
     "$API/v1/admin/registry/{modelName}/promote/{versionId}" \
     -H 'Content-Type: application/json' \
     -d '{"targetStage":"CHAMPION","reason":"<metric evidence + why now>"}'
   ```
   `$API` = the box-local API base (e.g. `http://localhost:8080`); `$ADMIN_BASIC` = the `/v1/admin/**`
   ADMIN credential. Expect `200` with `stage=CHAMPION`. Server-side this archives the prior champion
   (-> `ARCHIVED`), creates/updates the routing row (`ensureRoutingForChampion`: first promotion =
   SHADOW mode, 0 challenger traffic, so the champion serves 100%), and emits an Ops `PROMOTE` event
   (visible on `/ops`).
   - `409` = `IllegalTransition` / `PromotionCriteriaMissing` (rule 5) / `BaselineMissing` (rule 9).
   - `422` = `ArtifactMissing` / `ModelLoadFailed` (the load gate).
   - Both STOP-and-report - do not retry blindly.
5. Watch the next ~10 minutes via Prometheus / Grafana - error rate + p99 latency for the model.
   Discord-ping the promotion per the monitoring setup.

## Output

```
PROMOTION COMPLETE:
  model_name: <name>
  version_id:  <id>   stage: SHADOW -> CHAMPION
  displaced:   <previous_champion_version_id> (ARCHIVED) | none (bootstrap)
  evidence:    {model}_experiment_results_full.json | bootstrap (first champion)
WATCH:
  - Grafana dashboard <link>
  - prediction_log error rate / latency for next 30 min
  - Rollback: POST .../promote/<versionId> {"targetStage":"SHADOW","reason":"..."}
```

## Rollback (INC-1, decision [150])

If the watch window goes bad, demote via the same endpoint - NOT raw SQL:

```bash
curl -u "$ADMIN_BASIC" -X POST \
  "$API/v1/admin/registry/{modelName}/promote/{versionId}" \
  -H 'Content-Type: application/json' \
  -d '{"targetStage":"SHADOW","reason":"rollback: <symptom>"}'
```

This is a controlled `CHAMPION -> SHADOW` transition: it removes the routing row so
`InferenceRouter` finds none and the legacy fallback serves (or the predict path 503s if nothing
else is live). The version stays SHADOW and re-promotable; if it was the only version, the rule-5
bootstrap exemption stays in force (how a stuck first champion recovers). Then hand `decision-recorder`
a `decisions.md` draft documenting the rollback and the reason.

## Revision history

- **2026-06-20** - Rewritten to the real admin-endpoint mechanism. The prior version described raw
  `UPDATE models SET state='LIVE'` SQL and a `LIVE` / `PREVIOUS_LIVE` / `ROLLED_BACK` state model;
  that predated `RegistryAdminController` + `RegistryService.transitionStage` (the
  `CHAMPION` / `SHADOW` / `ARCHIVED` lifecycle, the [151] pre-write load gate, the [150] controlled
  rollback, and the [145] first-champion bootstrap exemption). Surfaced while preparing the
  `pitch_outcome_post` promotion. Hand-editing `model_versions` is no longer described or permitted.
