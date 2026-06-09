# H6 - Backup R2 verification and pre-season drills

> **Scope:** two things in one checklist:
> 1. Confirm that `clickhouse-snapshot.sh` (Layer 1) actually pushes to
>    Cloudflare R2 - this R2 push path was flagged as a known gap in the
>    2026-05-23 restore drill (the snapshot script writes local-only; the R2
>    upload step is a follow-up that was deferred to Phase 1).
> 2. Run the pre-season restore drill + reboot drill (CLAUDE.md rule 8).
>
> **Why this exists:** rule 8 says restore and reboot drills must run before
> the season starts. A backup that has never been restore-tested is not a
> backup (decision [14]). The R2 verification is a separate but related gate:
> if the box SSD dies, Layer 1 snapshots (local only) are useless - the R2
> push is the off-machine copy that makes Layer 1 durable.
>
> **References:** `infra/backup/README.md` (backup architecture, both layers),
> `infra/backup/clickhouse-snapshot.sh`, `ops/scripts/restore-drill.sh`,
> `docs/drills/2026-05-23_restore.md` (last drill - PASS, local-only),
> ADR-0007 (S3-compatible storage abstraction), decisions [13] [14] [128].

---

## Part A - R2 push verification

### A1 - Confirm the gap still exists

As of the 2026-05-23 restore drill, `clickhouse-snapshot.sh` sets
`remote_storage: none` in its clickhouse-backup config inside the container.
There is no `rclone copy` call in the script. Verify this is still the case:

```bash
# On the box
grep -n "remote_storage\|rclone\|R2\|r2\|bullpen-r2" \
  ~/code/the-bullpen/infra/backup/clickhouse-snapshot.sh
```

If the output shows `remote_storage: none` and no `rclone` lines, the gap is
still open. Proceed to A2 to close it. If `rclone` lines are present and the
script already uploads, skip to A3 to verify the R2 destination.

### A2 - Add the R2 upload step (on the Mac, not the box)

This step is a Mac-side code change per ADR-0006. The operator on the box cannot
author this.

The pattern is a `rclone copy` call appended after the `clickhouse-backup create`
block in `infra/backup/clickhouse-snapshot.sh`:

```bash
# Pattern (to be written by the Mac author, not run here directly):
# rclone copy \
#   /var/lib/clickhouse/backup/${NAME}/ \
#   bullpen-r2:bullpen-prod/snapshots/clickhouse/${NAME}/ \
#   --config ~/.config/rclone/rclone.conf
```

The R2 bucket is `bullpen-prod`. The snapshot prefix in R2 is
`snapshots/clickhouse/<backup-name>/`. This matches the bucket layout in
ADR-0007 (`snapshots/v{N}/` for model artifacts; clickhouse snapshots go
under `snapshots/clickhouse/`).

After the Mac author merges and deploys the change, confirm it works via A3.

### A3 - Verify an R2 upload succeeds (after the code change is deployed)

Run a manual snapshot and confirm the backup lands in R2:

```bash
# On the box
infra/backup/clickhouse-snapshot.sh

# Then verify the backup appears in R2
rclone lsd bullpen-r2:bullpen-prod/snapshots/clickhouse/ \
  --config ~/.config/rclone/rclone.conf
# Expect an entry for the backup name you just created (auto_<timestamp>)

# Spot-check one file landed
rclone ls bullpen-r2:bullpen-prod/snapshots/clickhouse/ \
  --config ~/.config/rclone/rclone.conf | head -10
```

Also confirm the existing three R2 prefixes from the reboot drill still exist:

```bash
rclone lsd bullpen-r2:bullpen-prod --config ~/.config/rclone/rclone.conf
# Expect: raw/  samples/  snapshots/
```

### A4 - Verify the restore-from-R2 path (extend restore-drill.sh)

The 2026-05-23 drill explicitly noted "R2 round-trip not proven" as a follow-up.
Once A2/A3 are done, extend `ops/scripts/restore-drill.sh` with a `--from-r2`
mode on the Mac, deploy, and run:

```bash
# On the box (after the --from-r2 mode is merged and deployed)
bash ops/scripts/restore-drill.sh --from-r2
# Should: rclone copy the latest snapshot from R2, then run the local restore loop
```

The local restore procedure inside `--from-r2` is identical to the existing
`restore-drill.sh` steps (create scratch container, transfer backup, restore,
verify counts). The only addition is the `rclone copy` download before step 3.

---

## Part B - Pre-season restore drill

Run this on the box once per pre-season (before the first game of the MLB season).
The passing drill from 2026-05-23 proves the local mechanism works. Run it again
each season to catch any state drift.

