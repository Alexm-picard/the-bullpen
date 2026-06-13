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
#   SQLITE_REGISTRY          - the live registry DB; defaults to BULLPEN_REGISTRY_DB
#   BULLPEN_REGISTRY_DB      - live registry path; default /opt/bullpen/data/registry.sqlite
#   ALLOW_NO_REGISTRY        - set =1 to permit a missing registry (skip instead of fail)
#   BULLPEN_DISCORD_WEBHOOK  — required; failures ping this URL
#   BULLPEN_HC_PING_URL      - optional Healthchecks.io ping URL; success pings it, failure pings
#                              $URL/fail. The external snapshot dead-man's switch - if the daily run
#                              stops pinging, Healthchecks alerts, and unlike the internal Prometheus
#                              SnapshotStale rule it survives a full host-down.
#   NODE_TEXTFILE_DIR        - node_exporter textfile dir (default /var/lib/node_exporter); on success
#                              a bullpen_snapshot_last_success_timestamp_seconds metric is written here
#                              for the internal SnapshotStale Prometheus rule.
#   RETAIN_DAYS              — defaults to 14

set -uo pipefail

REPO_ROOT="${REPO_ROOT:-/home/$(whoami)/code/the-bullpen}"
SNAPSHOT_DIR="${SNAPSHOT_DIR:-/var/lib/clickhouse-backup}"
SQLITE_REGISTRY="${SQLITE_REGISTRY:-${BULLPEN_REGISTRY_DB:-/opt/bullpen/data/registry.sqlite}}"
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

NODE_TEXTFILE_DIR="${NODE_TEXTFILE_DIR:-/var/lib/node_exporter}"

log()  { printf '[%s] %s\n' "$(date -u +%FT%TZ)" "$*"; }
# Healthchecks.io ping; $1 is an optional path suffix (e.g. /fail). No-op when the URL is unset.
hc_ping() {
  [[ -n "${BULLPEN_HC_PING_URL:-}" ]] || return 0
  curl -fsS -m 10 --retry 3 "${BULLPEN_HC_PING_URL}${1:-}" >/dev/null 2>&1 || true
}
fail() {
  log "FAIL: $*"
  if [[ -n "${BULLPEN_DISCORD_WEBHOOK:-}" ]]; then
    curl -fsS -X POST -H 'Content-Type: application/json' \
      -d "$(printf '{"content":"[bullpen] snapshot %s failed: %s"}' "$NAME" "$*")" \
      "$BULLPEN_DISCORD_WEBHOOK" >/dev/null 2>&1 || true
  fi
  hc_ping /fail
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
  # *.bin (not data.bin): compact parts use data.bin, wide parts use per-column <col>.bin -
  # a wide-only backup has zero data.bin yet is fully valid (the 2026-06-13 drill finding).
  DATA_PARTS=$(docker exec "$CH_CONTAINER" \
    find "/var/lib/clickhouse/backup/${NAME}/shadow" -name '*.bin' 2>/dev/null \
    | wc -l)
  [[ "$DATA_PARTS" -ge 1 ]] || fail "backup ${NAME} captured 0 data parts despite ${HAS_ROWS} active parts in source - FREEZE path broken"
  log "data parts captured: ${DATA_PARTS}"
fi

# 2. SQLite registry snapshot (model registry / A-B config / retraining queue).
#    Sources the LIVE registry (default /opt/bullpen/data), not the repo's stale dev
#    copy. A missing registry is a HARD FAIL (set ALLOW_NO_REGISTRY=1 to override), and
#    the captured copy is verified non-empty + integrity-clean + schema-present before
#    the snapshot counts as good. See decision [153] (2026-06-08 backup remediation).
if [[ -f "$SQLITE_REGISTRY" ]]; then
  REG_DIR="${SNAPSHOT_DIR}/${NAME}_sqlite"
  mkdir -p "$REG_DIR"
  REG_OUT="${REG_DIR}/registry.sqlite"
  log "SQLite registry snapshot via .backup ($SQLITE_REGISTRY)"
  sqlite3 "$SQLITE_REGISTRY" ".backup '${REG_OUT}'" || fail "sqlite3 .backup failed for $SQLITE_REGISTRY"
  OUT_BYTES=$(wc -c < "$REG_OUT" 2>/dev/null || echo 0)
  [[ "${OUT_BYTES:-0}" -ge 16384 ]] || fail "registry capture too small (${OUT_BYTES}B < 16384) - empty or stale DB?"
  [[ "$(sqlite3 "$REG_OUT" 'PRAGMA integrity_check;' 2>/dev/null)" == "ok" ]] || fail "registry capture failed integrity_check"
  HAS_MV=$(sqlite3 "$REG_OUT" "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='model_versions';" 2>/dev/null || echo 0)
  [[ "${HAS_MV:-0}" -ge 1 ]] || fail "registry capture missing model_versions table - wrong DB?"
  log "registry captured: ${OUT_BYTES}B, integrity ok, schema present"
elif [[ "${ALLOW_NO_REGISTRY:-0}" == "1" ]]; then
  log "SKIP: $SQLITE_REGISTRY missing and ALLOW_NO_REGISTRY=1"
else
  fail "live registry $SQLITE_REGISTRY not found (set ALLOW_NO_REGISTRY=1 only for a genuinely registry-less env)"
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

# WS4: publish snapshot freshness on success. The node_exporter textfile metric drives the internal
# SnapshotStale Prometheus rule; the Healthchecks.io success ping is the external dead-man (it fires
# even if the whole host - and Prometheus with it - is down). Atomic write (tmp + mv) so the textfile
# collector never reads a half-written file.
if mkdir -p "$NODE_TEXTFILE_DIR" 2>/dev/null; then
  TEXTFILE="${NODE_TEXTFILE_DIR}/bullpen_snapshot.prom"
  TMP_TEXTFILE="${TEXTFILE}.$$"
  {
    echo "# HELP bullpen_snapshot_last_success_timestamp_seconds Unix time of the last successful snapshot."
    echo "# TYPE bullpen_snapshot_last_success_timestamp_seconds gauge"
    echo "bullpen_snapshot_last_success_timestamp_seconds $(date +%s)"
  } > "$TMP_TEXTFILE" && mv -f "$TMP_TEXTFILE" "$TEXTFILE" || log "WARN: could not write $TEXTFILE"
else
  log "WARN: NODE_TEXTFILE_DIR ${NODE_TEXTFILE_DIR} not writable; skipping snapshot freshness metric"
fi
hc_ping

log "DONE: $NAME"
