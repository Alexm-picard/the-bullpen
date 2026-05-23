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
#   REPO_ROOT                — defaults to /home/$(whoami)/code/the-bullpen
#   SNAPSHOT_DIR             — defaults to /var/lib/clickhouse-backup
#   SQLITE_REGISTRY          — defaults to $REPO_ROOT/backend/data/registry.sqlite
#   BULLPEN_DISCORD_WEBHOOK  — required; failures ping this URL
#   RETAIN_DAYS              — defaults to 14

set -uo pipefail

REPO_ROOT="${REPO_ROOT:-/home/$(whoami)/code/the-bullpen}"
SNAPSHOT_DIR="${SNAPSHOT_DIR:-/var/lib/clickhouse-backup}"
SQLITE_REGISTRY="${SQLITE_REGISTRY:-${REPO_ROOT}/backend/data/registry.sqlite}"
RETAIN_DAYS="${RETAIN_DAYS:-14}"
TS="$(date -u +%Y%m%dT%H%M%SZ)"
NAME="auto_${TS}"
# ClickHouse runs in Docker; clickhouse-backup MUST run inside the same
# container so it can see the data disk's shadow dir at /var/lib/clickhouse/
# rather than the host's namesake (which would be a stub). Running it on the
# host yields metadata-only backups with zero data parts. See the 2026-05-23
# restore drill for the discovery + repro. (CLAUDE.md rule 8 forcing function.)
CH_CONTAINER="${CH_CONTAINER:-bullpen-clickhouse}"
CB_HOST_BINARY="${CB_HOST_BINARY:-/usr/bin/clickhouse-backup}"

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

# 1. ClickHouse snapshot — clickhouse-backup runs INSIDE the docker container
log "ClickHouse snapshot: $NAME (inside $CH_CONTAINER)"
if ! docker ps --format '{{.Names}}' | grep -qx "$CH_CONTAINER"; then
  fail "container $CH_CONTAINER not running"
fi
# Ensure clickhouse-backup binary + config exist inside the container
if ! docker exec "$CH_CONTAINER" test -x /usr/bin/clickhouse-backup 2>/dev/null; then
  [[ -x "$CB_HOST_BINARY" ]] || fail "clickhouse-backup not in container and host binary $CB_HOST_BINARY missing"
  docker cp "$CB_HOST_BINARY" "$CH_CONTAINER:/usr/bin/clickhouse-backup" >/dev/null \
    || fail "docker cp clickhouse-backup into $CH_CONTAINER failed"
fi
if ! docker exec "$CH_CONTAINER" test -f /etc/clickhouse-backup/config.yml 2>/dev/null; then
  docker exec "$CH_CONTAINER" mkdir -p /etc/clickhouse-backup || true
  docker exec -i "$CH_CONTAINER" bash -c 'cat > /etc/clickhouse-backup/config.yml' <<'EOF'
general:
  remote_storage: none
clickhouse:
  username: default
  password: thebullpen
  host: localhost
  port: 9000
  data_path: /var/lib/clickhouse
EOF
fi
docker exec "$CH_CONTAINER" /usr/bin/clickhouse-backup create "$NAME" \
  || fail "clickhouse-backup create failed"
# Sanity: confirm the new backup has at least one data part if any user table has rows
HAS_ROWS=$(docker exec "$CH_CONTAINER" clickhouse-client --password thebullpen \
  --query "SELECT count() FROM system.parts WHERE active AND database NOT IN ('system','INFORMATION_SCHEMA','information_schema')" 2>/dev/null || echo 0)
if [[ "${HAS_ROWS:-0}" -gt 0 ]]; then
  DATA_PARTS=$(docker exec "$CH_CONTAINER" \
    find "/var/lib/clickhouse/backup/${NAME}/shadow" -name 'data.bin' 2>/dev/null \
    | wc -l)
  [[ "$DATA_PARTS" -ge 1 ]] || fail "backup ${NAME} captured 0 data parts despite ${HAS_ROWS} active parts in source — FREEZE path broken"
  log "data parts captured: ${DATA_PARTS}"
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
