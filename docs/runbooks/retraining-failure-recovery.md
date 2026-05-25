# Runbook — Recovering a failed or stuck retrain

**Owner:** alex · **Last reviewed:** 2026-05-25 · **Phase:** 3d.4

Fires when:

- a retrain run completed with `status='failed'` (the Python worker
  caught an exception and reported it back to the queue), OR
- a retrain row stayed in `running` past the 4-hour reap threshold and
  the stale-claim reaper flipped it back to `queued` (worker crashed
  before reporting), OR
- an operator wants to retry a previously-failed retrain manually.

## What just happened

Workflow recap (per leaves 3d.1 / 3d.3 / 3d.4):

1. A trigger (scheduled, drift, or manual) inserted a `retraining_queue`
   row with `status='queued'`.
2. The hourly systemd timer (`bullpen-retrain.timer`, 02:00–06:00 ET)
   fired `bullpen-retrain.service`, which ran
   `python -m bullpen_training.retraining.run`.
3. The worker `POST`ed `/v1/admin/retrain/claim` and atomically flipped
   the row to `running`.
4. From here, one of three terminal states (or a hang) happened:
   - **succeeded** — trainer wrote artifacts, worker called
     `/v1/admin/registry/{model}/register` (3a.4) producing a CANDIDATE
     row, then `/v1/admin/retrain/{trigger}/complete` flipped queue row
     to `succeeded`.
   - **failed** — trainer raised; worker reported error_message via
     `/complete?succeeded=false`. The Discord NOTICE on `DriftTrigger`
     (3d.2) may have fired separately if this was a drift retrain.
   - **stuck-running** — worker crashed before reporting (CUDA OOM,
     host reboot, GPU process killed by OOM-killer). The half-hourly
     `bullpen-stale-claim-reaper.timer` flips it back to `queued` after
     4 hours via `POST /v1/admin/retrain/reap-stale`.

## Triage

### Step 1 — Identify the row

```bash
# Recent triggers for one model, newest first
curl -fsS -u "$BULLPEN_ADMIN_USER:$BULLPEN_ADMIN_PASSWORD" \
  "https://api.thebullpen.net/v1/admin/retrain?modelName=pitch_outcome_pre" \
  | jq '.[0:5] | map({triggerId, status, enqueuedAt, finishedAt, errorMessage})'
```

### Step 2 — Read the failure context

```bash
# Pull the Python worker's structured logs for the trigger window
journalctl -u bullpen-retrain.service --since "1 hour ago" \
  | grep '<trigger_id>'
```

Every log line emitted by `run.py` is JSON with `trigger_id`,
`model_name`, and `trigger_type` bound via structlog contextvars — grep
the trigger id and read top-to-bottom. The exception (if any) appears
in `retraining: training pipeline raised` with a full traceback.

If the worker never logged, check whether it even ran:

```bash
systemctl status bullpen-retrain.timer  # next-run + last-run timestamps
systemctl status bullpen-retrain.service
```

### Step 3 — Categorize

| Symptom                                              | Diagnosis                                                                                                                                | Action                                                                                                                                                                                                                                               |
| ---------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `errorMessage` contains "CUDA out of memory"         | GPU contention — daytime workload still running, or model too big for the card                                                           | Verify GPU is idle (`nvidia-smi`). If OOM is real, the model needs more VRAM than this host has — bigger box or smaller batch.                                                                                                                       |
| `errorMessage` contains "UnsupportedModel"           | Trainer dispatch entry not yet wired (leaf 3d.3 deferred per-model wiring)                                                               | Wire the trainer in `bullpen_training.retraining._dispatch.DISPATCH` and re-enqueue.                                                                                                                                                                 |
| `errorMessage` contains "register call failed" / 4xx | Registry rejected the new candidate. Most likely the feature-pipeline hash drifted, or the canonical paths don't exist on the prod host. | Read the response body; fix the pipeline / paths. The trained artifacts are still on disk — re-enqueue and the next run will register them.                                                                                                          |
| `errorMessage` contains "schema_hash"                | Feature pipeline changed without a registry-side bootstrap reset                                                                         | Use the `registerWithBootstrap` escape hatch (rule 7, 3a.3) — requires explicit `ResetFeatureSchemaConfirmation` with a written justification. See `docs/runbooks/registry-snapshot-recovery.md` if the prior champion's artifacts were S3-archived. |
| Row is `running` with `started_at` more than 4h old  | Worker crashed mid-flight                                                                                                                | Reaper will flip it back to `queued` within 30 min automatically. Or trigger immediately: `curl -X POST -u ... /v1/admin/retrain/reap-stale`.                                                                                                        |
| Row is `queued` but never gets claimed               | Timer disabled, or api unreachable from the worker                                                                                       | `systemctl is-enabled bullpen-retrain.timer` and `curl http://localhost:8080/actuator/health`.                                                                                                                                                       |

### Step 4 — Re-enqueue (if appropriate)

For a transient failure (GPU contention, transient network), re-enqueue
manually:

```bash
curl -fsS -u "$BULLPEN_ADMIN_USER:$BULLPEN_ADMIN_PASSWORD" \
  -X POST -H "Content-Type: application/json" \
  -d '{"modelName": "pitch_outcome_pre", "reason": "retry after CUDA OOM at 03:00"}' \
  https://api.thebullpen.net/v1/admin/retrain
```

The 1-hour manual-trigger dedup window (`ManualTrigger`, 3d.2) means
calling this twice within an hour returns the same trigger row — no
risk of double-enqueue from a frustrated double-click.

### Step 5 — Cancel a stale or unwanted trigger

```bash
curl -fsS -u "$BULLPEN_ADMIN_USER:$BULLPEN_ADMIN_PASSWORD" \
  -X DELETE \
  https://api.thebullpen.net/v1/admin/retrain/<trigger_id>
```

Legal from `queued` or `running`. Terminal rows reject with 409.

## After-action

- Successful retry produces a CANDIDATE row in `model_versions`.
  Per rule 6, promotion stays human-gated — exercise the 3a.4 promote
  endpoint after reviewing the eval artifacts.
- If the failure cause was a code bug in the trainer, file an issue
  and add a regression test under `training/tests/<model>/`.
- If the failure was operator-induced (deploy mid-retrain, host
  reboot), add an entry to `docs/postmortems/{date}_{name}.md` and
  consider tightening the deploy-window check in `deploy-safely`.

## Related

- Leaf 3d.1 — queue + lifecycle service
- Leaf 3d.2 — three trigger producers
- Leaf 3d.3 — Python retrain worker
- Leaf 3d.4 — systemd timer + reaper (this leaf)
- `RetrainAdminController` source —
  `backend/src/main/java/net/thebullpen/baseball/api/admin/RetrainAdminController.java`
- Worker source —
  `training/src/bullpen_training/retraining/run.py`
