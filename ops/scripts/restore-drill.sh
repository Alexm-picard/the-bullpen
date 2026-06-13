#!/usr/bin/env bash
# Restore drill - proves a clickhouse-backup snapshot can be restored end-to-end
# into a scratch ClickHouse instance, with data integrity verified.
#
# TWO MODES:
#
#   (default, local)  Create a fresh backup on the LIVE container, restore it into
#                     scratch, verify a seeded marker round-trips. Proves the
#                     backup/restore MECHANICS. Fast, no network, no registry/app.
#
#   --from-r2         The real disaster-recovery drill (CLAUDE.md rule 8, ADR-0007
#                     P2 leg). Fetch the LATEST offsite set from Cloudflare R2 - the
#                     copy that survives an SSD failure - restore BOTH ClickHouse and
#                     the SQLite registry into scratch, then boot BOTH app profiles
#                     against the restored data. The 2026-05-23 local-only drill was
#                     retired as INVALID: it never restored the registry and never
#                     booted the worker (the profile that hard-fails on a missing
#                     bean - the 2026-06-04 crash-loop that went undetected for 4
#                     days because the drill only ever booted api). This mode fixes
#                     both. See .claude/agents/drill-runner.md.
#
#   --dry-run         Resolve + print the plan and config without executing any
#                     docker / rclone / java / restore step. Safe to run anywhere
#                     (incl. the Mac) to sanity-check wiring before the box runs it.
#
# WHERE: the box (ADR-0006). It needs the live docker stack, the rclone config, the
# deployed JAR, and the live registry. Authoring is on the Mac; this script is RUN on
# the box. The box captures evidence; the Mac writes docs/drills/{date}_restore.md.
#
# Exit codes: 0 PASS / 1 FAIL / 2 bad usage.
#
# Idempotent: cleans up prior scratch container/network/files on entry and on exit.

set -uo pipefail

# --- args ------------------------------------------------------------------

MODE=local
DRY_RUN=0
for arg in "$@"; do
  case "$arg" in
    --from-r2) MODE=r2 ;;
    --dry-run) DRY_RUN=1 ;;
    -h|--help)
      sed -n '2,40p' "$0"; exit 0 ;;
    *) echo "unknown arg: $arg (try --from-r2, --dry-run, --help)" >&2; exit 2 ;;
  esac
done

# --- config (shared) -------------------------------------------------------

LIVE_CONTAINER=bullpen-clickhouse
SCRATCH_CONTAINER=bullpen-clickhouse-scratch
SCRATCH_NETWORK=bullpen-drill-net
SCRATCH_HTTP_PORT=18123
SCRATCH_NATIVE_PORT=19000
CH_IMAGE=clickhouse/clickhouse-server:24.12
CB_BINARY=${CB_BINARY:-/usr/bin/clickhouse-backup}
CH_PASSWORD=${CH_PASSWORD:-thebullpen}
CH_DB=${CH_DB:-bullpen}

# --- config (r2 mode) ------------------------------------------------------

# REMOTE base, e.g. bullpen-r2:bullpen-prod/backups (same value as
# BULLPEN_OFFSITE_REMOTE that offsite-push.sh writes to). The R2 layout per snapshot
# NAME (offsite-push.sh is the authority) is:
#   ${REMOTE}/${NAME}/clickhouse/...          (the clickhouse-backup output)
#   ${REMOTE}/${NAME}/sqlite/registry.sqlite  (the P1-irreplaceable registry capture)
BULLPEN_OFFSITE_REMOTE="${BULLPEN_OFFSITE_REMOTE:-}"
RCLONE_BIN="${RCLONE_BIN:-rclone}"
# The root/cron context cannot discover the dev user's rclone config on its own
# (same failure class offsite-push.sh documents); pass it explicitly.
RCLONE_CONFIG="${RCLONE_CONFIG:-/home/alepic/.config/rclone/rclone.conf}"
LIVE_REGISTRY="${LIVE_REGISTRY:-/opt/bullpen/data/registry.sqlite}"
EXPECTED_MODELS="${EXPECTED_MODELS:-6}"   # fallback when the live registry is unreadable
BULLPEN_JAR="${BULLPEN_JAR:-/opt/bullpen/app.jar}"
JAVA_BIN="${JAVA_BIN:-java}"
DRILL_API_PORT="${DRILL_API_PORT:-18080}"
DRILL_WORKER_PORT="${DRILL_WORKER_PORT:-18081}"
BOOT_TIMEOUT="${BOOT_TIMEOUT:-120}"       # seconds to reach actuator UP
WORKER_SETTLE="${WORKER_SETTLE:-10}"      # seconds to confirm worker doesn't crash-loop post-UP

