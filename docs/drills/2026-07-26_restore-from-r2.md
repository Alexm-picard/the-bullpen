# Restore Drill - 2026-07-26 (restore-FROM-R2, rule-8 closure)

- **Operator:** TD session on the box
- **Type:** disaster-recovery restore-from-R2 (CLAUDE.md rule 8, ADR-0007 P2 offsite leg)
- **Host:** WSL2 desktop, off-window (0 live games, 07:59-08:21 EDT)
- **Harness:** `infra/backup/restore-drill.sh --from-r2` at `df0d927`
- **Source:** `bullpen-r2:bullpen-prod/backups/auto_20260726T070435Z`
- **Duration:** ~21 min (11:59:45Z -> 12:21:03Z), ~20 min of it the R2 fetch
- **Status:** **PASS** - supersedes [`2026-06-15_restore.md`](2026-06-15_restore.md)
- **Recorded:** 2026-08-05, ten days after the run - see "Why this landed late" below.

## Restored (verified non-empty)

| table                    | rows      |
| ------------------------ | --------- |
| `default.pitches`        | 7,672,294 |
| `default.prediction_log` | 76,944    |
| `default.pitches_live`   | 35,746    |
| `default.drift_metrics`  | 825       |

15 tables; SQLite registry integrity ok, 9 `model_versions` rows in range.
Both profiles booted: api actuator UP, worker UP + stable 10s, no crash-loop.
No memory pressure (a low-memory watchdog armed for the run never fired).

## FINDING - data-complete, not serving-capable

The drill's own probe recorded `api prediction probe: HTTP 503`. The restore returns every row and a
registry pointing at champions whose ONNX bytes are archived SEPARATELY in R2 (`models-archive/`,
per ADR-0007) and are not materialised by this drill. So measured RTO is ~21 minutes to DATA; RTO to
a SERVING system is unmeasured and includes an operator following
[`docs/runbooks/registry-snapshot-recovery.md`](../runbooks/registry-snapshot-recovery.md).

Second-order: the probe is NON-GATING and its comment enumerates only 200 and 404 as expected. It
received 503 - outside that set - and the drill still reported PASS. As written this drill cannot
fail on a non-serving restore.

## The DR floor is reproducible, not lost

Worth stating explicitly, because the finding above reads worse than the situation is. The recovery
ladder has three rungs, and only the top one is undrilled:

1. **Artifacts present locally** - normal operation, no recovery needed.
2. **Artifacts archived in R2** under `models-archive/<model_name>/<version>/`, pushed at
   registration. `ModelLoader` rejects `s3://` paths deliberately, with an error naming the
   pull-back runbook, so this is a designed manual step rather than a missing capability. This is
   the rung this drill does not exercise.
3. **Archive prefix gone entirely** - the runbook's own documented answer is "register a fresh
   version from the Python training artifacts". Training code and fold data are in git and R2, so
   the floor is retrain-and-reregister: slow, but reproducible rather than unrecoverable.

## Why this landed late

The drill ran 2026-07-26 and this record is committed 2026-08-05. The record was drafted on the box
and relayed for Mac-side commit per ADR-0006 (the box authors nothing in git), and the handoff was
dropped. Nothing noticed for ten days, and `docs/drills/` read as 51 days stale against a 30-day
guidance while a passing drill sat unrecorded.

That is worse than a missed drill, because the record IS the artifact rule 8 is about: an untested
backup and an untested-LOOKING backup are indistinguishable to anyone auditing. It also exposes the
weakness in the relay pattern - box-produced evidence has no tracking, so a dropped handoff is
silent. The scratchpad draft was subsequently cleared, so this text survives only because it was
reproduced in the handoff conversation: a relay that lived in exactly one place.

Mechanism, not vigilance: rule 8 is now self-policing against the newest file in this directory (see
the drill-freshness check added alongside this record), so an unrecorded drill surfaces as an
incomplete one rather than as a completed one with missing paperwork.

## Action items

1. ~~Extend the drill to exercise the R2 `models-archive` pull-back.~~ **[DEV] DONE** -
   `restore_model_artifacts` rclone-downloads champion ONNX from `models-archive/<name>/<version>/`
   into the local paths the registry points at. Also wired: admin restore endpoint
   (`POST /v1/admin/registry/{name}/restore/{id}`) and `RestoreVersionMain` CLI entry point
   (`-Dloader.main=...`, PropertiesLauncher configured in `build.gradle.kts`).
2. ~~Make the prediction probe gating: 200 = pass, 404/503 = fail.~~ **[DEV] DONE** -
   `boot_profile` third arg `gating` makes the probe fatal. The `run_r2_drill` flow calls it
   after model restore, so the probe tests the full recovery path.
3. Re-run and record measured RTO-to-serving. **[TD]** - the drill now emits `RTO-to-serving`
   in the result summary (drill start to first 200 on predict).
4. ~~SLO alerts + watchdog dead-man.~~ **[DEV] DONE** - new `slo` rule group in
   `bullpen-alerts.yml`: `PredictionLatencySLOBreach` (p99 > 100ms, 2x the 50ms SLO),
   `InferenceErrorRateHigh` (champion error rate > 5%, traffic-gated),
   `ApiServerErrorRate` (HTTP 5xx > 5%, traffic-gated), `Watchdog` (dead-man's switch,
   `vector(1)` always-fires). Behavioural tests in `slo-alerts.test.yml`, wired into
   `infra.yml`'s promtool lane. Total rule count: 28 (was 24).
