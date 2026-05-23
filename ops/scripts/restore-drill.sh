#!/usr/bin/env bash
# Phase 0 restore drill — proves a clickhouse-backup snapshot can be restored
# end-to-end into a scratch ClickHouse instance, with data integrity verified
# against the live instance.
#
# Sequence:
#   1. Insert a known marker row into bullpen._drill_marker on LIVE
#   2. OPTIMIZE FINAL to force the table to flush to a real on-disk part
#   3. clickhouse-backup create (INSIDE the live container — running on host
#      yields metadata-only backups because the host path /var/lib/clickhouse/
#      doesn't see the container's shadow dir at /var/lib/clickhouse/shadow/)
#   4. Spin scratch ClickHouse on a dedicated docker network + non-default ports
#   5. Install clickhouse-backup binary into scratch
#   6. docker cp the backup directory from live → scratch
#   7. clickhouse-backup restore inside scratch
#   8. Verify row counts, drill row presence, schema parity
#   9. Teardown scratch + cleanup live marker row + live backup dir
#
# Exit codes:
#   0 — PASS
#   1 — FAIL
#
# Idempotent: cleans up prior scratch container/network on entry.

set -uo pipefail

# --- config ----------------------------------------------------------------

LIVE_CONTAINER=bullpen-clickhouse
SCRATCH_CONTAINER=bullpen-clickhouse-scratch
SCRATCH_NETWORK=bullpen-drill-net
SCRATCH_HTTP_PORT=18123
SCRATCH_NATIVE_PORT=19000
CH_IMAGE=clickhouse/clickhouse-server:24.12
CB_BINARY=/usr/bin/clickhouse-backup
CH_PASSWORD=thebullpen

DRILL_ID=$(date -u +%s)
DRILL_NOTE="restore-drill-${DRILL_ID}"
BACKUP_NAME="drill_$(date -u +%Y%m%dT%H%M%SZ)"

ts() { printf '[%s] ' "$(date -u +%FT%TZ)"; }
log() { printf '%s%s\n' "$(ts)" "$*"; }
fail() { log "FAIL: $*"; cleanup; exit 1; }

cleanup() {
  log "cleanup: scratch container + network"
  docker rm -f "$SCRATCH_CONTAINER" >/dev/null 2>&1 || true
  docker network rm "$SCRATCH_NETWORK" >/dev/null 2>&1 || true
}

trap cleanup EXIT

# --- preflight -------------------------------------------------------------

log "preflight: tools + live state"
command -v docker >/dev/null || fail "docker not installed"
[[ -x "$CB_BINARY" ]] || fail "$CB_BINARY not executable"
docker ps --format '{{.Names}}' | grep -qx "$LIVE_CONTAINER" || fail "$LIVE_CONTAINER not running"

cleanup  # stale prior runs

# Make sure clickhouse-backup is installed inside the live container
# (this also matches the convention this drill is establishing: clickhouse-backup
# always runs inside the same container that owns the data)
log "preflight: ensure clickhouse-backup is present inside live container"
if ! docker exec "$LIVE_CONTAINER" test -x /usr/bin/clickhouse-backup 2>/dev/null; then
  docker cp "$CB_BINARY" "$LIVE_CONTAINER:/usr/bin/clickhouse-backup" >/dev/null
fi
if ! docker exec "$LIVE_CONTAINER" test -f /etc/clickhouse-backup/config.yml 2>/dev/null; then
  docker exec "$LIVE_CONTAINER" mkdir -p /etc/clickhouse-backup
  docker exec -i "$LIVE_CONTAINER" bash -c 'cat > /etc/clickhouse-backup/config.yml' <<EOF
general:
  remote_storage: none
clickhouse:
  username: default
  password: ${CH_PASSWORD}
  host: localhost
  port: 9000
  data_path: /var/lib/clickhouse
EOF
fi

# --- 1. seed live --------------------------------------------------------

log "seed: insert drill marker (id=${DRILL_ID}, note='${DRILL_NOTE}')"
docker exec "$LIVE_CONTAINER" clickhouse-client --password "$CH_PASSWORD" --query "
  INSERT INTO bullpen._drill_marker (id, note) VALUES (${DRILL_ID}, '${DRILL_NOTE}')