FETCH_DIR="${FETCH_DIR:-/tmp/restore-drill-r2-fetch}"
SCRATCH_REGISTRY="${SCRATCH_REGISTRY:-/tmp/restore-drill-scratch-registry.sqlite}"

DRILL_ID=$(date -u +%s)
DRILL_NOTE="restore-drill-${DRILL_ID}"
APP_PIDS=()

ts()   { printf '[%s] ' "$(date -u +%FT%TZ)"; }
log()  { printf '%s%s\n' "$(ts)" "$*"; }
fail() { log "FAIL: $*"; cleanup; exit 1; }
# run: execute, or in --dry-run just print. Use for every side-effecting command.
run()  { if [[ "$DRY_RUN" == "1" ]]; then printf '%s  [dry-run] %s\n' "$(ts)" "$*"; else eval "$@"; fi; }

cleanup() {
  log "cleanup: scratch container/network + drill app pids + fetched files"
  for pid in "${APP_PIDS[@]:-}"; do [[ -n "$pid" ]] && kill "$pid" >/dev/null 2>&1 || true; done
  docker rm -f "$SCRATCH_CONTAINER" >/dev/null 2>&1 || true
  docker network rm "$SCRATCH_NETWORK" >/dev/null 2>&1 || true
  rm -rf "$FETCH_DIR" "$SCRATCH_REGISTRY" "${SCRATCH_REGISTRY}-wal" "${SCRATCH_REGISTRY}-shm" 2>/dev/null || true
}
trap cleanup EXIT

# --- shared scratch lifecycle ----------------------------------------------

cb_config() {  # emit the local-only clickhouse-backup config for a container
  cat <<EOF
general:
  remote_storage: none
clickhouse:
  username: default
  password: ${CH_PASSWORD}
  host: localhost
  port: 9000
  data_path: /var/lib/clickhouse
EOF
}

install_cb() {  # install clickhouse-backup binary + config into a container
  local container="$1"
  if ! docker exec "$container" test -x /usr/bin/clickhouse-backup 2>/dev/null; then
    docker cp "$CB_BINARY" "$container:/usr/bin/clickhouse-backup" >/dev/null
  fi
  docker exec "$container" /usr/bin/clickhouse-backup --version >/dev/null \
    || fail "$container: clickhouse-backup not executable"
  docker exec "$container" mkdir -p /etc/clickhouse-backup
  cb_config | docker exec -i "$container" bash -c 'cat > /etc/clickhouse-backup/config.yml'
}

spin_scratch() {
  if [[ "$DRY_RUN" == "1" ]]; then
    log "spin: [dry-run] would create network ${SCRATCH_NETWORK} + run ${CH_IMAGE} (http=${SCRATCH_HTTP_PORT}, native=${SCRATCH_NATIVE_PORT}), wait ready, install clickhouse-backup"
    return 0
  fi
  log "spin: scratch ClickHouse on ${SCRATCH_NETWORK} (http=${SCRATCH_HTTP_PORT}, native=${SCRATCH_NATIVE_PORT})"
  docker network create "$SCRATCH_NETWORK" >/dev/null || fail "network create failed"
  docker run -d \
    --name "$SCRATCH_CONTAINER" \
    --network "$SCRATCH_NETWORK" \
    -p ${SCRATCH_HTTP_PORT}:8123 \
    -p ${SCRATCH_NATIVE_PORT}:9000 \
    -e CLICKHOUSE_USER=default \
    -e CLICKHOUSE_PASSWORD="$CH_PASSWORD" \
    -e CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT=1 \
    "$CH_IMAGE" >/dev/null || fail "scratch run failed"
  for i in $(seq 1 60); do
    if docker exec "$SCRATCH_CONTAINER" clickhouse-client --password "$CH_PASSWORD" \
         --query "SELECT 1" >/dev/null 2>&1; then
      log "scratch ready after ${i}s"; break
    fi
    sleep 1
    [[ "$i" -eq 60 ]] && fail "scratch did not become ready in 60s"
  done
  install_cb "$SCRATCH_CONTAINER"
}

