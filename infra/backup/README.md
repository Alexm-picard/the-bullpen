# Backup runbook

Three layers of backup for The Bullpen. They serve different failure modes — all are needed:
local snapshot (Layer 1, fast restores), air-gapped USB (Layer 2, host-loss), offsite R2
(Layer 3, site-loss).

## Layer 1 — Daily automated snapshot (`clickhouse-snapshot.sh`)

**Protects against**: accidental DROP/TRUNCATE, schema migration gone wrong, app bug that corrupts data.

**Lives on**: the WSL2 host itself, at `$SNAPSHOT_DIR` (default `/var/lib/clickhouse-backup`).

**Runs**: daily at 03:00 local via `bullpen-snapshot@<user>.timer` (template timer; the instance is the operator user).

**Captures**:

- ClickHouse snapshot (via `clickhouse-backup create` if installed, else `ALTER TABLE FREEZE` fallback)
- SQLite registry (via `sqlite3 .backup`)
- Training artifact _metadata_ (paths, sha256 of large files — NOT the ONNX/Parquet bytes themselves)

**Retention**: last 14 days. Older snapshots auto-deleted.

**Touches `.last_snapshot_ok`** — the destructive-ClickHouse Claude hook checks this file's mtime before allowing DROPs.

### Install on WSL2

```bash
# 1. Install clickhouse-backup (recommended)
wget https://github.com/Altinity/clickhouse-backup/releases/latest/download/clickhouse-backup_amd64.deb
sudo dpkg -i clickhouse-backup_amd64.deb

# 2. Drop the systemd units (note: %i template variable — use your username)
sudo cp "infra/backup/bullpen-snapshot.service" /etc/systemd/system/bullpen-snapshot@.service
sudo cp "infra/backup/bullpen-snapshot@.timer" /etc/systemd/system/bullpen-snapshot@.timer

# 3. Create the env file (KEEP THIS chmod 600 — contains the Discord webhook URL)
sudo tee /etc/default/bullpen >/dev/null <<EOF
BULLPEN_DISCORD_WEBHOOK="https://discord.com/api/webhooks/...your-webhook..."
REPO_ROOT="/home/$(whoami)/code/the-bullpen"
RETAIN_DAYS="14"
EOF
sudo chmod 600 /etc/default/bullpen

# 4. Enable + start the timer
sudo systemctl daemon-reload
sudo systemctl enable --now "bullpen-snapshot@$(whoami).timer"

# 5. Test it once manually
sudo systemctl start "bullpen-snapshot@$(whoami).service"
sudo journalctl -u "bullpen-snapshot@$(whoami).service" --since today

# 6. Verify retention
ls -lh /var/lib/clickhouse-backup/
```

## Layer 2 — Air-gapped USB backup (`usb-backup.sh`)

**Protects against**: WSL2 corruption, SSD failure, ransomware (when drive is unplugged), Windows update wiping the WSL2 distro, the desktop dying entirely.

**Lives on**: external USB drive labeled `BULLPEN_BAK` (11-char label keeps it valid on exFAT too).

**Runs**: manually, when _you_ decide. Recommended cadence:

- **Weekly** during build months (Phase 0–4)
- **Before any disruptive change**: Windows feature update, WSL2 distro change, driver install, hardware swap
- **Daily** during the live season (Phase 5)
- **Before any destructive ClickHouse operation** (in addition to the daily snapshot — defense in depth)

**Captures**:

- Most-recent daily snapshot from Layer 1
- Live SQLite registry
- **Training artifacts (the bytes)** — ONNX + Parquet + metadata. These can't be regenerated quickly.
- `/contracts/` — the Python↔Java boundary, small but critical
- `/docs/` — planning + drills + postmortems