" || fail "marker insert failed"

log "flush: OPTIMIZE TABLE bullpen._drill_marker FINAL"
docker exec "$LIVE_CONTAINER" clickhouse-client --password "$CH_PASSWORD" --query "
  OPTIMIZE TABLE bullpen._drill_marker FINAL
" || fail "optimize failed"

LIVE_COUNT=$(docker exec "$LIVE_CONTAINER" clickhouse-client --password "$CH_PASSWORD" \
  --query "SELECT count() FROM bullpen._drill_marker")
log "live row count: ${LIVE_COUNT}"
[[ "$LIVE_COUNT" -ge 1 ]] || fail "live row count is zero — drill cannot prove restore"

# --- 2. snapshot (inside live container) --------------------------------

log "snapshot: clickhouse-backup create ${BACKUP_NAME} (inside ${LIVE_CONTAINER})"
docker exec "$LIVE_CONTAINER" /usr/bin/clickhouse-backup create "$BACKUP_NAME" \
  >/tmp/restore-drill-create.log 2>&1 \
  || { cat /tmp/restore-drill-create.log; fail "snapshot create failed"; }

# Confirm the backup has actual data (not metadata-only)
BACKUP_SUMMARY=$(docker exec "$LIVE_CONTAINER" /usr/bin/clickhouse-backup list 2>&1 \
  | grep -F "$BACKUP_NAME" | head -1)
log "backup summary: ${BACKUP_SUMMARY}"

DATA_PARTS=$(docker exec "$LIVE_CONTAINER" \
  find "/var/lib/clickhouse/backup/${BACKUP_NAME}/shadow" -name 'data.bin' 2>/dev/null \
  | wc -l)
[[ "$DATA_PARTS" -ge 1 ]] || fail "backup has 0 data parts — FREEZE did not capture rows"
log "data parts captured: ${DATA_PARTS}"

# --- 3. spin scratch -----------------------------------------------------

log "spin: scratch ClickHouse on ${SCRATCH_NETWORK} (http=${SCRATCH_HTTP_PORT}, native=${SCRATCH_NATIVE_PORT})"
docker network create "$SCRATCH_NETWORK" >/dev/null
docker run -d \
  --name "$SCRATCH_CONTAINER" \
  --network "$SCRATCH_NETWORK" \
  -p ${SCRATCH_HTTP_PORT}:8123 \
  -p ${SCRATCH_NATIVE_PORT}:9000 \
  -e CLICKHOUSE_USER=default \
  -e CLICKHOUSE_PASSWORD="$CH_PASSWORD" \
  -e CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT=1 \
  "$CH_IMAGE" >/dev/null \
  || fail "scratch run failed"

log "wait: scratch ClickHouse ready"
for i in $(seq 1 60); do
  if docker exec "$SCRATCH_CONTAINER" clickhouse-client --password "$CH_PASSWORD" \
       --query "SELECT 1" >/dev/null 2>&1; then
    log "scratch ready after ${i}s"
    break
  fi
  sleep 1
  [[ "$i" -eq 60 ]] && fail "scratch did not become ready in 60s"
done

SCRATCH_VERSION=$(docker exec "$SCRATCH_CONTAINER" clickhouse-client --password "$CH_PASSWORD" \
  --query "SELECT version()")
log "scratch version: ${SCRATCH_VERSION}"

# --- 4. install clickhouse-backup into scratch ---------------------------

log "install: clickhouse-backup into scratch"
docker cp "$CB_BINARY" "$SCRATCH_CONTAINER:/usr/bin/clickhouse-backup" >/dev/null
docker exec "$SCRATCH_CONTAINER" /usr/bin/clickhouse-backup --version >/dev/null \
  || fail "scratch clickhouse-backup not executable"

docker exec "$SCRATCH_CONTAINER" mkdir -p /etc/clickhouse-backup
docker exec -i "$SCRATCH_CONTAINER" bash -c 'cat > /etc/clickhouse-backup/config.yml' <<EOF
general:
  remote_storage: none