scratch_q() {  # query scratch
  docker exec "$SCRATCH_CONTAINER" clickhouse-client --password "$CH_PASSWORD" --query "$1"
}

# ===========================================================================
# R2 (disaster-recovery) MODE
# ===========================================================================

rclone_() { "$RCLONE_BIN" --config "$RCLONE_CONFIG" "$@"; }

r2_find_newest() {
  # NAME = auto_<TS> where TS is date -u +%Y%m%dT%H%M%SZ - lexically sortable.
  # The token is bucket-scoped: `rclone lsd <remote>:` at the account root 403s by
  # design (no ListBuckets). Bucket-prefixed paths work - that is not a failure.
  local newest
  newest=$(rclone_ lsf "${BULLPEN_OFFSITE_REMOTE%/}/" --dirs-only 2>/tmp/restore-drill-lsf.err \
            | sed 's:/$::' | grep -E '^auto_' | sort | tail -1 || true)
  [[ -n "$newest" ]] || { cat /tmp/restore-drill-lsf.err >&2 2>/dev/null || true; \
    fail "no auto_* snapshot under ${BULLPEN_OFFSITE_REMOTE} (rclone config/remote/creds?)"; }
  printf '%s' "$newest"
}

r2_fetch() {
  local name="$1" base="${BULLPEN_OFFSITE_REMOTE%/}"
  log "fetch: ${base}/${name} -> ${FETCH_DIR}"
  run "mkdir -p '${FETCH_DIR}/clickhouse' '${FETCH_DIR}/sqlite'"
  # ClickHouse backup is ONE tar object (offsite-push.sh, post-2026-06-13): the 64k-tiny-object
  # layout did not reliably round-trip on fetch (the 2026-06-13 drill finding). One object download
  # + an EXACT size check is the fail-loud completeness gate the old `find data.bin` heuristic
  # lacked - it could not tell an incomplete fetch from a hollow backup. rclone retries transient R2
  # 5xx on its own - do not treat a single 5xx in the log as fatal.
  run "rclone_ copy '${base}/${name}/clickhouse.tar' '${FETCH_DIR}'" \
    || fail "rclone copy of clickhouse.tar failed"
  run "rclone_ copy '${base}/${name}/sqlite/registry.sqlite' '${FETCH_DIR}/sqlite'" \
    || fail "rclone copy of registry.sqlite failed"
  [[ "$DRY_RUN" == "1" ]] && return 0

  [[ -f "${FETCH_DIR}/clickhouse.tar" ]] || fail "clickhouse.tar not present after fetch"
  local remote_bytes local_bytes
  remote_bytes=$(rclone_ size --json "${base}/${name}/clickhouse.tar" 2>/dev/null \
    | sed -n 's/.*"bytes":[[:space:]]*\([0-9][0-9]*\).*/\1/p')
  local_bytes=$(stat -c%s "${FETCH_DIR}/clickhouse.tar" 2>/dev/null || echo 0)
  [[ -n "$remote_bytes" && "$remote_bytes" == "$local_bytes" ]] \
    || fail "incomplete fetch: clickhouse.tar local=${local_bytes}B != remote=${remote_bytes:-?}B"
  log "fetch verified: clickhouse.tar ${local_bytes}B == remote"
  tar -xf "${FETCH_DIR}/clickhouse.tar" -C "${FETCH_DIR}/clickhouse" --strip-components=1 \
    || fail "untar of clickhouse.tar failed"
  [[ -f "${FETCH_DIR}/sqlite/registry.sqlite" ]] || fail "registry.sqlite not present after fetch"
  # Format-agnostic non-hollow check: compact parts use data.bin, wide parts use per-column *.bin.
  find "${FETCH_DIR}/clickhouse" -name '*.bin' | grep -q . \
    || fail "restored backup has 0 data part files (*.bin) - hollow restore"
}