### B1 - Pre-flight

```bash
# Layer-1 snapshot first (hard rule)
infra/backup/clickhouse-snapshot.sh
ls -lh /var/lib/clickhouse-backup/.last_snapshot_ok
# mtime should be today

# Both services active
systemctl is-active bullpen-api bullpen-worker
# Both must print 'active'

# ClickHouse container healthy
docker ps --format '{{.Names}}\t{{.Status}}' | grep bullpen-clickhouse
# Expect: Up ... (healthy)
```

### B2 - Run the restore drill

```bash
cd ~/code/the-bullpen
bash ops/scripts/restore-drill.sh
```

Expected output (last block):

```
================================================================
RESTORE DRILL RESULT
================================================================
  backup:           drill_<timestamp>
  live row count:   <N>
  scratch count:    <N>
  drill row found:  1
  schema match:     yes
================================================================
  RESULT: PASS
```

If RESULT is FAIL:

- Check the drill log for which step failed (snapshot create, scratch spin,
  transfer, restore, or verify).
- The most common past failure was `data: 0B` in the backup - caused by running
  `clickhouse-backup` on the host instead of inside the container. The
  `clickhouse-snapshot.sh` fix (2026-05-23) now runs it inside the container
  via `docker exec`. Confirm the script is still doing this:
  ```bash
  grep "docker exec" ~/code/the-bullpen/infra/backup/clickhouse-snapshot.sh | head -5
  ```
- A second possible cause: the `OPTIMIZE TABLE bullpen._drill_marker FINAL`
  step in the drill may time out if the table has many unmerged parts. Give it
  up to 60 seconds before investigating.

### B3 - Extended verify: check the real production tables

Once `pitches` and `prediction_log` are populated (Phase 1+ data), add per-table
row-count verification to the drill output. The `restore-drill.sh` mechanism is
table-agnostic - extend the verify step to spot-check:

```bash
# On the box, after a real production restore-drill pass
docker exec bullpen-clickhouse clickhouse-client --password thebullpen \
  --query "SELECT toYear(game_date) y, count() FROM pitches GROUP BY y ORDER BY y"
# Compare against the known row counts per season

docker exec bullpen-clickhouse clickhouse-client --password thebullpen \
  --query "SELECT count() FROM prediction_log"
```

### B4 - SQLite registry leg

The 2026-05-23 drill notes the SQLite restore leg was skipped (empty registry).
Once the registry has real rows, extend the drill to restore and spot-check:

```bash
# On the box (after models are registered)
sqlite3 /var/lib/clickhouse-backup/<latest-snapshot>_sqlite/registry.sqlite \
  "SELECT model_name, status, registered_at FROM models ORDER BY registered_at DESC LIMIT 5"
# Should show the same rows as the live registry:
sqlite3 ~/code/the-bullpen/backend/data/registry.sqlite \
  "SELECT model_name, status, registered_at FROM models ORDER BY registered_at DESC LIMIT 5"
```

### B5 - Record the drill result

After a PASS, write the drill report to `docs/drills/` and update
`docs/phase-status.json`:

```bash
# On the box: capture the output and bring it back to the Mac for commit (ADR-0006)
bash ops/scripts/restore-drill.sh 2>&1 | tee /tmp/restore-drill-$(date +%Y-%m-%d).log
# scp the log to Mac, then commit to docs/drills/<date>_restore.md
```

Update `docs/phase-status.json` field `drills.restore_drill.last_run` to today's
date (Mac-side commit).

---

## Part C - Pre-season reboot drill

The reboot drill proves WSL2 + systemd bring the full stack back without manual
intervention after a cold boot (CLAUDE.md rule 8). The 2026-05-22/23 drill passed
in 3 minutes from terminal open.

### C1 - Pre-shutdown state capture

Before shutting down, capture the running state:

```bash
# On the box
echo "=== systemd units ==="
systemctl is-active bullpen-api bullpen-worker cloudflared docker bullpen-snapshot.timer

echo "=== listening ports ==="
ss -tlnp | grep -E "8080|8081|8123|9000|9090|3000"

echo "=== ClickHouse row counts ==="
docker exec bullpen-clickhouse clickhouse-client --password thebullpen \
  --query "SELECT table, count() FROM system.parts WHERE active AND database='bullpen' GROUP BY table"

echo "=== API health ==="
curl -sf http://localhost:8080/actuator/health

echo "=== tunnel (external) ==="
curl -sSI https://api.thebullpen.net/actuator/health | head -3

echo "=== R2 reachable ==="
rclone lsd bullpen-r2:bullpen-prod --config ~/.config/rclone/rclone.conf
```

