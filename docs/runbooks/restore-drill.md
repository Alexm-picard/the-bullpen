# Restore drill — runbook

Run this any time you want re-verified evidence that a `clickhouse-backup`
snapshot survives a full create → restore round-trip into a scratch instance.
This is the CLAUDE.md rule 8 forcing function — untested backups don't count.

## Cadence

- **Mandatory:** before each MLB season begins (pre-season hardening).
- **Recommended:** after any change to `infra/backup/clickhouse-snapshot.sh`,
  the systemd timer, or the `data_path` / disk layout of the live ClickHouse
  container.
- **Recommended:** after upgrading ClickHouse or `clickhouse-backup`.
- **Not necessary:** for code changes that don't touch backups or storage.

## Pre-flight (no opt out)

1. `bullpen-clickhouse` container is running and healthy
   (`docker ps | grep bullpen-clickhouse`).
2. You are NOT in a live-game window (decision [21] — no service disruption
   during evenings April–October). The drill itself doesn't touch the live
   container's data, but it does INSERT + TRUNCATE on
   `bullpen._drill_marker` and creates + deletes a snapshot, so play it
   safe.
3. Pause the Uptime Robot monitor if you're paranoid — the drill won't take
   the API down (it doesn't touch the API or the worker), but the alerting
   chain is set up around prod traffic, so silence reduces noise.

## Run

```bash
bash infra/backup/restore-drill.sh
```

Expected runtime: 3–10 seconds. Exits 0 on PASS, 1 on FAIL.

Output (PASS case):

```
================================================================
RESTORE DRILL RESULT
================================================================
  backup:           drill_YYYYMMDDTHHMMSSZ
  live row count:   N
  scratch count:    N
  drill row found:  1
  schema match:     yes
================================================================
  RESULT: PASS
```

## On FAIL

Diagnose in this order:

1. **`backup has 0 data parts — FREEZE did not capture rows`** —
   `clickhouse-backup` ran the create but the snapshot is metadata-only. This
   is the bug fixed on 2026-05-23: `clickhouse-backup` must run inside the
   `bullpen-clickhouse` docker container (`docker exec`), not on the host.
   The drill script does this by default; check that you haven't accidentally
   reverted to the host-side invocation. Repro:
   `docker exec bullpen-clickhouse /usr/bin/clickhouse-backup create test_$(date +%s)` —
   if that produces `data:0B` summary, ClickHouse itself isn't FREEZE-ing
   correctly (rare; usually a permissions or volume-mount problem).
2. **`scratch did not become ready in 60s`** — image pull or port conflict.
   Check `docker logs bullpen-clickhouse-scratch`; check `ss -tlnp | grep
':\(18123\|19000\)'` for whoever's holding those ports.
3. **`restore command failed`** — restore couldn't recreate the database or
   attach the parts. Capture the inside-container log:
   `cat /tmp/restore-drill-restore.log`. Common cause: a schema change
   between live (newer) and scratch (older). The drill pins
   `clickhouse/clickhouse-server:24.12` — bump it if live moves forward.
4. **`live row count != scratch count`** or **drill row not found in
   scratch** — the data parts didn't attach cleanly. Look inside scratch
   manually: `docker exec -it bullpen-clickhouse-scratch clickhouse-client
--password "$BULLPEN_CLICKHOUSE_PASSWORD"`. Check `system.parts` for `_drill_marker` —
   active parts should match what was in the backup metadata. If the parts
   are there but the table count is 0, the ATTACH didn't run. If the parts
   are missing entirely, the tar transfer dropped them (rare; look at
   `docker exec bullpen-clickhouse-scratch ls /var/lib/clickhouse/backup/`).
5. **Cleanup leftover state** — the drill's `trap cleanup EXIT` removes the
   scratch container + network, but if you Ctrl+C between snapshot and
   teardown you may end up with an orphaned `drill_*` backup on live.
   Inspect with `docker exec bullpen-clickhouse /usr/bin/clickhouse-backup list`,
   delete with `docker exec bullpen-clickhouse /usr/bin/clickhouse-backup
delete local <backup-name>`.

If after that the drill still fails, do NOT call the system restorable.
Open a `severity/sev2` issue, page yourself, fix it. This is the bedrock
discipline — there's no point doing the rest of Phase 1+ on a foundation
that can't recover from a corrupted disk.

## What the drill proves

- `clickhouse-backup create` (inside the live container) captures real data
  parts, not just metadata.
- The on-disk backup format is self-contained: shipping just
  `/var/lib/clickhouse/backup/<name>/` to a new ClickHouse 24.12 instance
  is enough to reconstruct schema + data without any other state.
- The scratch ClickHouse can ATTACH the parts and serve them — the
  reconstructed table is queryable and matches the source.
- Schema parity: the table's `engine_full` matches between live and scratch.

## What the drill does NOT prove (yet)

- **R2 round-trip.** The current snapshot script writes locally only.
  Restore-from-R2 lands as a Phase 1 follow-up — extend the drill with a
  `--from-r2` flag that `rclone copy`s the backup down from
  `bullpen-r2:bullpen-prod/snapshots/clickhouse/<name>/` before the local
  restore.
- **Multi-table.** Today `bullpen._drill_marker` is the only user table.
  The mechanism is table-agnostic; extend the verify block to iterate
  over `system.tables` once real tables land.
- **SQLite registry.** The snapshot script copies it via `.backup`, but the
  registry is empty pre-Phase-1, so a restore proves nothing. Wire in once
  rows exist.

## Cross-references

- Script: `infra/backup/restore-drill.sh`
- Patched snapshot script: `infra/backup/clickhouse-snapshot.sh`
- Plan spec: `docs/plans/phase-0-foundation/0.10-backup-restore-drill.md`
- First successful drill: `docs/drills/2026-05-23_restore.md`
- Discipline rule: CLAUDE.md rule 8
- Decisions: [13], [14], [21], [128]; ADR-0007