clickhouse:
  username: default
  password: ${CH_PASSWORD}
  host: localhost
  port: 9000
  data_path: /var/lib/clickhouse
EOF

# --- 5. transfer backup into scratch ------------------------------------

log "transfer: backup ${BACKUP_NAME} from live → scratch"
docker exec "$SCRATCH_CONTAINER" mkdir -p /var/lib/clickhouse/backup
docker exec "$LIVE_CONTAINER" tar -C /var/lib/clickhouse/backup -cf - "$BACKUP_NAME" \
  | docker exec -i "$SCRATCH_CONTAINER" tar -C /var/lib/clickhouse/backup -xf - \
  || fail "backup tar failed"

docker exec -u 0 "$SCRATCH_CONTAINER" chown -R clickhouse:clickhouse \
  "/var/lib/clickhouse/backup/${BACKUP_NAME}" \
  || fail "chown failed"

# --- 6. restore ----------------------------------------------------------

log "restore: clickhouse-backup restore ${BACKUP_NAME} (inside scratch)"
docker exec "$SCRATCH_CONTAINER" /usr/bin/clickhouse-backup restore "$BACKUP_NAME" \
  >/tmp/restore-drill-restore.log 2>&1 \
  || { cat /tmp/restore-drill-restore.log; fail "restore command failed"; }

# --- 7. verify -----------------------------------------------------------

log "verify: query scratch"
SCRATCH_COUNT=$(docker exec "$SCRATCH_CONTAINER" clickhouse-client --password "$CH_PASSWORD" \
  --query "SELECT count() FROM bullpen._drill_marker")
SCRATCH_HAS_DRILL=$(docker exec "$SCRATCH_CONTAINER" clickhouse-client --password "$CH_PASSWORD" \
  --query "SELECT count() FROM bullpen._drill_marker WHERE note='${DRILL_NOTE}'")
LIVE_SCHEMA=$(docker exec "$LIVE_CONTAINER" clickhouse-client --password "$CH_PASSWORD" \
  --query "SELECT engine_full FROM system.tables WHERE database='bullpen' AND name='_drill_marker'")
SCRATCH_SCHEMA=$(docker exec "$SCRATCH_CONTAINER" clickhouse-client --password "$CH_PASSWORD" \
  --query "SELECT engine_full FROM system.tables WHERE database='bullpen' AND name='_drill_marker'")

log "scratch row count: ${SCRATCH_COUNT}"
log "drill row in scratch: ${SCRATCH_HAS_DRILL}"
log "live schema:    ${LIVE_SCHEMA}"
log "scratch schema: ${SCRATCH_SCHEMA}"

# --- 8. cleanup live (marker row + drill backup) ------------------------

log "cleanup live: TRUNCATE TABLE bullpen._drill_marker"
docker exec "$LIVE_CONTAINER" clickhouse-client --password "$CH_PASSWORD" \
  --query "TRUNCATE TABLE bullpen._drill_marker" || true

log "cleanup live: delete drill backup ${BACKUP_NAME}"
docker exec "$LIVE_CONTAINER" /usr/bin/clickhouse-backup delete local "$BACKUP_NAME" \
  >/dev/null 2>&1 || true

# --- 9. result ---------------------------------------------------------

echo
echo "================================================================"
echo "RESTORE DRILL RESULT"
echo "================================================================"
echo "  backup:           ${BACKUP_NAME}"
echo "  live row count:   ${LIVE_COUNT}"
echo "  scratch count:    ${SCRATCH_COUNT}"
echo "  drill row found:  ${SCRATCH_HAS_DRILL}"
echo "  schema match:     $([[ "$LIVE_SCHEMA" == "$SCRATCH_SCHEMA" ]] && echo yes || echo no)"
echo "================================================================"

if [[ "$LIVE_COUNT" == "$SCRATCH_COUNT" \
   && "$SCRATCH_HAS_DRILL" -ge 1 \
   && "$LIVE_SCHEMA" == "$SCRATCH_SCHEMA" ]]; then
  echo "  RESULT: PASS"
  exit 0
else
  echo "  RESULT: FAIL"
  exit 1
fi