load_and_restore_ch() {
  local name="$1"
  log "load: fetched clickhouse backup -> scratch /var/lib/clickhouse/backup/${name}"
  run "docker exec '$SCRATCH_CONTAINER' mkdir -p '/var/lib/clickhouse/backup/${name}'"
  run "tar -C '${FETCH_DIR}/clickhouse' -cf - . | docker exec -i '$SCRATCH_CONTAINER' tar -C '/var/lib/clickhouse/backup/${name}' -xf -" \
    || fail "loading backup into scratch failed"
  run "docker exec -u 0 '$SCRATCH_CONTAINER' chown -R clickhouse:clickhouse '/var/lib/clickhouse/backup/${name}'" \
    || fail "chown failed"
  log "restore: clickhouse-backup restore ${name} (inside scratch)"
  run "docker exec '$SCRATCH_CONTAINER' /usr/bin/clickhouse-backup restore '${name}' >/tmp/restore-drill-restore.log 2>&1" \
    || { [[ "$DRY_RUN" == "1" ]] || cat /tmp/restore-drill-restore.log; fail "restore command failed"; }
}

verify_ch_scratch() {
  # The offsite backup is from last night, so live row counts will have GROWN since -
  # an exact count match (the local-mode check) is wrong here. The DR assertion is:
  # the core tables came back AND a substantive table is non-empty (a restored-but-
  # empty table is the silent failure this guards against).
  [[ "$DRY_RUN" == "1" ]] && { log "verify-ch: [dry-run] skipped"; return 0; }
  local tables core_non_empty=0
  tables=$(scratch_q "SELECT name FROM system.tables WHERE database='${CH_DB}' ORDER BY name FORMAT TSV")
  log "scratch ${CH_DB} tables restored: $(echo "$tables" | tr '\n' ' ')"
  for t in prediction_log pitches pitches_live drift_metrics; do
    if echo "$tables" | grep -qx "$t"; then
      local c; c=$(scratch_q "SELECT count() FROM ${CH_DB}.${t}" 2>/dev/null || echo 0)
      log "  ${CH_DB}.${t}: ${c} rows"
      [[ "${c:-0}" -gt 0 ]] && core_non_empty=1
    fi
  done
  [[ "$core_non_empty" == "1" ]] || fail "no core ClickHouse table is non-empty after restore - restore is hollow"
}

restore_registry() {
  log "registry: .restore -> ${SCRATCH_REGISTRY}, integrity_check, count vs live"
  run "rm -f '${SCRATCH_REGISTRY}'"
  # .restore reads the fetched DB into the scratch file - proves the restore path,
  # not just a file copy.
  run "sqlite3 '${SCRATCH_REGISTRY}' \".restore '${FETCH_DIR}/sqlite/registry.sqlite'\"" \
    || fail "sqlite3 .restore failed"
  [[ "$DRY_RUN" == "1" ]] && { log "registry: [dry-run] skipped checks"; return 0; }

  local integ; integ=$(sqlite3 "$SCRATCH_REGISTRY" 'PRAGMA integrity_check;' 2>/dev/null || echo "error")
  [[ "$integ" == "ok" ]] || fail "restored registry failed integrity_check: ${integ}"
  log "registry integrity_check: ok"

  local scratch_models
  scratch_models=$(sqlite3 "$SCRATCH_REGISTRY" "SELECT count(*) FROM model_versions;" 2>/dev/null || echo "-1")
  [[ "$scratch_models" -ge 0 ]] || fail "restored registry missing model_versions table - wrong DB?"

  if [[ -r "$LIVE_REGISTRY" ]]; then
    local live_models
    live_models=$(sqlite3 "$LIVE_REGISTRY" "SELECT count(*) FROM model_versions;" 2>/dev/null || echo "-1")
    log "model_versions: scratch=${scratch_models} live=${live_models}"
    [[ "$scratch_models" == "$live_models" && "$scratch_models" -ge 1 ]] \
      || fail "model_versions count mismatch (scratch=${scratch_models} vs live=${live_models})"
  else
    log "model_versions: scratch=${scratch_models} (live registry ${LIVE_REGISTRY} unreadable; comparing to EXPECTED_MODELS=${EXPECTED_MODELS})"
    [[ "$scratch_models" == "$EXPECTED_MODELS" ]] \
      || fail "model_versions count ${scratch_models} != expected ${EXPECTED_MODELS}"
  fi
  log "registry restore verified (${scratch_models} model_versions rows)"
}

