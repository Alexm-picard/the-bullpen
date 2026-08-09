# Restore Drill - 2026-08-09 (gating restore-from-R2, Part C)

- **Operator:** TD session on the box
- **Type:** disaster-recovery restore-from-R2 with gating prediction probe
- **Host:** WSL2 desktop
- **Source:** `bullpen-r2:bullpen-prod/backups/auto_20260809T070935Z`
- **Status:** **FAIL** (by design: first run of the gating probe)

## Evidence (verbatim from the box)

```
[2026-08-09T08:34:19Z] newest offsite snapshot: auto_20260809T070935Z
[2026-08-09T08:55:37Z] fetch verified: clickhouse.tar 3041464320B == remote, 31877 data parts
[2026-08-09T08:56:41Z]   default.pitches: 7925701 rows
[2026-08-09T08:56:41Z]   default.prediction_log: 138676 rows
[2026-08-09T08:56:42Z] registry restore verified (11 model_versions rows)
[2026-08-09T08:56:42Z]   no CHAMPION versions in scratch registry - model restore skipped (probe will 503)
[2026-08-09T08:56:50Z]   api actuator UP after 9s
[2026-08-09T08:56:51Z] FAIL: prediction probe returned HTTP 503 after model restore - system is not serving
```

## Findings

### 1. Case bug in champion query (Task 1)

`restore_model_artifacts()` queried `WHERE stage='CHAMPION'` but the registry
stores stages lowercase (`CHECK (stage IN ('candidate','shadow','champion','archived'))`
in V001). SQLite string comparison is case-sensitive, so the query matched 0 of
4 champions and the restore stage silently skipped. The gating probe correctly
caught the result (503).

### 2. R2 layout mismatch (Task 2)

The drill derived the archive prefix as `models-archive/` but the real bundles
live under `snapshots/<model_name>/<version>/` (ADR-0007's documented layout).
`models-archive/` has zero objects, ever. Three sites named the prefix
independently (drill script, runbook, SnapshotStorage default), which is how
they diverged.

### 3. Two of four champions had no bytes in R2 (Task 3)

`battedball_outcome/v2` and `pitch_type_pre/v1` had no objects anywhere in R2.
They were pushed manually same-day (rclone check --one-way verified, 0
differences, 4 and 15 files respectively). All four champions now have complete
bundles under `snapshots/`. Root cause: the retention sweep deliberately never
archives champions (SnapshotStorage), and nothing else pushed bytes to R2. The
two bundles that WERE in R2 got there via one-off manual pushes.

**A real box loss would have lost the /parks champion and pitch_type_pre
unrecoverably (local + USB only).**

## Framing

The 07-26 drill "passed" because its probe could not fail: it checked 200 and
404 as expected, received 503 (outside that set), and still reported PASS. The
first drill that COULD fail, did, and found a real recovery gap. The gating
probe is working as designed.

## Remediation

| Item                          | Fix                                                                                  | Status          |
| ----------------------------- | ------------------------------------------------------------------------------------ | --------------- |
| Case bug                      | `stage='champion'` + fail-loud when champions exist but query returns none           | This PR         |
| Layout mismatch               | `models-archive` -> `snapshots` everywhere (drill, runbook, SnapshotStorage default) | This PR         |
| Missing R2 bundles (instance) | Manual push of battedball_outcome/v2 + pitch_type_pre/v1 to R2                       | Done 2026-08-09 |
| Missing R2 bundles (class)    | Push champion bundle to R2 at promote time (RegistryService)                         | This PR         |
| Re-run gate                   | Box re-runs restore-drill.sh --from-r2 after this PR merges                          | **PASS** 15:01Z |

## Re-run: PASS (2026-08-09, post-#427)

- **Script:** `restore-drill.sh --from-r2` at `134bb3b` (post-#427 fixes)
- **Source:** same snapshot `auto_20260809T070935Z`
- **RTO-to-serving:** 1492s (~25 min), fetch-dominated (~23 min for the 3.0 GB tar;
  restore-to-first-200 is ~90s once bytes are local)
- **Status:** **PASS** - first end-to-end DR proof from offsite state alone

### Evidence (verbatim from the box)

```
[15:00:30Z] registry restore verified (11 model_versions rows)
[15:00:31Z]   battedball_outcome/v2: restored (model.onnx 195165B)
[15:00:50Z]   pitch_outcome_post/v1: restored (model.onnx 17420303B)
[15:01:06Z]   pitch_outcome_pre/v2: restored (model.onnx 11645406B)
[15:01:31Z]   pitch_type_pre/v1: restored (model.onnx 8860151B)
[15:01:31Z] models: restored 4 champion(s) from R2 snapshots/
[15:01:36Z]   api prediction probe: HTTP 200 (GATING: 200=pass, else=fail)
[15:01:53Z]   worker stable for 10s after UP
RTO-to-serving: 1492s   RESULT: PASS
```

All four champion bundles pulled from `snapshots/`, api prediction probe 200, worker
UP + stable. The recovery-time lever is download bandwidth, not the software path. The
W-DR verification criterion ("restore drill boots readiness-UP with a measured RTO") is
met with evidence.
