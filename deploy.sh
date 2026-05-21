#!/usr/bin/env bash
# deploy.sh — Phase 0 deploy script for The Bullpen.
#
# Prefer the deploy-safely skill (/deploy-safely) over invoking this directly:
# it wraps this script with the live-game-window check, git tag, and post-deploy
# smoke verification (CLAUDE.md discipline rule 3, decision [21]).
#
# Per ADR-0006, this script is the *only* writer of the prod working copy.
# It's intended to run ON the WSL2 desktop, against the live install at
# /opt/thebullpen. From the MacBook side, you ssh in and invoke this — or
# better, push to main and run it here via /deploy-safely.
#
# What it does:
#   1. Pre-flight: clean working tree (or --allow-dirty), systemd units present.
#   2. Build the backend bootJar (no tests — CI is the test gate per [20]).
#   3. Stage the JAR into /opt/thebullpen/releases/<TAG>/app.jar.
#   4. Atomic symlink swap: /opt/thebullpen/app.jar -> releases/<TAG>/app.jar
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

INSTALL_DIR="/opt/thebullpen"
RELEASES_DIR="${INSTALL_DIR}/releases"
APP_SYMLINK="${INSTALL_DIR}/app.jar"

TAG="v$(date -u +%Y.%m.%d-%H%M)"
ALLOW_DIRTY=false
SKIP_SMOKE=false
for arg in "$@"; do
  case "$arg" in
    --allow-dirty) ALLOW_DIRTY=true ;;
    --skip-smoke)  SKIP_SMOKE=true ;;
    *) echo "Unknown flag: $arg" >&2; exit 2 ;;
  esac
done

log() { printf '[deploy %s] %s\n' "$TAG" "$*"; }
die() { printf '[deploy %s] ERROR: %s\n' "$TAG" "$*" >&2; exit 1; }

# --- 1. Pre-flight -----------------------------------------------------------

if ! command -v java >/dev/null 2>&1; then
  die "java not found in PATH (need Java 21)"
fi

if [[ ! -d "$INSTALL_DIR" ]]; then
  die "$INSTALL_DIR does not exist — run infra/systemd/install.sh first"
fi

if ! systemctl list-unit-files 2>/dev/null | grep -q '^bullpen-api\.service'; then
  die "bullpen-api.service not installed — run infra/systemd/install.sh first"
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

JAR_SRC="$(ls -1 backend/build/libs/*.jar 2>/dev/null | head -1)"
[[ -f "$JAR_SRC" ]] || die "no jar produced under backend/build/libs/"
log "built $(basename "$JAR_SRC") ($(du -h "$JAR_SRC" | cut -f1))"

# --- 3. Stage ----------------------------------------------------------------

RELEASE_DIR="${RELEASES_DIR}/${TAG}"
sudo install -d -o alepic -g alepic -m 0755 "$RELEASES_DIR"
sudo install -d -o alepic -g alepic -m 0755 "$RELEASE_DIR"
sudo install -o alepic -g alepic -m 0644 "$JAR_SRC" "${RELEASE_DIR}/app.jar"
log "staged ${RELEASE_DIR}/app.jar"

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
  log "smoke: waiting up to 30s for /actuator/health to go green"
  HEALTH_OK=false
  for i in $(seq 1 30); do
    sleep 1
    if curl -fsS "http://localhost:${BULLPEN_API_PORT:-8080}/actuator/health" 2>/dev/null \
        | grep -q '"status":"UP"'; then
      HEALTH_OK=true
      log "smoke OK after ${i}s"
      break
    fi
  done

  if [[ "$HEALTH_OK" != "true" ]]; then
    log "smoke FAILED — attempting rollback"
    if [[ -n "$PREVIOUS_TARGET" ]]; then
      sudo ln -snf "$PREVIOUS_TARGET" "$TMP_LINK"
      sudo mv -Tf "$TMP_LINK" "$APP_SYMLINK"
      sudo systemctl restart bullpen-api bullpen-worker
      log "rolled back to $PREVIOUS_TARGET"
    else
      log "no previous release to roll back to"
    fi
    die "deploy aborted; health check did not go UP within 30s"
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