boot_profile() {
  # boot_profile <profile> <port>  -> 0 healthy / 1 not. Best-effort prediction on api.
  local profile="$1" port="$2"
  log "boot: ${profile} profile on :${port} (scratch CH + scratch registry, ingest off)"
  if [[ "$DRY_RUN" == "1" ]]; then
    log "  [dry-run] ${JAVA_BIN} -jar ${BULLPEN_JAR} --spring.profiles.active=${profile} --server.port=${port} ..."
    return 0
  fi
  [[ -f "$BULLPEN_JAR" ]] || fail "JAR not found at ${BULLPEN_JAR} (set BULLPEN_JAR)"
  "$JAVA_BIN" -jar "$BULLPEN_JAR" \
    --spring.profiles.active="$profile" \
    --server.port="$port" \
    --bullpen.clickhouse.enabled=true \
    --bullpen.clickhouse.url="jdbc:ch:http://localhost:${SCRATCH_HTTP_PORT}/${CH_DB}" \
    --bullpen.clickhouse.user=default \
    --bullpen.clickhouse.password="$CH_PASSWORD" \
    --spring.datasource.url="jdbc:sqlite:${SCRATCH_REGISTRY}" \
    --spring.flyway.url="jdbc:sqlite:${SCRATCH_REGISTRY}" \
    --bullpen.ingest.live.enabled=false \
    --bullpen.ingest.players.enabled=false \
    >"/tmp/restore-drill-boot-${profile}.log" 2>&1 &
  local pid=$!
  APP_PIDS+=("$pid")

  local healthy=0
  for i in $(seq 1 "$BOOT_TIMEOUT"); do
    if ! kill -0 "$pid" 2>/dev/null; then
      cat "/tmp/restore-drill-boot-${profile}.log" | tail -30
      fail "${profile} process exited during startup (crash) - see /tmp/restore-drill-boot-${profile}.log"
    fi
    if curl -fsS "http://localhost:${port}/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; then
      healthy=1; log "  ${profile} actuator UP after ${i}s"; break
    fi
    sleep 1
  done
  [[ "$healthy" == "1" ]] || fail "${profile} did not reach actuator UP in ${BOOT_TIMEOUT}s"

  if [[ "$profile" == "api" ]]; then
    # Best-effort: a prediction needs model artifacts, which are NOT part of the
    # backup set (artifacts live in R2 snapshots/, gitignored locally). 404 here is
    # acceptable - the hard gate is actuator UP, which proves DB connectivity to the
    # restored scratch CH + registry. Logged for the report, never fatal.
    local code
    code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "http://localhost:${port}/v1/predict/pitch" \
      -H 'content-type: application/json' \
      -d '{"countBalls":1,"countStrikes":2,"outs":1,"inning":5,"baseState":0,"scoreDiff":0,"dow":3,"pitcherThrows":"R","batterStand":"L","parkId":"BOS","pitcherId":1,"batterId":2}' 2>/dev/null || echo "000")
    log "  api prediction probe: HTTP ${code} (200=artifact loaded, 404=artifact absent - both acceptable for a DATA restore)"
  else
    # The 2026-06-04 lesson: the worker hard-fails on a missing bean. A startup
    # hard-fail is caught by never-reaching-UP above; this catches a DELAYED crash.
    sleep "$WORKER_SETTLE"
    kill -0 "$pid" 2>/dev/null || fail "worker crashed within ${WORKER_SETTLE}s of UP (crash-loop) - see /tmp/restore-drill-boot-worker.log"
    curl -fsS "http://localhost:${port}/actuator/health" 2>/dev/null | grep -q '"status":"UP"' \
      || fail "worker health regressed within ${WORKER_SETTLE}s of UP"
    log "  worker stable for ${WORKER_SETTLE}s after UP"
  fi

  kill "$pid" >/dev/null 2>&1 || true
  wait "$pid" 2>/dev/null || true
  log "  ${profile} profile shut down"
}