**Does NOT capture**: source code (it's in git), `node_modules`, build outputs.

### One-time setup

```bash
# 1. Format the drive (ext4 if Linux-only; exFAT for cross-platform).
#    NOTE: exFAT volume labels are capped at 11 chars — `BULLPEN_BAK` fits both.
lsblk                                          # find the device, e.g. /dev/sdb
sudo mkfs.ext4  -L BULLPEN_BAK /dev/sdb1       # or:
sudo mkfs.exfat -L BULLPEN_BAK /dev/sdb1

# 1a. WSL2 only: the drive must be attached as a raw block device, not Windows-auto-mounted
#     at /mnt/d. From PowerShell (Admin) on Windows:
#       Get-Disk                                  # find the disk Number (USB, ~1 TB)
#       Set-Disk -Number <N> -IsOffline $true     # release it from Windows
#       wsl --mount \\.\PHYSICALDRIVE<N> --bare   # attach raw to WSL2
#     Then from WSL2, lsblk shows the new device (e.g. /dev/sde) and you can mkfs.

# 2. Install the narrow sudoers rule (NOPASSWD for this one script only)
./infra/backup/install-sudoers.sh

# 3. Run the backup
./infra/backup/usb-backup.sh                   # no password prompt after step 2

# 4. Label the physical drive with a tape label so you can find it in a panic.
```

### WSL2 + exFAT gotchas (read once, save tears)

The stock WSL2 kernel (`CONFIG_EXFAT_FS is not set` as of kernel 6.6.x) **cannot mount
exFAT natively** — the format step succeeds via `exfatprogs`, but `mount` then fails with
"unknown filesystem type 'exfat'". Two extra steps if you go exFAT on WSL2:

```bash
# 1. Install the FUSE userspace driver
sudo apt-get install -y exfat-fuse

# 2. Make mount(8)'s auto-detect call the FUSE helper
sudo ln -sf /usr/sbin/mount.exfat-fuse /usr/sbin/mount.exfat
```

Once those are in place, `mount /dev/sdX1 /mnt/...` (no `-t`) finds the helper and works.

Other things `usb-backup.sh` handles for you when the target FS can't carry Unix metadata
(exFAT, FUSE-mounted): rsync runs with `--no-owner --no-group --no-perms` so the script
doesn't error on chown/chmod attempts. The `chown` at the end of the script also no-ops
silently on exFAT — that's expected.

### About the sudoers rule

The `install-sudoers.sh` helper writes a single-line rule to `/etc/sudoers.d/bullpen-backup`:

```
<your-user> ALL=(root) NOPASSWD: /home/<you>/code/the-bullpen/infra/backup/usb-backup.sh
```

This is whitelist-based: it allows ONLY that exact script path to run without a password.
All other sudo invocations still prompt normally. The installer validates the rule with
`visudo -c` before writing and refuses to install a broken rule.

**Trade-off:** the script lives in the repo, so anyone with write access to the repo can
modify it and gain root-NOPASSWD execution. Fine for solo dev on your own machine. To
harden later (e.g., if a teammate joins or you want defense-in-depth):

```bash
# Copy the script to a root-only-writable location and point sudoers there
sudo install -o root -g root -m 0755 infra/backup/usb-backup.sh /usr/local/sbin/bullpen-usb-backup
./infra/backup/install-sudoers.sh --hardened    # rule now references /usr/local/sbin/...
```

Then re-copy the system script whenever the in-repo version changes.

### Remove the sudoers rule

```bash
./infra/backup/install-sudoers.sh --uninstall
```

### Restore drill (do this before the season - discipline rule 8)

The restore drill is `ops/scripts/restore-drill.sh` (the old `restore-test.sh` placeholder was
never written; this is the real one):

```bash
# Local-mechanics drill: create a backup on the live container, restore into a
# scratch ClickHouse, verify row counts + schema. Fast, no network.
ops/scripts/restore-drill.sh

# Disaster-recovery drill: restore the latest OFFSITE set from R2 (ClickHouse +
# the SQLite registry), then boot BOTH app profiles against the restored data.
# Preview the plan with --dry-run first.
ops/scripts/restore-drill.sh --from-r2 --dry-run
ops/scripts/restore-drill.sh --from-r2
```

## Layer 3 — Offsite to Cloudflare R2 (`offsite-push.sh`, the P2 leg of [153])

A SEPARATE decoupled step from the local snapshot: `bullpen-offsite@<user>.service` +
`bullpen-offsite@<user>.timer` fire at **03:30 local** (after the 03:00 local snapshot). An R2
failure alerts Discord and fails the offsite unit; it can never fail or block the local
snapshot - local-first is the prime directive. The script pushes, per `auto_*` snapshot:

- the night's clickhouse-backup output as a **single `clickhouse.tar` object** (tar staged to a
  temp file under `OFFSITE_TMP_DIR` then uploaded with `rclone copyto`, NOT `rclone rcat` - R2
  returns `NotImplemented` for rcat's streaming upload of a large object, observed 2026-06-13;
  copyto uses the proven multipart path and self-verifies the upload checksum). It is ONE object,
  not ~64k tiny per-column files: the 2026-06-13 restore-from-R2 drill proved the
  many-tiny-objects layout did not reliably round-trip on fetch (see
  `docs/drills/2026-06-13_restore-from-r2-drill-FAIL.md`). Restore = one download + untar.
- `auto_*_sqlite/registry.sqlite` (asserted non-empty BEFORE push - the P1 lesson)
- `auto_*_artifacts_meta/`

