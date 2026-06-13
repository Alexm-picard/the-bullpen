# Restore Drill - 2026-06-13 (restore-FROM-R2 - FAIL, restore path not backup)

- **Operator:** alepic (derpthund@gmail.com)
- **Drill type:** disaster-recovery restore-from-R2 (CLAUDE.md rule 8), first run of the
  `ops/scripts/restore-drill.sh --from-r2` mode shipped in #74
- **Host:** the WSL2 desktop, off-window (no live games), 8.3 GiB free, sprint `v2026.06.13-1445` deployed
- **Status:** **FAIL** - and a valid one: the offsite backup is complete and good, but it could not be
  restored from R2. Rule 8 stays **OPEN** until the fix below lands and a clean re-run passes.
- **Supersedes for rule-8 purposes:** the 2026-05-23 local-only drill (retired as INVALID - it never
  restored from offsite, never restored the registry, never booted the worker)

## Result

The drill aborted at its pre-restore data-parts check (`rc=1`) after a ~29-minute fetch, before ever
calling `clickhouse-backup restore`. It is **not** a bad-backup failure: it is a restore-path failure
plus a heuristic that misattributed it. This is exactly the failure class rule 8 exists to surface -
a backup you cannot restore is not yet a backup.

## What ran

1. Pulled #73/#74 (script + test + docs; no deploy needed). Confirmed `--from-r2` present.
2. `--dry-run` first (the Mac had verified it): plan correct and well-isolated - scratch ClickHouse on
   `bullpen-drill-net` ports 18123/19000, scratch app on 18080/18081, scratch registry in `/tmp`,
   trap teardown - **zero contact** with live ports / containers / registry. `BULLPEN_OFFSITE_REMOTE`
   lives in `/etc/default/bullpen` (systemd env, not the interactive shell); exported it for the run.
3. Real run, backgrounded with a progress + memory monitor.

## Diagnosis (the backup is good; the restore is not)

- **The offsite backup is valid and complete.** R2 holds the night's set: 2.35 GiB, **64,463 objects**.
  Confirmed present: **223 compact-format `data.bin`** part files (e.g. `pitches_live`,
  `bbip_retrodicted_labels`, `_schema_migrations`) **plus 29,494 wide-format per-column `.bin`** files
  (one `.bin` per column, the format ClickHouse uses once a table grows). The 03:03 nightly logged
  `data parts captured: 223, DONE`.
- **The fetched copy had zero `data.bin`.** The full 64,000-tiny-object `rclone copy` back from R2 did
  not faithfully reproduce the backup, so the drill's `find -name data.bin` check found nothing and
  aborted.
- **A one-table fetch is flawless.** Re-fetching a single compact part (`rclone copy` exit 0, 0 errors,
  `data.bin` + all 11 part files land). So the mechanism is sound at small scale and unreliable at
  64k-object scale - pulling a ClickHouse backup as tens of thousands of individual tiny objects from
  R2 does not reliably round-trip.
- **The SQLite registry leg PASSED.** `.restore` + `integrity_check` + `model_versions` count vs live
  round-tripped perfectly (registry = 7 models after the PRE v2 SHADOW registration). The registry -
  the P1-irreplaceable piece - restores cleanly.

## Two defects (both Mac-side, per ADR-0006)

1. **Restore reliability (root cause):** the offsite ClickHouse backup is stored as ~64k individual
   objects; fetching them all back is unreliable. **Fix:** store the ClickHouse backup as a **single
   tar object** in `infra/backup/offsite-push.sh` so a restore is one download, not 64k.
2. **Drill heuristic:** `restore-drill.sh` declared "0 data parts" without first verifying the fetch
   was complete, and its `find -name data.bin` only matches **compact**-format parts (it misses
   wide-format per-column `.bin`). **Fix:** add a fail-loud post-fetch completeness check (exact size
   match against the remote, now cheap with one object) and make the part check format-agnostic
   (`*.bin`). The same `data.bin` heuristic in `clickhouse-snapshot.sh` is corrected in lockstep.

## Status / next

- Rule 8 stays **OPEN** with a concrete blocker and a concrete fix, comfortably ahead of the ~June 22
  clock.
- Once the fix lands and a fresh (tarred) offsite set exists, re-run `restore-drill.sh --from-r2` to a
  clean PASS, then supersede this report with the PASS evidence.
- This is the third operating-phase finding where running the real thing exposed a gap code review
  did not (the dsla epoch poison, the v0.27 Alertmanager floor, now the 64k-object restore).