run_r2_drill() {
  log "=== RESTORE DRILL (--from-r2): disaster-recovery from Cloudflare R2 ==="
  # preflight
  command -v docker >/dev/null || fail "docker not installed"
  [[ -n "$BULLPEN_OFFSITE_REMOTE" ]] || fail "BULLPEN_OFFSITE_REMOTE unset (e.g. bullpen-r2:bullpen-prod/backups)"
  command -v "$RCLONE_BIN" >/dev/null || fail "rclone not installed"
  [[ -f "$RCLONE_CONFIG" || "$DRY_RUN" == "1" ]] || fail "rclone config not found at ${RCLONE_CONFIG} (set RCLONE_CONFIG)"
  command -v sqlite3 >/dev/null || fail "sqlite3 not installed"
  [[ -x "$CB_BINARY" || "$DRY_RUN" == "1" ]] || fail "$CB_BINARY not executable"

  cleanup  # stale prior runs

  local NAME
  if [[ "$DRY_RUN" == "1" ]]; then
    NAME="auto_<newest-from-r2>"
    log "would resolve newest snapshot via: rclone lsf ${BULLPEN_OFFSITE_REMOTE%/}/ --dirs-only | grep auto_ | sort | tail -1"
  else
    NAME=$(r2_find_newest)
  fi
  log "newest offsite snapshot: ${NAME}"

  r2_fetch "$NAME"
  spin_scratch
  load_and_restore_ch "$NAME"
  verify_ch_scratch
  restore_registry
  boot_profile api "$DRILL_API_PORT"
  boot_profile worker "$DRILL_WORKER_PORT"

  echo
  echo "================================================================"
  echo "RESTORE DRILL RESULT (--from-r2)"
  echo "================================================================"
  echo "  source:           ${BULLPEN_OFFSITE_REMOTE%/}/${NAME}"
  echo "  clickhouse:        restored into scratch (core tables non-empty)"
  echo "  registry:          integrity ok, model_versions matches live"
  echo "  api profile:       actuator UP against restored data"
  echo "  worker profile:    actuator UP + stable ${WORKER_SETTLE}s (no crash-loop)"
  echo "================================================================"
  if [[ "$DRY_RUN" == "1" ]]; then echo "  RESULT: DRY-RUN OK (no execution)"; else echo "  RESULT: PASS"; fi
  exit 0
}

# ===========================================================================
# LOCAL (mechanics) MODE - the original drill, unchanged in substance
# ===========================================================================

