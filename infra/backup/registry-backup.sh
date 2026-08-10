#!/usr/bin/env bash
# Hourly SQLite registry backup to R2 (K6, closing the 24h->1h RPO gap).
#
# The nightly clickhouse-snapshot.sh captures the registry as part of its run,
# but that gives a ~24h RPO against a DB that records promotions, experiments,
# and routing config. A midday box loss loses the morning's registry writes.
#
# This script runs hourly (bullpen-registry-backup.timer) and:
#   1. Takes a hot backup via `sqlite3 .backup` (WAL-safe, consistent snapshot)
#   2. Verifies the backup is a valid SQLite DB with the expected tables
#   3. Pushes it to R2 at a timestamped key (hourly/ prefix, separate from the
#      nightly snapshots/ tree so retention can differ)
#   4. Pings the dead-man (optional)
#
# Retention: the R2 lifecycle rule on hourly/ should keep ~48h of copies
# (operator-configured, not script-managed). The nightly snapshot provides
# the longer-term archive.
#
# Environment (all via /etc/default/bullpen):
#   BULLPEN_REGISTRY_DB         - path to the live registry (default /opt/bullpen/data/registry.sqlite)
#   BULLPEN_OFFSITE_REMOTE      - REQUIRED. e.g. bullpen-r2:bullpen-prod/backups
#   RCLONE_CONFIG               - REQUIRED on the box (root timer context)
#   BULLPEN_DISCORD_WEBHOOK     - failure pings
#   BULLPEN_REGISTRY_HC_PING_URL - optional dead-man check
#   RCLONE_BIN                  - binary override (default: rclone)
#
# Usage: registry-backup.sh
set -euo pipefail

REGISTRY_DB="${BULLPEN_REGISTRY_DB:-/opt/bullpen/data/registry.sqlite}"
OFFSITE_REMOTE="${BULLPEN_OFFSITE_REMOTE:-}"
RCLONE_BIN="${RCLONE_BIN:-rclone}"
RCLONE_CONFIG="${RCLONE_CONFIG:-}"
DISCORD_WEBHOOK="${BULLPEN_DISCORD_WEBHOOK:-}"

log() { echo "[$(date -u '+%Y-%m-%dT%H:%M:%SZ')] $*"; }

fail() {
  log "FAIL: $*"
  if [[ -n "$DISCORD_WEBHOOK" ]]; then
    curl -sf -H 'Content-Type: application/json' \
      -d "{\"content\":\"registry-backup FAIL: $*\"}" \
      "$DISCORD_WEBHOOK" >/dev/null 2>&1 || true
  fi
  exit 1
}

rclone_() {
  local args=("$@")
  if [[ -n "$RCLONE_CONFIG" ]]; then
    args=("--config" "$RCLONE_CONFIG" "${args[@]}")
  fi
  "$RCLONE_BIN" "${args[@]}"
}

# --- pre-checks ---
[[ -f "$REGISTRY_DB" ]] || fail "registry DB not found at $REGISTRY_DB"

if [[ -z "$OFFSITE_REMOTE" ]]; then
  log "BULLPEN_OFFSITE_REMOTE not set - skipping (dev / CI)"
  exit 0
fi

# --- hot backup ---
TIMESTAMP=$(date -u '+%Y%m%dT%H%M%SZ')
BACKUP_DIR=$(mktemp -d)
BACKUP_FILE="${BACKUP_DIR}/registry.sqlite"
trap 'rm -rf "$BACKUP_DIR"' EXIT

log "backing up $REGISTRY_DB -> $BACKUP_FILE"
sqlite3 "$REGISTRY_DB" ".backup '$BACKUP_FILE'" \
  || fail "sqlite3 .backup failed"

# --- integrity check ---
BACKUP_SIZE=$(stat -c%s "$BACKUP_FILE" 2>/dev/null || stat -f%z "$BACKUP_FILE" 2>/dev/null || echo 0)
[[ "${BACKUP_SIZE:-0}" -gt 0 ]] || fail "backup file is empty"

TABLE_COUNT=$(sqlite3 "$BACKUP_FILE" "SELECT count(*) FROM sqlite_master WHERE type='table'" 2>/dev/null || echo 0)
[[ "${TABLE_COUNT:-0}" -ge 3 ]] || fail "backup has only ${TABLE_COUNT} tables (expected >=3: model_versions, model_routing, experiment_results)"

VERSION_COUNT=$(sqlite3 "$BACKUP_FILE" "SELECT count(*) FROM model_versions" 2>/dev/null || echo 0)
log "backup verified: ${BACKUP_SIZE}B, ${TABLE_COUNT} tables, ${VERSION_COUNT} model_versions rows"

# --- push to R2 ---
REMOTE_KEY="${OFFSITE_REMOTE}/hourly/${TIMESTAMP}/registry.sqlite"
log "pushing to ${REMOTE_KEY}"
rclone_ copyto "$BACKUP_FILE" "$REMOTE_KEY" \
  || fail "rclone push failed to ${REMOTE_KEY}"

log "registry backup complete: ${REMOTE_KEY}"

# --- dead-man ping ---
if [[ -n "${BULLPEN_REGISTRY_HC_PING_URL:-}" ]]; then
  curl -fsS -o /dev/null "$BULLPEN_REGISTRY_HC_PING_URL" 2>/dev/null || true
fi
