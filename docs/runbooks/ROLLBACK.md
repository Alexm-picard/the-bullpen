# Runbook — ROLLBACK (the 2am index)

**Owner:** alex · **Last reviewed:** 2026-05-30 · **Audience:** on-call you, half-asleep

This is the top-level rollback index. When something you just shipped made the
system worse, find the matching scenario, follow detection → decision → commands
→ verification. Each scenario links to the detailed runbook where one exists.

**Golden rules**

- **Never touch live ClickHouse without a snapshot first** (CLAUDE.md hard rule).
  The `block-destructive-ch` hook enforces it; manual ops must too.
- **Roll back state and code separately.** A bad deploy is not a bad migration is
  not a bad model promotion. Identify which layer regressed before acting.
- **Prod writes happen via `./deploy.sh` or admin API only** (ADR-0006). Don't
  hand-edit the WSL2 working copy to "fix it fast."
- Admin endpoints below need HTTP Basic (`THEBULLPEN_ADMIN_BASIC_AUTH`,
  `user:pass`). Examples use `-u "$ADMIN"`.

Quick chooser:

| Symptom                                                       | Scenario                                               |
| ------------------------------------------------------------- | ------------------------------------------------------ |
| Site down / 5xx / health red right after a deploy             | [1 — Deploy](#1--deploy-rollback)                      |
| Boot fails on a migration, or a schema change broke queries   | [2 — Migration](#2--migration-rollback)                |
| A newly promoted CHAMPION is serving worse predictions        | [3 — Model promotion](#3--model-promotion-rollback)    |
| A challenger/shadow or auto-retrain is misbehaving in routing | [4 — Routing / retrain](#4--routing--retrain-rollback) |

---

## 1 — Deploy rollback

**Detection signal**

- `./deploy.sh` already auto-rolled back (it swaps the symlink to the previous
  release and exits 1 if the 30s `/actuator/health` smoke fails). If you see
  that, the prior release is already live — go to Verification.
- Otherwise: external monitor (Uptime Robot) paged, `/health` or
  `/actuator/health` returns non-200, or p99/error-rate spiked post-deploy.

**Decision criteria**

- Regression is in the JAR (new code), not data/schema → roll the release back.
- If a migration ran as part of this deploy and the failure is schema-shaped, do
  [Scenario 2](#2--migration-rollback) first.

**Commands** (on the WSL2 prod box; releases live at `/opt/bullpen/releases/<TAG>/`)

```bash
# See the last few releases and which one is current.
ls -lt /opt/bullpen/releases | head
readlink -f /opt/bullpen/app.jar          # current target

# Fast path — repoint the symlink at the previous good release + restart.
PREV=/opt/bullpen/releases/<GOOD_TAG>/app.jar
sudo ln -snf "$PREV" /opt/bullpen/.app.jar.new
sudo mv -Tf /opt/bullpen/.app.jar.new /opt/bullpen/app.jar
sudo systemctl restart bullpen-api bullpen-worker
```

```bash
# Clean path — re-deploy a known-good tag from the Mac (rebuilds from git):
git checkout <GOOD_TAG> && ./deploy.sh         # prefer the deploy-safely skill
```

**Verification**

```bash
curl -fsS https://api.thebullpen.net/actuator/health    # expect {"status":"UP"}
systemctl is-active bullpen-api bullpen-worker           # both: active
```

Confirm the external monitor recovers and p99/error-rate return to baseline in
Grafana. Record what happened in `docs/deploys/<TAG>.md`.

---

## 2 — Migration rollback

Two stores migrate independently: **SQLite registry** (Flyway, on boot) and
**ClickHouse** (`ClickHouseMigrationRunner`, tracked in `_schema_migrations`).
Neither auto-undoes — rollback is forward-fix or restore.

**Detection signal**

- App fails to boot with a Flyway validation/checksum error, or a ClickHouse
  migration logs a failure on startup.
- A schema change shipped and queries/inserts now error or return wrong shapes.

**Decision criteria**

- **SQLite registry**: it is small and snapshotted. Prefer restoring the
  pre-migration `registry.sqlite` from the daily backup over hand-editing.
- **ClickHouse**: **snapshot first, always.** A forward-fix migration (a new
  `V00N` that corrects the bad one) is safer than a destructive undo.

**Commands**

```bash
# --- SQLite registry: restore the pre-change DB from the daily snapshot ---
sudo systemctl stop bullpen-api bullpen-worker
# locate the most recent good snapshot (see infra/backup/README.md)
cp /opt/bullpen/backups/registry/<DATE>/registry.sqlite /opt/bullpen/data/registry.sqlite
sudo systemctl start bullpen-api bullpen-worker
```

```bash
# --- ClickHouse: ALWAYS snapshot before any corrective DDL ---
infra/backup/clickhouse-snapshot.sh        # fail-loud; verify it captured data
# then ship a forward-fix V00N migration via deploy (do NOT hand-run DROP/ALTER).
```

A Flyway checksum mismatch on an _already-applied_ migration that you
intentionally changed: do not edit history — add a new migration, or
`flywayRepair` only with a snapshot taken and a clear understanding of why.

**Verification**

- App boots clean; `flyway_schema_history` (SQLite) / `_schema_migrations`
  (ClickHouse) show the expected versions.
- Spot-check the affected query/insert path returns correct results.

---

## 3 — Model promotion rollback

A version was promoted to CHAMPION (rule 5/6 gate) and is serving worse. Rollback
= promote the **previous** champion back, which archives the bad one. No model
bytes move; the registry just re-points.

See also: [registry-snapshot-recovery.md](registry-snapshot-recovery.md) if the
prior champion's artifacts were archived to R2 and need rehydrating first.

**Detection signal**

- Calibration/Brier degraded right after a promotion (see
  [calibration-drift-investigation.md](calibration-drift-investigation.md)),
  user-visible predictions look wrong, or the promotion was a mistake.

**Decision criteria**

- The regression correlates with the promotion timestamp (not a data shift). If
  it's a gradual data shift instead, that's drift — investigate, don't roll back
  a good model.

**Commands**

```bash
ADMIN="user:pass"   # THEBULLPEN_ADMIN_BASIC_AUTH
BASE=https://api.thebullpen.net

# 1. Find the prior champion's version id (full list incl. archived):
curl -s -u "$ADMIN" "$BASE/v1/admin/registry/pitch_outcome_pre" | jq '.[] | {id,version,stage,promoted_at}'

# 2. If its artifacts were archived to R2, rehydrate first (see runbook above):
#    registry-snapshot-recovery.md

# 3. Re-promote the prior good version to CHAMPION (archives the current one).
#    Body fields: targetStage + reason (both required).
curl -s -u "$ADMIN" -X POST \
  "$BASE/v1/admin/registry/pitch_outcome_pre/promote/<PRIOR_VERSION_ID>" \
  -H 'Content-Type: application/json' \
  -d '{"targetStage":"champion","reason":"rollback of bad promotion"}'
```

**Verification**

```bash
curl -s -u "$ADMIN" "$BASE/v1/admin/registry/pitch_outcome_pre" \
  | jq '.[] | select(.stage=="champion") | {id,version}'
```

Confirm the served `modelVersion` on `/v1/predict/pitch` matches the restored
champion, and that calibration recovers over the next daily `CalibrationJob`.

---

## 4 — Routing / retrain rollback

Retraining is automated but **promotion is human-gated** (rule 6), so a bad
auto-retrain can only reach users through routing (challenger / shadow / traffic
split). Rollback = neutralize the routing, not the model.

See also:
[retraining-failure-recovery.md](retraining-failure-recovery.md) (stuck/failed
retrain queue) and
[feature-drift-investigation.md](feature-drift-investigation.md) (what triggered
it).

**Detection signal**

- A challenger is taking live A/B traffic and serving worse, or shadow volume is
  overwhelming the prediction-log queue (drop counter climbing), or a retrain
  queued a candidate you don't trust.

**Decision criteria**

- If the challenger is **live** (taking real traffic) and bad → cut traffic now,
  ask questions after.
- If it's only **shadow** → no user impact; you can investigate calmly.

**Commands**

```bash
ADMIN="user:pass"; BASE=https://api.thebullpen.net; M=pitch_outcome_pre

# Fastest cut — drop the challenger's live traffic to 0 (body: pct + reason):
curl -s -u "$ADMIN" -X POST "$BASE/v1/admin/routing/$M/traffic-pct" \
  -H 'Content-Type: application/json' -d '{"pct":0,"reason":"rollback"}'

# Or force the whole model back to shadow-only mode (body: mode + reason):
curl -s -u "$ADMIN" -X POST "$BASE/v1/admin/routing/$M/mode" \
  -H 'Content-Type: application/json' -d '{"mode":"shadow","reason":"rollback"}'

# Or remove the challenger entirely (no body):
curl -s -u "$ADMIN" -X DELETE "$BASE/v1/admin/routing/$M/challenger"
```

A stuck/failed retrain in the queue: follow
[retraining-failure-recovery.md](retraining-failure-recovery.md) to mark it and
free the claim.

**Verification**

```bash
curl -s -u "$ADMIN" "$BASE/v1/admin/routing/$M" | jq '{mode,challengerVersionId,trafficPct}'
```

Confirm `/v1/predict/pitch` serves only the champion, and the prediction-log
drop counter (`thebullpen_prediction_log_dropped_total`) stops climbing.

---

## After any rollback

1. Note it in `docs/hardening/observations.md` (one line — it's sweep signal).
2. If the root cause changed a decision, append to `docs/decisions.md`.
3. If detection was slow or a command was wrong here, fix this runbook — it's the
   thing you'll read at 2am next time.
