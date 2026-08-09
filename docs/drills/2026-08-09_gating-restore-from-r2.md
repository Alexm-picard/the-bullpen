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
| Re-run gate                   | Box re-runs restore-drill.sh --from-r2 after this PR merges                          | Pending         |
