#!/usr/bin/env bash
# Air-gapped USB backup for The Bullpen.
# Use case: hardware contingency. If the WSL2 desktop dies, you can restore from an external drive.
#
# Plug in the USB drive (labeled BULLPEN_BACKUP — see setup below), run this, unplug.
# No daemon, no schedule — you decide when to run it (recommended: weekly during build,
# daily during season, and before any disruptive change like a Windows update / driver
# install / WSL2 distro upgrade).
#
# What gets copied:
#   - Latest ClickHouse snapshot from $SNAPSHOT_DIR
#   - registry.sqlite (live, via .backup)
#   - training/artifacts/ (ONNX + Parquet + metadata — these can't be regenerated quickly)
#   - /contracts/ (small but critical)
#   - docs/ (planning, drills, postmortems)
#
# What does NOT get copied:
#   - The repo source code (it's in git; clone it again)
#   - node_modules, build/, target/ (regenerated)
#   - Anything in /tmp
#
# Setup once: format a USB drive with a single ext4 (or exFAT for cross-platform) partition.
# Label it BULLPEN_BACKUP:
#   sudo mkfs.ext4 -L BULLPEN_BACKUP /dev/sdX1
# or
#   sudo mkfs.exfat -n BULLPEN_BACKUP /dev/sdX1
#
# Then plug it in and run this script. With the sudoers.d/bullpen-backup rule installed
# (see infra/backup/install-sudoers.sh), no password prompt.

set -euo pipefail

# Self-elevate to root. With the sudoers.d/bullpen-backup rule installed, this is NOPASSWD
# for this exact script path. Without the rule, you'll be prompted for sudo password once.
# We pass through SUDO_USER and the original env so we can find the right home dir.
if [[ $EUID -ne 0 ]]; then
  exec sudo --preserve-env=REPO_ROOT,SNAPSHOT_DIR,USB_LABEL,MOUNT_POINT "$0" "$@"
fi

# From here on, we're root. SUDO_USER tells us who invoked us (for the default REPO_ROOT path).
INVOKER="${SUDO_USER:-$(whoami)}"
REPO_ROOT="${REPO_ROOT:-/home/${INVOKER}/code/thebullpen}"
SNAPSHOT_DIR="${SNAPSHOT_DIR:-/var/lib/clickhouse-backup}"
USB_LABEL="${USB_LABEL:-BULLPEN_BACKUP}"
MOUNT_POINT="${MOUNT_POINT:-/mnt/bullpen-backup}"
DEVICE="$(blkid -L "$USB_LABEL" 2>/dev/null || true)"

log() { printf '[%s] %s\n' "$(date -u +%FT%TZ)" "$*"; }

if [[ -z "$DEVICE" ]]; then
  log "ERROR: no device with label '$USB_LABEL' is plugged in."
  log "Plug the backup USB drive in and try again."
  log "If this is the first time, format it: sudo mkfs.ext4 -L $USB_LABEL /dev/sdX1"
  exit 1
fi

log "Device: $DEVICE"
mkdir -p "$MOUNT_POINT"
mount "$DEVICE" "$MOUNT_POINT"
trap 'log "Unmounting..."; umount "$MOUNT_POINT" 2>/dev/null && rmdir "$MOUNT_POINT" 2>/dev/null || true' EXIT

DEST="${MOUNT_POINT}/bullpen-$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -p "$DEST"

log "Destination: $DEST"

# 1. Most recent ClickHouse snapshot only (don't bloat the USB with history)
if [[ -d "$SNAPSHOT_DIR" ]]; then
  LATEST=$(find "$SNAPSHOT_DIR" -maxdepth 1 -name 'auto_*' -type d 2>/dev/null | sort -r | head -1)
  if [[ -n "$LATEST" ]]; then
    log "Copying ClickHouse snapshot: $(basename "$LATEST")"
    rsync -a --info=progress2 "$LATEST/" "$DEST/clickhouse_snapshot/"
  else
    log "WARN: no clickhouse snapshot found in $SNAPSHOT_DIR — run clickhouse-snapshot.sh first"
  fi
fi

# 2. Live SQLite registry copy
SQLITE_REGISTRY="${REPO_ROOT}/backend/data/registry.sqlite"
if [[ -f "$SQLITE_REGISTRY" ]]; then
  log "SQLite registry"
  mkdir -p "${DEST}/sqlite"
  sqlite3 "$SQLITE_REGISTRY" ".backup '${DEST}/sqlite/registry.sqlite'"
fi

# 3. Training artifacts (the bytes — these are the part you can't easily regenerate)
if [[ -d "${REPO_ROOT}/training/artifacts" ]]; then
  log "Training artifacts (ONNX + Parquet + metadata)"
  rsync -a --info=progress2 "${REPO_ROOT}/training/artifacts/" "$DEST/training_artifacts/"
fi

# 4. Contracts (small, critical, defines the Python↔Java boundary)
if [[ -d "${REPO_ROOT}/contracts" ]]; then
  log "Contracts"
  rsync -a "${REPO_ROOT}/contracts/" "$DEST/contracts/"
fi

# 5. Docs (planning, drills, postmortems — irreplaceable narrative)
if [[ -d "${REPO_ROOT}/docs" ]]; then
  log "Docs"
  rsync -a "${REPO_ROOT}/docs/" "$DEST/docs/"
fi

# 6. Manifest file at root
{
  echo "Bullpen USB backup"
  echo "Created: $(date -u +%FT%TZ)"
  echo "Host: $(hostname)"
  echo "Invoked-by: $INVOKER"
  echo "Repo: $REPO_ROOT"
  echo ""
  echo "Contents:"
  du -sh "${DEST}"/* 2>/dev/null
} > "${DEST}/MANIFEST.txt"

# Hand ownership of the backup directory back to the invoker so they can inspect/restore
# without further sudo. The mount itself stays root-owned (Linux convention).
chown -R "${INVOKER}:${INVOKER}" "$DEST" 2>/dev/null || true

log "Sync complete. Total size: $(du -sh "$DEST" | cut -f1)"
log "Drive will unmount automatically on script exit. Unplug after the next line prints."
sync
