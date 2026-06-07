# Runbook - Clean recovery of the stuck first champion (incident 2026-06-07)

**Owner:** developer (Mac) + box-operator · **Created:** 2026-06-07 · **Phase:** 3a / Phase-6 ops
**Incident:** [`docs/postmortems/incident-2026-06-07-first-champion-promotion-500.md`](../postmortems/incident-2026-06-07-first-champion-promotion-500.md)
**Pairs with decisions:** `[149]` (map calibrator), `[150]` (INC-1 rollback), `[151]` (INC-2 load gate), `[152]` (reader input-name fix)

This runbook takes the box from "battedball_outcome/v1 is a stuck, unloadable
CHAMPION 500ing `/all-parks`" to "v1 is a healthy CHAMPION serving `/all-parks`
with a 200" - the durable clean recovery, not the interim re-key unblock.

It is **gated on a deploy** of three changes. All are on `main` but NOT yet on
the box:

| Change     | Commit    | Decision | What it enables for recovery                                                                                                                      |
| ---------- | --------- | -------- | ------------------------------------------------------------------------------------------------------------------------------------------------- |
| INC-1      | `2878420` | `[150]`  | `CHAMPION -> SHADOW` rollback (a stuck champion is demotable)                                                                                     |
| INC-2      | `27298ac` | `[151]`  | promote load gate (a non-loadable model can't re-champion)                                                                                        |
| reader fix | `2ca7e2f` | `[152]`  | reader resolves the input name (the MLP names it `"features"`, not `"input"`) - WITHOUT this the INC-2 gate 422s the re-promote on the input name |

**Do not start before `./deploy.sh` has put a SHA >= `2ca7e2f` on the box.**
Confirm with `readlink -f /opt/bullpen/app.jar` + the deployed-SHA print from
`deploy.sh`. The reader fix (`2ca7e2f`) is the load-bearing prerequisite: it is
the most recent of the three and the one that lets step 4's load gate pass.

---

## Why this sequence (the constraints that shape it)

- **INC-6 (rule-5 deadlock): recover v1, never register a v2.** Registering a
  fix-version makes the model two-versioned, the bootstrap exemption is lost, and
  promoting v2 needs an `experiment_results` row whose only possible baseline (v1)
  is unloadable. So the recovery fixes the **existing v1 snapshot in place** and
  re-promotes v1. No new version.
- **`/all-parks` has no toy fallback.** The single `/v1/predict/batted-ball`
  endpoint falls back to the toy v0 (a graceful 200) when there is no routing row.
  `/v1/predict/batted-ball/all-parks` does **not** - its fallback supplier calls
  `requireChampionId()`, so with the champion demoted it returns a clean **503**
  ("has no LIVE champion ... promote a model first"), not a prediction. That is
  strictly better than the calibrator-500 (honest + actionable), but it means the
  **demote -> re-promote window is a 503 window for `/all-parks`**. The snapshot is
  already complete (step 2 only verifies), so the window is just demote -> re-promote;
  keep it short.
- **The load gate runs on the re-promote, and depends on the reader fix being
  deployed.** Step 4's `promote -> champion` loads v1 + runs a forward pass through
  the SAME reader serving uses (INC-2). With `[152]` deployed, the reader resolves
  the model's `"features"` input and the pass succeeds; WITHOUT it, the gate returns
  **422 `Unknown input name "input"`** - which is the signal that the reader fix did
  not make the deploy, NOT a snapshot defect. Either way v1 stays SHADOW (safe). A
  green step 4 is the proof the model loads + predicts end-to-end; C5 confirms serving.

---

## Pre-checks (box-operator, read-only)

```bash
BASE=http://localhost:8080            # box-side; external confirm via https://api.thebullpen.net
ADMIN="<admin-user>:<admin-pass>"     # the /v1/admin Basic creds (systemd EnvironmentFile)
M=battedball_outcome

# 0a. All three changes are deployed (SHA >= 2ca7e2f - the reader fix is the latest):
readlink -f /opt/bullpen/app.jar
journalctl -u bullpen-api --since "10 min ago" | grep -i "Started\|version" | tail

# 0b. Current registry state - confirm v1 is the stuck CHAMPION, single version:
curl -s "$BASE/v1/ops/registry/$M" | jq '.[] | {id, version, stage, artifact_path}'
#   expect exactly one row: {id: <ID>, version: "v1", stage: "CHAMPION", ...}
#   capture <ID> -> export VID=<ID>

# 0c. Confirm the live symptom (the calibrator-500 on /all-parks):
curl -s -o /dev/null -w "%{http_code}\n" -X POST "$BASE/v1/predict/batted-ball/all-parks" \
  -H 'Content-Type: application/json' \
  -d '{"launchSpeedMph":102,"launchAngleDeg":27,"sprayAngleDeg":5,"hitDistanceFt":401,"stand":"R","baseState":0,"outs":1}'
#   expect 500 (pre-recovery)
```

## Step 1 - Back up the registry (mandatory before any registry write)

```bash
# SQLite registry backup (the demote + re-promote both write model_versions/model_routing):
TS=$(date -u +%Y%m%dT%H%M%SZ)
sudo install -d "/opt/bullpen/backups/registry/$TS"
sudo cp /opt/bullpen/data/registry.sqlite "/opt/bullpen/backups/registry/$TS/registry.sqlite"
ls -l "/opt/bullpen/backups/registry/$TS/"
```

No ClickHouse write happens in this recovery, so no CH snapshot is required (the
`block-destructive-ch` rule does not apply - we touch only SQLite + the snapshot
files on disk).

## Step 2 - Verify the v1 snapshot is complete (no snapshot change needed)

**The snapshot is already correct and complete.** All of the incident's
snapshot-side layers were resolved before this deploy; the remaining blocker was
reader CODE (`[152]`, the input-name fix shipping in this deploy), NOT a snapshot
defect. So this step is a read-only re-verify, not a fix - which is also why the
"fix before demote" ordering concern is moot (there is no snapshot fix to race the
503 window).

```bash
SNAP=/opt/bullpen/data/models/$M/v1
ls -l "$SNAP"   # model.onnx, model.onnx.data, meta.json, feature_pipeline.json, calibrator.json

# 2a. Calibrator is map-format ([149], re-keyed 2026-06-07):
jq '.schema_version' "$SNAP/calibrator.json"                 # expect 2

# 2b. External-weights sidecar present (the original C5 symptom; confirmed ~151 KB on the box):
test -s "$SNAP/model.onnx.data" && echo "sidecar OK ($(stat -c%s "$SNAP/model.onnx.data") bytes)" || echo "MISSING"

# 2c. model.onnx input name is "features" - the name the reader fix ([152]) now resolves and feeds.
#     This is NOT a snapshot defect (the model is correct); it is the proof that the reader, not the
#     model, was the third layer. The deployed [152] reads whatever this prints.
python3 -c "import onnx; print([i.name for i in onnx.load('$SNAP/model.onnx').graph.input])"  # expect ['features']
```

Expected: calibrator `schema_version` 2, `model.onnx.data` ~151 KB, model input
`['features']` - all confirmed during the incident.

> If (unexpectedly) a check fails - calibrator not v2, or the sidecar missing -
> THAT is a real snapshot fix, and the clean path is a re-stage from the Mac
> artifact (authoring stays on the Mac, ADR-0006). The `[149]` converter
> (`training.scripts.convert_calibrator_list_to_map`, in-place, pure re-key) is the
> box-mechanical unblock for the calibrator layer only. Per the incident's
> verification these are already done; do not re-key a calibrator that is already
> `schema_version` 2.

## Step 3 - Demote the stuck champion (INC-1, `[150]`)

This flips v1 `CHAMPION -> SHADOW` and removes its routing row in one transaction.
`/all-parks` goes to a clean 503 from here until step 4 - that is the bounded
window, which is why step 2 ran first.

```bash
curl -s -X POST "$BASE/v1/admin/registry/$M/promote/$VID" -u "$ADMIN" \
  -H 'Content-Type: application/json' \
  -d '{"targetStage":"shadow","reason":"INC-1 rollback of the unloadable first champion (incident 2026-06-07)"}' | jq

# verify: stage SHADOW, no champion, routing row gone:
curl -s "$BASE/v1/ops/registry/$M" | jq '.[] | {id, version, stage}'
curl -s -o /dev/null -w "all-parks now: %{http_code}\n" -X POST "$BASE/v1/predict/batted-ball/all-parks" \
  -H 'Content-Type: application/json' \
  -d '{"launchSpeedMph":102,"launchAngleDeg":27,"sprayAngleDeg":5,"hitDistanceFt":401,"stand":"R","baseState":0,"outs":1}'
#   expect 503 (clean "no champion") - NOT 500. If still 500, stop: the demote did not clear routing.
```

## Step 4 - Re-promote v1 to CHAMPION (INC-2 load gate is the proof)

The gate loads v1 + runs a forward pass **before** the transition. A green 200
here means the snapshot is genuinely fixed (it loaded + predicted via the serving
loader). A **422** means the snapshot is still broken - read the message, return
to step 2, v1 stays SHADOW (safe).

```bash
curl -s -w "\nHTTP %{http_code}\n" -X POST "$BASE/v1/admin/registry/$M/promote/$VID" -u "$ADMIN" \
  -H 'Content-Type: application/json' \
  -d '{"targetStage":"champion","reason":"clean recovery re-promote post snapshot fix (incident 2026-06-07)"}'
#   expect HTTP 200 + stage CHAMPION.  422 => snapshot still broken (gate did its job); fix + retry.
```

Bootstrap note: v1 is still the only version, so the rule-5 bootstrap exemption
applies - no `experiment_results` row is needed for this re-promote (INC-6).

## Step 5 - C5 end-to-end verification

```bash
# box-side:
curl -s -X POST "$BASE/v1/predict/batted-ball/all-parks" \
  -H 'Content-Type: application/json' \
  -d '{"launchSpeedMph":102,"launchAngleDeg":27,"sprayAngleDeg":5,"hitDistanceFt":401,"stand":"R","baseState":0,"outs":1}' \
  | jq '{modelName, modelVersion, parks: (.probHrByPark | length)}'
#   expect: HTTP 200, modelVersion "v1" (NOT the toy "v0"), parks == 30, finite probabilities.

# external confirm (the public path that 500'd):
curl -s -o /dev/null -w "public all-parks: %{http_code}\n" -X POST \
  https://api.thebullpen.net/v1/predict/batted-ball/all-parks \
  -H 'Content-Type: application/json' \
  -d '{"launchSpeedMph":102,"launchAngleDeg":27,"sprayAngleDeg":5,"hitDistanceFt":401,"stand":"R","baseState":0,"outs":1}'
#   expect 200.
```

`modelVersion: "v1"` (not `v0`) is the served-by signal: the registry champion is
serving, not the legacy toy fallback. Incident closed.

## Rollback of the recovery (if step 4/5 misbehave)

The recovery is itself rollback-able: demote v1 again (`targetStage: shadow`) to
return to the clean-503 state, restore the registry backup from step 1 if a write
went wrong (`cp /opt/bullpen/backups/registry/$TS/registry.sqlite /opt/bullpen/data/registry.sqlite`
then restart `bullpen-api`), and re-open the incident. No state here is
destructive or one-way.

## Post-recovery

- Flip the postmortem **Status** to `resolved` and tick the "Clean recovery"
  action-item row.
- The interim re-key (`convert_calibrator_list_to_map.py`) and this clean
  recovery converge on the same map calibrator; once a clean re-export +
  re-register lands, the converter script is a historical artifact.
- Follow-ons that did NOT ride this recovery deploy (tracked separately, by
  design - a schema change does not belong in an incident-recovery deploy):
  the `model_kind` registry column (explicit loader resolution vs the current
  `park_order` metadata sniff) and the INC-4 calibrator content-hash under rule 7.