run_local_drill() {
  local BACKUP_NAME="drill_$(date -u +%Y%m%dT%H%M%SZ)"
  log "=== RESTORE DRILL (local): backup/restore mechanics ==="
  log "preflight: tools + live state"
  command -v docker >/dev/null || fail "docker not installed"
  [[ -x "$CB_BINARY" ]] || fail "$CB_BINARY not executable"
  docker ps --format '{{.Names}}' | grep -qx "$LIVE_CONTAINER" || fail "$LIVE_CONTAINER not running"

  cleanup
  install_cb "$LIVE_CONTAINER"

  log "seed: insert drill marker (id=${DRILL_ID}, note='${DRILL_NOTE}')"
  docker exec "$LIVE_CONTAINER" clickhouse-client --password "$CH_PASSWORD" --query "
    INSERT INTO bullpen._drill_marker (id, note) VALUES (${DRILL_ID}, '${DRILL_NOTE}')" \
    || fail "marker insert failed"
  docker exec "$LIVE_CONTAINER" clickhouse-client --password "$CH_PASSWORD" --query "
    OPTIMIZE TABLE bullpen._drill_marker FINAL" || fail "optimize failed"

  local LIVE_COUNT
  LIVE_COUNT=$(docker exec "$LIVE_CONTAINER" clickhouse-client --password "$CH_PASSWORD" \
    --query "SELECT count() FROM bullpen._drill_marker")
  log "live row count: ${LIVE_COUNT}"
  [[ "$LIVE_COUNT" -ge 1 ]] || fail "live row count is zero - drill cannot prove restore"

  log "snapshot: clickhouse-backup create ${BACKUP_NAME} (inside ${LIVE_CONTAINER})"
  docker exec "$LIVE_CONTAINER" /usr/bin/clickhouse-backup create "$BACKUP_NAME" \
    >/tmp/restore-drill-create.log 2>&1 || { cat /tmp/restore-drill-create.log; fail "snapshot create failed"; }
  local DATA_PARTS
  # *.bin (not data.bin): compact parts use data.bin, wide parts use per-column <col>.bin.
  DATA_PARTS=$(docker exec "$LIVE_CONTAINER" \
    find "/var/lib/clickhouse/backup/${BACKUP_NAME}/shadow" -name '*.bin' 2>/dev/null | wc -l)
  [[ "$DATA_PARTS" -ge 1 ]] || fail "backup has 0 data parts - FREEZE did not capture rows"
  log "data parts captured: ${DATA_PARTS}"

  spin_scratch

  log "transfer: backup ${BACKUP_NAME} from live -> scratch"
  docker exec "$SCRATCH_CONTAINER" mkdir -p /var/lib/clickhouse/backup
  docker exec "$LIVE_CONTAINER" tar -C /var/lib/clickhouse/backup -cf - "$BACKUP_NAME" \
    | docker exec -i "$SCRATCH_CONTAINER" tar -C /var/lib/clickhouse/backup -xf - || fail "backup tar failed"
  docker exec -u 0 "$SCRATCH_CONTAINER" chown -R clickhouse:clickhouse \
    "/var/lib/clickhouse/backup/${BACKUP_NAME}" || fail "chown failed"

  log "restore: clickhouse-backup restore ${BACKUP_NAME} (inside scratch)"
  docker exec "$SCRATCH_CONTAINER" /usr/bin/clickhouse-backup restore "$BACKUP_NAME" \
    >/tmp/restore-drill-restore.log 2>&1 || { cat /tmp/restore-drill-restore.log; fail "restore command failed"; }

  local SCRATCH_COUNT SCRATCH_HAS_DRILL LIVE_SCHEMA SCRATCH_SCHEMA
  SCRATCH_COUNT=$(scratch_q "SELECT count() FROM bullpen._drill_marker")
  SCRATCH_HAS_DRILL=$(scratch_q "SELECT count() FROM bullpen._drill_marker WHERE note='${DRILL_NOTE}'")
  LIVE_SCHEMA=$(docker exec "$LIVE_CONTAINER" clickhouse-client --password "$CH_PASSWORD" \
    --query "SELECT engine_full FROM system.tables WHERE database='bullpen' AND name='_drill_marker'")
  SCRATCH_SCHEMA=$(scratch_q "SELECT engine_full FROM system.tables WHERE database='bullpen' AND name='_drill_marker'")

  log "cleanup live: TRUNCATE bullpen._drill_marker + delete drill backup"
  docker exec "$LIVE_CONTAINER" clickhouse-client --password "$CH_PASSWORD" \
    --query "TRUNCATE TABLE bullpen._drill_marker" || true
  docker exec "$LIVE_CONTAINER" /usr/bin/clickhouse-backup delete local "$BACKUP_NAME" >/dev/null 2>&1 || true

  echo
  echo "================================================================"
  echo "RESTORE DRILL RESULT (local mechanics)"
  echo "================================================================"
  echo "  backup:           ${BACKUP_NAME}"
  echo "  live row count:   ${LIVE_COUNT}"
  echo "  scratch count:    ${SCRATCH_COUNT}"
  echo "  drill row found:  ${SCRATCH_HAS_DRILL}"
  echo "  schema match:     $([[ "$LIVE_SCHEMA" == "$SCRATCH_SCHEMA" ]] && echo yes || echo no)"
  echo "================================================================"
  if [[ "$LIVE_COUNT" == "$SCRATCH_COUNT" && "$SCRATCH_HAS_DRILL" -ge 1 && "$LIVE_SCHEMA" == "$SCRATCH_SCHEMA" ]]; then
    echo "  RESULT: PASS"; exit 0
  else
    echo "  RESULT: FAIL"; exit 1
  fi
}

# --- dispatch --------------------------------------------------------------

if [[ "$MODE" == "r2" ]]; then
  run_r2_drill
else
  [[ "$DRY_RUN" == "1" ]] && { log "local mode --dry-run: no separate plan (it mutates only a scratch container + a disposable marker row)"; exit 0; }
  run_local_drill
fi
