#!/usr/bin/env bash
# deploy.sh — Phase 0 deploy script for The Bullpen.
#
# Prefer the deploy-safely skill (/deploy-safely) over invoking this directly:
# it wraps this script with the live-game-window check, git tag, and post-deploy
# smoke verification (CLAUDE.md discipline rule 3, decision [21]).
#
# Per ADR-0006, this script is the *only* writer of the prod working copy.
# It's intended to run ON the WSL2 desktop, against the live install at
# /opt/bullpen. From the MacBook side, you ssh in and invoke this — or
# better, push to main and run it here via /deploy-safely.
#
# What it does:
#   1. Pre-flight: clean working tree (or --allow-dirty), systemd units present.
#   2. Build the backend bootJar (no tests — CI is the test gate per [20]).
#   3. Stage the JAR into /opt/bullpen/releases/<TAG>/app.jar.
#   4. Atomic symlink swap: /opt/bullpen/app.jar -> releases/<TAG>/app.jar
#   5. systemctl restart bullpen-api bullpen-worker.
#   6. Smoke: poll /actuator/health for up to 30s; both units must be active.
#   7. On smoke failure: swap symlink back to previous release, restart, exit 1.
#   8. Record the deploy at docs/deploys/<TAG>.md.
#   9. Prune releases beyond the last 5.
#
# What it does NOT do:
#   - Frontend deploy. The frontend is on Vercel and auto-deploys on push to main.
#   - SSH anywhere. This runs locally on the WSL2 host.
#   - Database migrations. Flyway runs at app boot.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

INSTALL_DIR="/opt/bullpen"
RELEASES_DIR="${INSTALL_DIR}/releases"
APP_SYMLINK="${INSTALL_DIR}/app.jar"

TAG="v$(date -u +%Y.%m.%d-%H%M)"
ALLOW_DIRTY=false
SKIP_SMOKE=false
ALLOW_GAME_WINDOW=false
for arg in "$@"; do
  case "$arg" in
    --allow-dirty) ALLOW_DIRTY=true ;;
    --skip-smoke)  SKIP_SMOKE=true ;;
    --allow-game-window) ALLOW_GAME_WINDOW=true ;;
    *) echo "Unknown flag: $arg" >&2; exit 2 ;;
  esac
done

log() { printf '[deploy %s] %s\n' "$TAG" "$*"; }
die() { printf '[deploy %s] ERROR: %s\n' "$TAG" "$*" >&2; exit 1; }

# Poll one profile's /actuator/health for up to 30s; 0 = went UP, 1 = did not.
smoke_health() {
  local port="$1" name="$2"
  for i in $(seq 1 30); do
    sleep 1
    if curl -fsS "http://localhost:${port}/actuator/health" 2>/dev/null \
        | grep -q '"status":"UP"'; then
      log "smoke OK: ${name} (:${port}) up after ${i}s"
      return 0
    fi
  done
  return 1
}

# Swap the symlink back to the previous release and restart both units.
rollback() {
  if [[ -n "${PREVIOUS_TARGET:-}" ]]; then
    # D2 guard: the previous release may have been PRUNED (step 9 keeps only 5) or otherwise
    # vanished - swapping the symlink to a dead path would leave the service unstartable, which
    # is worse than staying on the new (smoke-failing) release. Fail loud for the operator.
    if [[ ! -f "$PREVIOUS_TARGET" ]]; then
      log "ROLLBACK ABORTED: previous release $PREVIOUS_TARGET no longer exists (pruned?)"
      log "staying on the new release; operator action required (docs/runbooks/ROLLBACK.md)"
      return 1
    fi
    sudo ln -snf "$PREVIOUS_TARGET" "$TMP_LINK"
    sudo mv -Tf "$TMP_LINK" "$APP_SYMLINK"
    sudo systemctl restart bullpen-api bullpen-worker
    log "rolled back to $PREVIOUS_TARGET"
  else
    log "no previous release to roll back to"
  fi
}

# --- 1. Pre-flight -----------------------------------------------------------

if ! command -v java >/dev/null 2>&1; then
  die "java not found in PATH (need Java 21)"
