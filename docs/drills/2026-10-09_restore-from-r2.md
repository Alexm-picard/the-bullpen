# Restore Drill - 2026-10-09 (gating restore-from-R2)

- **Operator:** Alex, on the box (WSL2 desktop); record committed from the Mac per ADR-0006
- **Type:** disaster-recovery restore-from-R2 with gating prediction probe
- **Script:** `infra/backup/restore-drill.sh --from-r2` at `37be0a8` (main)
- **Source:** `bullpen-r2:bullpen-prod/backups/auto_20261009T071454Z`
- **Status:** **PASS**
- **RTO-to-serving:** 212s (drill start to first 200 on predict)

## Evidence (verbatim from the box)

```
[2026-10-09T22:04:19Z] newest offsite snapshot: auto_20261009T071454Z
[2026-10-09T22:06:23Z] fetch verified: clickhouse.tar 3143188480B == remote, 32107 data parts in archive
[2026-10-09T22:06:51Z] scratch ready after 8s
[2026-10-09T22:07:19Z]   default.prediction_log: 495014 rows
[2026-10-09T22:07:19Z]   default.pitches: 8087871 rows
[2026-10-09T22:07:19Z]   default.pitches_live: 16378 rows
[2026-10-09T22:07:20Z]   default.drift_metrics: 8291 rows
[2026-10-09T22:07:20Z] registry integrity_check: ok
[2026-10-09T22:07:20Z] model_versions: scratch=11 live=11
[2026-10-09T22:07:21Z]   battedball_outcome/v2: restored (model.onnx 195165B)
[2026-10-09T22:07:23Z]   pitch_outcome_post/v1: restored (model.onnx 17420303B)
[2026-10-09T22:07:24Z]   pitch_outcome_pre/v2: restored (model.onnx 11645406B)
[2026-10-09T22:07:25Z]   pitch_type_pre/v1: restored (model.onnx 8860151B)
[2026-10-09T22:07:25Z] models: restored 4 champion(s) from R2 snapshots/
[2026-10-09T22:07:32Z]   api actuator UP after 7s
[2026-10-09T22:07:32Z]   api prediction probe: HTTP 200 (GATING: 200=pass, else=fail)
[2026-10-09T22:07:39Z]   worker actuator UP after 5s
[2026-10-09T22:07:49Z]   worker stable for 10s after UP

RESTORE DRILL RESULT (--from-r2)
  source:           bullpen-r2:bullpen-prod/backups/auto_20261009T071454Z
  clickhouse:        restored into scratch (core tables non-empty)
  registry:          integrity ok, model_versions in range (1..live)
  models:            champion artifacts restored from R2 snapshots
  api profile:       actuator UP, prediction probe 200 (GATING)
  worker profile:    actuator UP + stable 10s (no crash-loop)
  RTO-to-serving:    212s (drill start to first 200 on predict)
  RESULT: PASS
```

## Notes

- Second consecutive gating PASS from offsite state alone (first: 2026-08-09 re-run).
  All four champions restored from `snapshots/`, confirming the #427 promote-time R2 push
  keeps champion bytes offsite.
- RTO dropped from 1492s (08-09) to 212s. The fetch is the lever: the 3.1 GB tar arrived
  in ~2 minutes this time versus ~23 minutes in August, so download bandwidth, not the
  software path, still dominates recovery time.
- The drill ran ~2h before a live game (dev-phase waiver of rule 3); it touches only
  scratch containers and a scratch API on :18080/:18081.
- Operator note: `/etc/default/bullpen` is root-readable only; load it with
  `set -a; source <(sudo cat /etc/default/bullpen); set +a` before running the drill.
