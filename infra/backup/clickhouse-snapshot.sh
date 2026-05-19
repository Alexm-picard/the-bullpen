#!/usr/bin/env bash
# Daily snapshot script for The Bullpen.
# Snapshots:
#   - ClickHouse via clickhouse-backup (or fallback to filesystem copy)
#   - SQLite registry (registry.sqlite + .sqlite-wal/shm)
#   - training/artifacts/ metadata (NOT the ONNX/parquet bytes themselves)
#
# Run via systemd timer (see bullpen-snapshot.timer). On failure, pings Discord webhook.
#
# Environment:
#   REPO_ROOT                — defaults to /home/$(whoami)/code/thebullpen
#   SNAPSHOT_DIR             — defaults to /var/lib/clickhouse-backup
#   SQLITE_REGISTRY          — defaults to $REPO_ROOT/backend/data/registry.sqlite
#   BULLPEN_DISCORD_WEBHOOK  — required; failures ping this URL
#   RETAIN_DAYS              — defaults to 14

set -uo pipefail

REPO_ROOT="${REPO_ROOT:-/home/$(whoami)/code/thebullpen}"
SNAPSHOT_DIR="${SNAPSHOT_DIR:-/var/lib/clickhouse-backup}"
SQLITE_REGISTRY="${SQLITE_REGISTRY:-${REPO_ROOT}/backend/data/registry.sqlite}"
RETAIN_DAYS="${RETAIN_DAYS:-14}"
TS="$(date -u +%Y%m%dT%H%M%SZ)"
NAME="auto_${TS}"

log()  { printf '[%s] %s\n' "$(date -u +%FT%TZ)" "$*"; }
fail() {
  log "FAIL: $*"
  if [[ -n "${BULLPEN_DISCORD_WEBHOOK:-}" ]]; then
    curl -fsS -X POST -H 'Content-Type: application/json' \
      -d "$(printf '{"content":"[bullpen] snapshot %s failed: %s"}' "$NAME" "$*")" \
      "$BULLPEN_DISCORD_WEBHOOK" >/dev/null 2>&1 || true
  fi
  exit 1
}

mkdir -p "$SNAPSHOT_DIR" || fail "cannot create $SNAPSHOT_DIR"

# 1. ClickHouse snapshot
log "ClickHouse snapshot: $NAME"
if command -v clickhouse-backup >/dev/null 2>&1; then
  clickhouse-backup create "$NAME" || fail "clickhouse-backup create failed"
else
  # Fallback: cold-ish copy via FREEZE
  SHADOW_DIR="${SNAPSHOT_DIR}/${NAME}_shadow"
  mkdir -p "$SHADOW_DIR"
  if command -v clickhouse-client >/dev/null 2>&1; then
    clickhouse-client --query "ALTER TABLE prediction_logs FREEZE" 2>/dev/null || log "WARN: prediction_logs freeze skipped (table may not exist yet)"
    clickhouse-client --query "ALTER TABLE drift_metrics FREEZE"  2>/dev/null || true
    clickhouse-client --query "ALTER TABLE pitches FREEZE"        2>/dev/null || true
  else
    fail "neither clickhouse-backup nor clickhouse-client available"
  fi
  log "WARN: clickhouse-backup not installed — using FREEZE only. Install it for proper restore."
fi

# 2. SQLite registry snapshot
if [[ -f "$SQLITE_REGISTRY" ]]; then
  REG_DIR="${SNAPSHOT_DIR}/${NAME}_sqlite"
  mkdir -p "$REG_DIR"
  log "SQLite registry snapshot via .backup"
  sqlite3 "$SQLITE_REGISTRY" ".backup '${REG_DIR}/registry.sqlite'" || fail "sqlite3 .backup failed"
else
  log "SKIP: $SQLITE_REGISTRY does not exist yet (pre-Phase 0)"
fi

# 3. Training artifacts metadata (paths and hashes, NOT the bytes)
if [[ -d "${REPO_ROOT}/training/artifacts" ]]; then
  META_DIR="${SNAPSHOT_DIR}/${NAME}_artifacts_meta"
  mkdir -p "$META_DIR"
  log "Artifact metadata snapshot"
  (cd "${REPO_ROOT}/training/artifacts" && \
    find . -type f \( -name '*.json' -o -name '*.yml' -o -name '*.yaml' \) -print0 | \
    xargs -0 -I{} cp --parents {} "$META_DIR/" 2>/dev/null) || true
  # Plus a manifest of large files (path + size + sha256) without copying them
  (cd "${REPO_ROOT}/training/artifacts" && \
    find . -type f \( -name '*.onnx' -o -name '*.parquet' -o -name '*.pt' \) -print0 | \
    xargs -0 sha256sum 2>/dev/null) > "${META_DIR}/large_files_manifest.txt" || true
fi

# 4. Retention — delete snapshots older than RETAIN_DAYS
log "Retention: keeping last ${RETAIN_DAYS} days"
find "$SNAPSHOT_DIR" -maxdepth 1 -mtime "+${RETAIN_DAYS}" -name 'auto_*' -exec rm -rf {} + 2>/dev/null || true

# Touch a sentinel file the destructive-CH hook checks for recency
touch "${SNAPSHOT_DIR}/.last_snapshot_ok"

log "DONE: $NAME"
