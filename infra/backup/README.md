# Backup runbook

Two layers of backup for The Bullpen. They serve different failure modes — both are needed.

## Layer 1 — Daily automated snapshot (`clickhouse-snapshot.sh`)

**Protects against**: accidental DROP/TRUNCATE, schema migration gone wrong, app bug that corrupts data.

**Lives on**: the WSL2 host itself, at `$SNAPSHOT_DIR` (default `/var/lib/clickhouse-backup`).

**Runs**: daily at 03:00 local via `bullpen-snapshot.timer`.

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
sudo cp infra/backup/bullpen-snapshot.service /etc/systemd/system/bullpen-snapshot@.service
sudo cp infra/backup/bullpen-snapshot.timer   /etc/systemd/system/bullpen-snapshot.timer

# 3. Create the env file (KEEP THIS chmod 600 — contains the Discord webhook URL)
sudo tee /etc/default/bullpen >/dev/null <<EOF
BULLPEN_DISCORD_WEBHOOK="https://discord.com/api/webhooks/...your-webhook..."
REPO_ROOT="/home/$(whoami)/code/the-bullpen"
RETAIN_DAYS="14"
EOF
sudo chmod 600 /etc/default/bullpen

# 4. Enable + start the timer
sudo systemctl daemon-reload
sudo systemctl enable --now bullpen-snapshot.timer

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

### Restore drill (do this once before the season — discipline rule 8)

```bash
# Plug in the USB
./infra/backup/restore-test.sh    # TODO: write this in Phase 0
# Should bring up a scratch ClickHouse + SQLite from the USB, make a prediction, tear down.
```

## Both layers, together

| Scenario                                            | Recovery path                                                                                                                                                                                                                                                                                                          |
| --------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Accidental `DROP TABLE`                             | Restore from yesterday's local snapshot (Layer 1) — ~5 min                                                                                                                                                                                                                                                             |
| SQLite registry corrupted                           | Restore `registry.sqlite` from local snapshot (Layer 1) — ~1 min                                                                                                                                                                                                                                                       |
| WSL2 distro broken                                  | Reinstall WSL2, restore from USB (Layer 2) — ~30 min                                                                                                                                                                                                                                                                   |
| Desktop SSD dead                                    | New desktop, install WSL2, restore from USB (Layer 2) — hours                                                                                                                                                                                                                                                          |
| Desktop physically destroyed + USB at same location | **Layer 3 (offsite cloud) covers this** once the R2 push lands: clickhouse-backup + the registry snapshot push to Cloudflare R2 per ADR-0007 ([13]/[128]). The offsite leg was found unimplemented and is re-scheduled as P2 (see decision [153]). Until it ships, store the USB at a different location periodically. |

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