Mute the Better Stack monitor before shutdown to suppress alert noise.

### C2 - Shutdown

```bash
# From Windows PowerShell (not WSL2) - initiates a clean WSL2 shutdown:
wsl --shutdown
# Or perform a full Windows restart/shutdown.
```

### C3 - On-boot verification (from WSL2 terminal after restart)

Open a WSL2 terminal to start the distro, then run:

```bash
# 1. systemd PID 1
ps -o pid,comm -p 1
# Expect: 1 systemd

# 2. All bullpen units active
systemctl is-active bullpen-api bullpen-worker cloudflared docker bullpen-snapshot.timer
# All must print 'active'

# 3. Docker containers up
docker ps --format '{{.Names}}\t{{.Status}}' | grep bullpen
# Expect bullpen-clickhouse (healthy), bullpen-prometheus, bullpen-grafana

# 4. App health (local)
curl -sf http://localhost:8080/actuator/health
# Expect: {"status":"UP",...}

# 5. ClickHouse DataSource wired (both profiles - the 2026-06-04 incident canary)
journalctl -u bullpen-api    -n 60 --no-pager | grep -i "ClickHouse DataSource ready"
journalctl -u bullpen-worker -n 60 --no-pager | grep -i "ClickHouse DataSource ready"
# Both must show the line; a missing worker line means BULLPEN_CLICKHOUSE_ENABLED is not set

# 6. Tunnel + external health
curl -sSI https://api.thebullpen.net/actuator/health | head -5
# Expect: HTTP/2 200

# 7. R2 reachable
rclone lsd bullpen-r2:bullpen-prod --config ~/.config/rclone/rclone.conf
# Expect: raw/  samples/  snapshots/

# 8. Snapshot timer catch-up (Persistent=true)
systemctl list-timers bullpen-snapshot.timer
# Expect a NEXT time; if LAST was missed, Persistent=true queues a catch-up

# 9. Worker not crash-looping
systemctl show bullpen-worker -p NRestarts
# Should be a small, stable number; a climbing NRestarts means the worker is looping
# (most likely cause: BULLPEN_CLICKHOUSE_ENABLED missing in /etc/default/bullpen)
```

**Pass criteria:** items 1-9 all green within 5 minutes of opening the WSL2
terminal. Item 9 (Better Stack monitor) is a UI follow-up; the external probe in
item 6 already proves the public path.

### C4 - If the worker is crash-looping after boot

The 2026-06-04 incident showed the worker crash-loops with ~10s restart intervals
when `BULLPEN_CLICKHOUSE_ENABLED` is missing from `/etc/default/bullpen`. The
`api` profile survives this (it soft-degrades), but the worker hard-fails.

```bash
# Confirm the env file has the required vars
grep "BULLPEN_CLICKHOUSE_ENABLED\|THEBULLPEN_ADMIN_BASIC_AUTH\|S3_ENDPOINT_URL" \
  /etc/default/bullpen
```

If any required variable is missing, add it and restart. Full list of required
variables is in `docs/runbooks/desktop-environment.md`.

### C5 - Record the drill result

After a PASS, write the drill report to `docs/drills/` (on the Mac, from the
captured state above) and update `docs/phase-status.json` field
`drills.reboot_drill.last_run` to today's date.

---

## Summary checklist (pre-season go/no-go)

| # | Check | Done |
| --- | --- | --- |
| A3 | R2 upload confirmed in `rclone lsd` output | [ ] |
| A4 | R2 round-trip restore proves `--from-r2` (after code lands) | [ ] |
| B2 | Local restore drill: RESULT PASS | [ ] |
| B4 | SQLite registry leg: row counts match live | [ ] |
| B5 | Drill report committed to `docs/drills/` and `phase-status.json` updated | [ ] |
| C3 | Reboot drill: all 9 items green within 5 min | [ ] |
| C5 | Reboot report committed | [ ] |

A "no" on any row is a drill failure. Do not proceed to season until all rows
are checked. Cuts made here are surgical; cuts made mid-season are amputations.

---

## When this runbook should change

- R2 upload is merged to `clickhouse-snapshot.sh`: update Part A to mark A2
  as "already done" and make A3 the first live check.
- `restore-drill.sh` gains `--from-r2` mode: update A4 from "future" to a
  real command.
- Real production tables (`pitches`, `prediction_log`) have rows: extend B3
  with the actual expected row counts from the last known-good state.
- The Better Stack monitor URL changes (decision [129] moved from original
  uptime monitor to Better Stack): update C1's mute instruction.
