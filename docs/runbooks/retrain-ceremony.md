# Runbook - Retrain ceremony (queue -> dispatch -> SHADOW -> human promote)

**Owner:** alex · **Last reviewed:** 2026-07-07 · **Phase:** 3 (retraining control plane)

Operator hand-off for driving one model retrain end-to-end through the wired
dispatch. This is the box ceremony behind the "control plane is complete; one
model is wired and proven" claim (the batted-ball head is the wired family;
BOX HAND-OFF #1). Promotion stays human-gated (rule 6): a retrain produces a
SHADOW candidate, never a champion.

> **Where:** the WSL2 desktop only (ADR-0006). Real seasons + GPU + the full
> 2015-2025 ClickHouse dataset are required; the MacBook cannot run it. No code
> editing on the box - the ceremony is HTTP calls + one Python orchestrator run.

## Scope + preconditions

- **Wired families:** `battedball_outcome` (dispatch key ->
  `_servable_battedball_outcome`, the served `BattedBallMLP` + carry head). The
  other five registry names (`pitch_outcome_{pre,post,lr_baseline}`,
  `battedball_lgbm_per_park`, `lr_baseline_batted_ball`) are explicit
  `UnsupportedModel` - a trigger for them fails loud, by design.
- The box is on the same git SHA as `main` (`git -C /opt/bullpen ... rev-parse`),
  ClickHouse is up and populated 2015-2025, the GPU is reachable, and a Layer-1
  snapshot ran first (hard rule - never train against live data without one).
- `$API` = box-local API base (e.g. `http://localhost:8080`); `$ADMIN_BASIC` =
  the `/v1/admin/**` ADMIN credential.

## Step 1 - Enqueue the trigger

Manual trigger (a drift or scheduled trigger enqueues the same way):

```bash
curl -u "$ADMIN_BASIC" -X POST "$API/v1/admin/retrain" \
  -H 'Content-Type: application/json' \
  -d '{"modelName":"battedball_outcome","reason":"ceremony: <why now>"}'
# -> 200 with the trigger_id (dedup: re-firing the same logical trigger within the
#    window is a no-op, DuplicateTriggerId). Confirm status:
curl -u "$ADMIN_BASIC" "$API/v1/admin/retrain/<triggerId>"   # status: queued
```

## Step 2 - Run the dispatch (GPU, off the serving path)

The Python orchestrator claims the queued trigger, runs the real dispatch
(`_servable_battedball_outcome`: train the served graph + carry head -> export
the single-file serving ONNX -> fit the 30x5 per-park isotonics on the held-out
val season), registers the candidate SHADOW, and marks the queue row complete.

```bash
cd training                                   # on the box
uv run python -m bullpen_training.retraining.run   # confirm the exact entrypoint
# Rule 13 is fenced inside the adapter (refuse_holdout) - a 2026 override fails loud.
```

Watch the queue row transition `queued -> claimed -> succeeded`; on any failure
it lands `failed` with an `error_message` (see
[retraining-failure-recovery.md](./retraining-failure-recovery.md)).

## Step 3 - Verify the SHADOW candidate

```bash
curl -u "$ADMIN_BASIC" "$API/v1/admin/retrain/<triggerId>"     # status: succeeded, produced_version_id set
curl -u "$ADMIN_BASIC" "$API/v1/admin/registry/battedball_outcome"  # new row present, stage=SHADOW
```

Confirm the produced bundle carries the served file set (`model.onnx`,
`metadata.json` with `feature_scaler` + `park_order` + `carry_target`,
`calibrator.json`) and that `trigger_id` is stamped in the metadata.

## Step 4 - Relay evidence to the Mac, then human-promote

- **Evidence relay (ADR-0006):** the box authors nothing in git. Relay the
  retrain diagnostics + the produced-version row through the hand-off channel;
  the Mac commits any evidence file. This is what upgrades the README claim from
  "wired" to "wired AND proven on real data".
- **Promotion is a separate, human-gated decision (rule 6).** If and only if the
  candidate clears its declared gate, run the [promote-model](../../.claude/skills/promote-model/SKILL.md)
  skill (`/promote battedball_outcome`). That skill's step 6 refreshes the drift
  baseline (`backfill_training_distributions.py`) so PSI does not go dark on the
  new champion - do not skip it, or the next PSI run fires `DriftBaselineMissing`.

## Abort / recovery

- Kill a running retrain: `systemctl stop bullpen-retrain` (or the GPU job), then
  `POST /v1/admin/retrain/reap-stale` to release a stuck `claimed` row.
- A `failed` row is safe to leave; fix the cause and enqueue a fresh trigger.
- A registered-but-unwanted SHADOW candidate stays dormant (it serves nothing);
  archive it via the registry if you want it gone. The champion is untouched
  until a human promotes.

## Related

- [h1-box-training-trigger.md](./h1-box-training-trigger.md) - the direct-CLI
  full-data pitch training (a different path: no queue/dispatch).
- [retraining-failure-recovery.md](./retraining-failure-recovery.md) - a `failed`
  or stuck queue row.
- Dispatch code: `training/src/bullpen_training/retraining/_dispatch.py` +
  `retraining/battedball_outcome.py`; end-to-end test:
  `training/tests/retraining/test_battedball_outcome_dispatch.py`.