fi

if [[ ! -d "$INSTALL_DIR" ]]; then
  die "$INSTALL_DIR does not exist — run infra/systemd/install.sh first"
fi

if [[ ! -f /etc/systemd/system/bullpen-api.service ]]; then
  die "bullpen-api.service not installed — run infra/systemd/install.sh first"
fi

# Rule 3 (decision [21]): no deploys during live games - evenings (16:00-23:59 ET)
# April-October. Enforced here in the script, not just in the deploy-safely skill
# prose. Override with --allow-game-window or BULLPEN_ALLOW_GAME_WINDOW_DEPLOY=1
# (the documented pre-launch / 0-user waiver path). ET is computed via TZ so it is
# correct regardless of the box's local timezone.
ET_MONTH=$((10#$(TZ='America/New_York' date +%m)))
ET_HOUR=$((10#$(TZ='America/New_York' date +%H)))
if (( ET_MONTH >= 4 && ET_MONTH <= 10 )) && (( ET_HOUR >= 16 && ET_HOUR <= 23 )); then
  if [[ "$ALLOW_GAME_WINDOW" == "true" || "${BULLPEN_ALLOW_GAME_WINDOW_DEPLOY:-}" == "1" ]]; then
    log "WARNING: inside the live-game window (rule 3) - proceeding via explicit override"
  else
    die "live-game window (rule 3): $(TZ='America/New_York' date '+%a %b %d %H:%M ET') is an evening in Apr-Oct - refusing to deploy. Override with --allow-game-window or BULLPEN_ALLOW_GAME_WINDOW_DEPLOY=1 if you accept the risk (e.g. pre-launch, 0 users)."
  fi
fi

if [[ "$ALLOW_DIRTY" != "true" ]]; then
  if [[ -n "$(git status --porcelain 2>/dev/null)" ]]; then
    die "working tree dirty (pass --allow-dirty to override)"
  fi
fi

REV="$(git rev-parse HEAD 2>/dev/null || echo 'no-git')"
log "git rev: $REV"

# --- 2. Build ----------------------------------------------------------------

log "building backend bootJar"
( cd backend && ./gradlew --no-daemon bootJar -x test )

# Select the executable Spring Boot fat jar, newest first. Two traps this guards:
#   - the `jar` task's non-executable `-plain.jar` (no Main-Class) can linger from
#     a prior `build`; it sorts BEFORE `.jar` so a bare `head -1` grabbed it and
#     the api died with "no main manifest attribute" (deploy auto-rolled-back).
#   - a stale fat jar from an older version: `-1t` (mtime) picks the one we just
#     built, not whatever sorts first alphabetically.
JAR_SRC="$(ls -1t backend/build/libs/*.jar 2>/dev/null | grep -v -- '-plain' | head -1)"
[[ -f "$JAR_SRC" ]] || die "no executable bootJar produced under backend/build/libs/"
log "built $(basename "$JAR_SRC") ($(du -h "$JAR_SRC" | cut -f1))"

# --- 3. Stage ----------------------------------------------------------------

RELEASE_DIR="${RELEASES_DIR}/${TAG}"
sudo install -d -o bullpen -g bullpen -m 0755 "$RELEASES_DIR"
sudo install -d -o bullpen -g bullpen -m 0755 "$RELEASE_DIR"
sudo install -o bullpen -g bullpen -m 0644 "$JAR_SRC" "${RELEASE_DIR}/app.jar"
log "staged ${RELEASE_DIR}/app.jar"

# B1 (PR-3): stage the canonical /contracts dir where the units expect it
# (BULLPEN_REGISTRY_CONTRACTSDIR=/opt/bullpen/contracts). The registry's
# bootstrap-registration gate hashes these files; without them a first-ever
# registration of a known model family fails loud with a pointer here.
CONTRACTS_DST="${INSTALL_DIR}/contracts"
sudo install -d -o bullpen -g bullpen -m 0755 "$CONTRACTS_DST"
for f in contracts/*.json contracts/README.md; do
  [[ -f "$f" ]] && sudo install -o bullpen -g bullpen -m 0644 "$f" "${CONTRACTS_DST}/$(basename "$f")"
done
log "staged canonical contracts -> ${CONTRACTS_DST}"

# --- 4. Atomic symlink swap --------------------------------------------------

PREVIOUS_TARGET=""
if [[ -L "$APP_SYMLINK" ]]; then
  PREVIOUS_TARGET="$(readlink "$APP_SYMLINK")"
  log "previous release: $PREVIOUS_TARGET"
fi

# `ln -sfn` is atomic on Linux when the target is a directory, but for a file
# we use the rename-trick for true atomicity.
TMP_LINK="${INSTALL_DIR}/.app.jar.new"
sudo ln -snf "${RELEASE_DIR}/app.jar" "$TMP_LINK"
sudo mv -Tf "$TMP_LINK" "$APP_SYMLINK"
log "symlink swapped: $APP_SYMLINK -> ${RELEASE_DIR}/app.jar"

# --- 5. Restart units --------------------------------------------------------

log "restarting bullpen-api + bullpen-worker"
sudo systemctl restart bullpen-api bullpen-worker

# --- 6. Smoke ----------------------------------------------------------------

if [[ "$SKIP_SMOKE" == "true" ]]; then
  log "smoke skipped (--skip-smoke)"
else
  log "smoke: waiting for api (:${BULLPEN_API_PORT:-8080}) AND worker (:${BULLPEN_WORKER_PORT:-8081}) to go green"
  if ! smoke_health "${BULLPEN_API_PORT:-8080}" "api"; then
    log "smoke FAILED: api did not go UP within 30s — attempting rollback"
    rollback
    die "deploy aborted; api health did not go UP within 30s"
  fi
  # WS4: the worker (:8081) restarts alongside the api but was never smoked. A worker that fails to
  # boot used to deploy 'green' and die silently (the 2026-06-04 blindspot). Smoke it too, and roll
  # back the WHOLE deploy if it does not come up — a half-up deploy (api yes, worker no) is a defect.
  if ! smoke_health "${BULLPEN_WORKER_PORT:-8081}" "worker"; then
    log "smoke FAILED: worker did not go UP within 30s — attempting rollback"
    rollback
    die "deploy aborted; worker health did not go UP within 30s"
  fi
fi

# --- 7. Record the deploy ----------------------------------------------------

mkdir -p docs/deploys
{
  echo "# Deploy $TAG"
  echo
  echo "- timestamp: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "- git rev: $REV"
  echo "- jar: $(readlink "$APP_SYMLINK")"
  echo "- previous: ${PREVIOUS_TARGET:-none}"
  echo "- smoke: $([[ "$SKIP_SMOKE" == "true" ]] && echo skipped || echo passed)"
  if command -v systemctl >/dev/null; then
    echo
    echo "## Unit status (post-restart)"
    for u in bullpen-api bullpen-worker; do
      echo "- ${u}: $(systemctl is-active "$u" 2>/dev/null)"
    done
  fi
} > "docs/deploys/${TAG}.md"
log "wrote docs/deploys/${TAG}.md"

# --- 8. Tag (best-effort) ----------------------------------------------------

if git tag -a "$TAG" -m "deploy $TAG" 2>/dev/null; then
  log "git tag created: $TAG (push it: git push origin $TAG)"
else
  log "git tag skipped (already exists or not a tag context)"
fi

# --- 9. Prune old releases ---------------------------------------------------

# Keep the last 5 releases; older ones get nuked. Rollback can only reach the
# ones still on disk, so 5 ~= one week's worth of daily deploys.
if [[ -d "$RELEASES_DIR" ]]; then
  KEEP=5
  TO_PRUNE=$(ls -1t "$RELEASES_DIR" | tail -n +$((KEEP + 1)))
  if [[ -n "$TO_PRUNE" ]]; then
    log "pruning old releases (keeping last $KEEP)"
    for old in $TO_PRUNE; do
      sudo rm -rf "${RELEASES_DIR:?}/${old}"
      log "  removed $old"
    done
  fi
fi

log "deploy complete"