The registry + artifacts-meta go via `rclone copy` + `rclone check --one-way` (NEVER sync - the
script has no delete authority); the clickhouse tar is uploaded with `rclone copyto` (whose
post-transfer checksum check is the integrity gate) and backstopped with an object-exists check.
The temp tar is removed on any exit via an EXIT trap. Logs `OFFSITE SUCCESS: <name> objects=N
bytes=B` (grep for it).

### Env contract (`/etc/default/bullpen`, chmod 600, never committed)

```bash
# THE TIMER RUNS AS ROOT: rclone's config lives under the dev user's home and the root
# context cannot discover it - the explicit path is REQUIRED (same failure class as the
# /home-path registration gap).
RCLONE_CONFIG=/home/alepic/.config/rclone/rclone.conf
# Setting this ENABLES the leg; unset = clean no-op (dev/CI safe).
BULLPEN_OFFSITE_REMOTE=bullpen-r2:bullpen-prod/backups
# Optional: a SEPARATE Healthchecks.io check for the offsite leg (success ping; /fail on
# failure). Deliberately distinct from BULLPEN_HC_PING_URL so the local dead-man stays
# local-only and the two failure domains alert independently.
#BULLPEN_OFFSITE_HC_PING_URL=https://hc-ping.com/<uuid>
```

### Auth + transport notes (box-proven 2026-06-11)

- The R2 token is **BUCKET-SCOPED**: `rclone lsd bullpen-r2:` (account root) returns
  **403 AccessDenied BY DESIGN** (no ListBuckets). Bucket-level paths work. A 403 at the
  account root is not broken auth - do not "fix" it.
- Transient R2 5xx happen (a 501 was observed mid-upload during the fold-export push);
  rclone's default retries recover them. Keep default retries; a single 5xx in the log
  is not fatal.

### Retention: R2 lifecycle rules (CONSOLE TASK, not script logic)

The [13] tiering (7-day local / 4-week weekly / 12-month monthly) is approximated with a
lifecycle rule the operator creates once in the Cloudflare console:
**R2 → bullpen-prod → Settings → Object lifecycle rules → Add rule**, prefix `backups/`,
"Delete uploaded objects after **35 days**". (Local Layer 1 keeps 14 days; the USB Layer 2
holds the long-tail monthlies. A finer weekly/monthly offsite tier would need date-aware
prefixes - deliberately out of scope for the script.) Record the rule's creation date here
when done.

### Box bring-up + dry-run (after merge)

```bash
git pull && ./infra/systemd/install.sh            # installs + enables the 03:30 timer (no-op until env set)
sudoedit /etc/default/bullpen                     # add RCLONE_CONFIG + BULLPEN_OFFSITE_REMOTE (above)
# Manual dry-run against the most recent local snapshot:
sudo -E env $(grep -v '^#' /etc/default/bullpen | xargs) ./infra/backup/offsite-push.sh
rclone size bullpen-r2:bullpen-prod/backups --config /home/alepic/.config/rclone/rclone.conf
# Then observe the next 03:00 -> 03:30 cycle end-to-end before trusting it.
```

Script-level tests (no network/docker needed): `./infra/backup/test-offsite-push.sh`.

## All layers, together

| Scenario                                            | Recovery path                                                                                                                                                                                                     |
| --------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Accidental `DROP TABLE`                             | Restore from yesterday's local snapshot (Layer 1) — ~5 min                                                                                                                                                        |
| SQLite registry corrupted                           | Restore `registry.sqlite` from local snapshot (Layer 1) — ~1 min                                                                                                                                                  |
| WSL2 distro broken                                  | Reinstall WSL2, restore from USB (Layer 2) — ~30 min                                                                                                                                                              |
| Desktop SSD dead                                    | New desktop, install WSL2, restore from USB (Layer 2) — hours                                                                                                                                                     |
| Desktop physically destroyed + USB at same location | **Layer 3 (offsite R2)**: pull `backups/<newest auto_*>` from `bullpen-r2:bullpen-prod`, restore ClickHouse via clickhouse-backup + drop in `registry.sqlite` (decision [153] P2, implemented 2026-06-11) — hours |

## Discord webhook setup

You need a webhook URL for the Layer 1 failure alert. To create one:

1. In your Discord server, go to a channel → Edit Channel → Integrations → Webhooks → New Webhook
2. Copy the webhook URL
3. Put it in `/etc/default/bullpen` as shown above

Test the webhook:

```bash
curl -X POST -H 'Content-Type: application/json' \
  -d '{"content":"bullpen webhook test"}' \
  "$BULLPEN_DISCORD_WEBHOOK"
```
