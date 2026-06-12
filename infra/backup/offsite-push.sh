#!/usr/bin/env bash
# Offsite (R2) push of the night's snapshot - the P2 leg of decision [153] / ADR-0007.
#
# DECOUPLED BY CONSTRUCTION from clickhouse-snapshot.sh: this runs as its own oneshot
# unit (bullpen-offsite.timer, ~03:30, after the 03:00 local snapshot). An offsite
# failure alerts Discord and fails THIS unit; it can never fail or block the local
# snapshot. Local-first is the prime directive.
#
# What goes offsite per snapshot (rclone copy - NEVER sync; this script has no delete
# authority on the remote):
#   - the clickhouse-backup output for the night's auto_* name (read directly from the
#     docker volume's host mountpoint - resolved via docker inspect, no staging copy)
#   - the ${NAME}_sqlite/registry.sqlite capture (the P1 lesson: the irreplaceable one;
#     asserted non-empty BEFORE push)
#   - the ${NAME}_artifacts_meta dir (if present)
# Destination prefix: ${BULLPEN_OFFSITE_REMOTE}/${NAME}/...
#
# Retention is R2 LIFECYCLE RULES, not script logic - see infra/backup/README.md
# ("Layer 3") for the console rule the operator creates.
#
# Environment (all via /etc/default/bullpen; THE TIMER RUNS AS ROOT):
#   BULLPEN_OFFSITE_REMOTE      - REQUIRED to enable, e.g. bullpen-r2:bullpen-prod/backups.
#                                 Unset => the whole leg no-ops cleanly (dev / CI).
#   RCLONE_CONFIG               - REQUIRED on the box: the rclone config lives at
#                                 /home/alepic/.config/rclone/rclone.conf (0600 alepic),
#                                 which the root timer context cannot discover on its own.
#                                 Same failure class as the /home-path registration gap.
#   SNAPSHOT_DIR                - host-side captures dir (default /var/lib/clickhouse-backup)
#   CH_CONTAINER                - default bullpen-clickhouse
#   ALLOW_NO_REGISTRY           - =1 permits a missing registry capture (registry-less env only)
#   BULLPEN_DISCORD_WEBHOOK     - failure pings
#   BULLPEN_OFFSITE_HC_PING_URL - optional SEPARATE Healthchecks check for the offsite leg
#                                 (success ping; /fail on failure). Deliberately distinct from
#                                 BULLPEN_HC_PING_URL so the local dead-man stays local-only
#                                 and the two failure domains alert independently.
#   RCLONE_BIN / DOCKER_BIN     - binary overrides (used by test-offsite-push.sh)
#
# NOTE on the R2 token: it is BUCKET-SCOPED. `rclone lsd bullpen-r2:` (account root)
# returns 403 AccessDenied BY DESIGN (no ListBuckets); bucket-level paths work. A 403 at
# the account root is not broken auth. Transient 5xx from R2 happen (a 501 was observed
# mid-upload on 2026-06-11); rclone's default retries recover them - do not add
# --retries 1 or treat a single 5xx in the log as fatal.
#
# Usage: offsite-push.sh [auto_NAME]   (defaults to the newest auto_*_sqlite capture)

set -uo pipefail

RCLONE_BIN="${RCLONE_BIN:-rclone}"
DOCKER_BIN="${DOCKER_BIN:-docker}"
SNAPSHOT_DIR="${SNAPSHOT_DIR:-/var/lib/clickhouse-backup}"
CH_CONTAINER="${CH_CONTAINER:-bullpen-clickhouse}"

log() { printf '[%s] %s\n' "$(date -u +%FT%TZ)" "$*"; }

# Gate: no remote configured => the leg is disabled (dev / CI). Exit 0, loudly.
if [[ -z "${BULLPEN_OFFSITE_REMOTE:-}" ]]; then
  log "offsite leg disabled (BULLPEN_OFFSITE_REMOTE unset) - nothing pushed"
  exit 0
fi
REMOTE="${BULLPEN_OFFSITE_REMOTE%/}"

hc_ping() {
  [[ -n "${BULLPEN_OFFSITE_HC_PING_URL:-}" ]] || return 0
  curl -fsS -m 10 --retry 3 "${BULLPEN_OFFSITE_HC_PING_URL}${1:-}" >/dev/null 2>&1 || true
}

NAME="${1:-}"

fail() {
  log "OFFSITE FAIL: $*"
  if [[ -n "${BULLPEN_DISCORD_WEBHOOK:-}" ]]; then
    curl -fsS -X POST -H 'Content-Type: application/json' \
      -d "$(printf '{"content":"[bullpen] offsite push %s failed: %s"}' "${NAME:-?}" "$*")" \
      "$BULLPEN_DISCORD_WEBHOOK" >/dev/null 2>&1 || true
  fi
  hc_ping /fail
  exit 1
}

# Resolve the snapshot to push: explicit arg, else the newest host-side capture.
if [[ -z "$NAME" ]]; then
  NEWEST=$(ls -1dt "${SNAPSHOT_DIR}"/auto_*_sqlite 2>/dev/null | head -1 || true)
  [[ -n "$NEWEST" ]] || fail "no auto_*_sqlite capture under ${SNAPSHOT_DIR} - did the 03:00 local snapshot run?"
  NAME="$(basename "$NEWEST")"
  NAME="${NAME%_sqlite}"
fi
log "offsite push: ${NAME} -> ${REMOTE}/${NAME}"

# The irreplaceable piece first (P1 lesson): the registry capture must exist and be
# non-trivial BEFORE anything is pushed - an offsite set without the registry is the
# exact single-site gap this leg exists to close.
REG_DIR="${SNAPSHOT_DIR}/${NAME}_sqlite"
REG_FILE="${REG_DIR}/registry.sqlite"
if [[ -f "$REG_FILE" ]]; then
  REG_BYTES=$(wc -c < "$REG_FILE" 2>/dev/null || echo 0)
  [[ "${REG_BYTES:-0}" -ge 16384 ]] || fail "registry capture too small (${REG_BYTES}B < 16384) - refusing to push a bad set"
elif [[ "${ALLOW_NO_REGISTRY:-0}" == "1" ]]; then
  log "SKIP registry: ${REG_FILE} missing and ALLOW_NO_REGISTRY=1"
else
  fail "registry capture ${REG_FILE} not found (set ALLOW_NO_REGISTRY=1 only for a genuinely registry-less env)"
fi

# ClickHouse backup dir: inside the docker volume. Resolve the volume's host mountpoint
# from the running container (robust to compose project naming) - the root timer can
# read it directly, so no staging copy of multi-GB backups.
CH_MOUNT=$($DOCKER_BIN inspect -f \
  '{{range .Mounts}}{{if eq .Destination "/var/lib/clickhouse"}}{{.Source}}{{end}}{{end}}' \
  "$CH_CONTAINER" 2>/dev/null || true)
[[ -n "$CH_MOUNT" ]] || fail "cannot resolve the /var/lib/clickhouse mount for container ${CH_CONTAINER} (is it running?)"
CH_BACKUP="${CH_MOUNT}/backup/${NAME}"
[[ -d "$CH_BACKUP" ]] || fail "clickhouse backup dir ${CH_BACKUP} not found for ${NAME}"

# rclone copy + one-way verify, per piece. copy is additive-only by design.
push_verified() {
  local src="$1" dst="$2" label="$3"
  log "pushing ${label}: ${src} -> ${dst}"
  $RCLONE_BIN copy "$src" "$dst" --transfers 4 || fail "${label}: rclone copy failed"
  $RCLONE_BIN check --one-way "$src" "$dst" || fail "${label}: rclone check (one-way) mismatch after push"
  log "${label}: pushed + verified"
}

push_verified "$CH_BACKUP" "${REMOTE}/${NAME}/clickhouse" "clickhouse backup"
if [[ -d "$REG_DIR" ]]; then
  push_verified "$REG_DIR" "${REMOTE}/${NAME}/sqlite" "sqlite registry"
fi
META_DIR="${SNAPSHOT_DIR}/${NAME}_artifacts_meta"
if [[ -d "$META_DIR" ]]; then
  push_verified "$META_DIR" "${REMOTE}/${NAME}/artifacts_meta" "artifacts metadata"
fi

# Success line the box greps for: object count + bytes of the pushed set.
SIZE_JSON=$($RCLONE_BIN size --json "${REMOTE}/${NAME}" 2>/dev/null || echo '{}')
OBJ_COUNT=$(printf '%s' "$SIZE_JSON" | sed -n 's/.*"count":[[:space:]]*\([0-9][0-9]*\).*/\1/p')
OBJ_BYTES=$(printf '%s' "$SIZE_JSON" | sed -n 's/.*"bytes":[[:space:]]*\([0-9][0-9]*\).*/\1/p')
log "OFFSITE SUCCESS: ${NAME} objects=${OBJ_COUNT:-?} bytes=${OBJ_BYTES:-?}"
hc_ping

exit 0
