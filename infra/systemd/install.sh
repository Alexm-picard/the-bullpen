#!/usr/bin/env bash
# Install the bullpen-api / bullpen-worker systemd units on this WSL2 host.
#
# What this does (idempotent):
#   1. Creates the `bullpen` system user (uid auto, no shell, no home password)
#      if it doesn't already exist.
#   2. Creates /opt/bullpen + /opt/bullpen/{data,logs} owned by bullpen:bullpen.
#   3. Copies infra/systemd/bullpen-{api,worker}.service to /etc/systemd/system/.
#   4. Reloads systemd.
#   5. enable --now both units (so they survive reboot AND start now if the JAR exists).
#
# Prereqs (does NOT auto-install):
#   - Java 21 at /usr/bin/java
#   - /opt/bullpen/app.jar already deployed (run ./deploy.sh first), OR
#     start without --now and run a deploy before activating.
#
# Flags:
#   --no-start    Install + enable but do not start (use when no JAR is present yet)
#   --uninstall   Disable + stop + remove the unit files (leaves /opt/bullpen + user alone)
#
# Design notes:
#   - Service runs as the `bullpen` system user (uid 997) per the 2026-05-19
#     phase-0 brief — service-as-dedicated-system-user is the convention, not
#     service-as-dev-user. Aligns with ADR-0006's dev/prod boundary.
#   - /opt/bullpen layout: app.jar + data/ + logs/ all owned by bullpen:bullpen.
#   - Logs go to journald (queryable via `journalctl -u bullpen-api`); /opt/bullpen/logs/
#     remains for ad-hoc operator dumps if needed but isn't unit-managed.

set -euo pipefail

NO_START=false
UNINSTALL=false
for arg in "$@"; do
  case "$arg" in
    --no-start)  NO_START=true ;;
    --uninstall) UNINSTALL=true ;;
    *) echo "Unknown flag: $arg" >&2; exit 1 ;;
  esac
done

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
UNIT_DIR="${REPO_ROOT}/infra/systemd"
TARGET_DIR="/etc/systemd/system"
SVC_USER="bullpen"
INSTALL_DIR="/opt/bullpen"

UNITS=(bullpen-api.service bullpen-worker.service)

log() { printf '[install-systemd] %s\n' "$*"; }

if [[ "$UNINSTALL" == "true" ]]; then
  for u in "${UNITS[@]}"; do
    if systemctl list-unit-files | grep -q "^${u}"; then
      log "stop + disable ${u}"
      sudo systemctl stop "$u" 2>/dev/null || true
      sudo systemctl disable "$u" 2>/dev/null || true
    fi
    if [[ -f "${TARGET_DIR}/${u}" ]]; then
      sudo rm "${TARGET_DIR}/${u}"
      log "removed ${TARGET_DIR}/${u}"
    fi
  done
  sudo systemctl daemon-reload
  log "uninstall complete (NOTE: ${INSTALL_DIR} and user ${SVC_USER} left in place)"
  exit 0
fi

if ! id "$SVC_USER" >/dev/null 2>&1; then
  log "creating system user '${SVC_USER}'"
  sudo useradd --system --shell /bin/false --home-dir "$INSTALL_DIR" "$SVC_USER"
else
  log "user '${SVC_USER}' already exists (uid $(id -u "$SVC_USER"))"
fi

log "creating runtime dirs under ${INSTALL_DIR}"
sudo install -d -o "$SVC_USER" -g "$SVC_USER" -m 0755 "$INSTALL_DIR"
sudo install -d -o "$SVC_USER" -g "$SVC_USER" -m 0755 "$INSTALL_DIR/data"
sudo install -d -o "$SVC_USER" -g "$SVC_USER" -m 0755 "$INSTALL_DIR/logs"

log "installing unit files"
for u in "${UNITS[@]}"; do
  src="${UNIT_DIR}/${u}"
  dst="${TARGET_DIR}/${u}"
  if [[ ! -f "$src" ]]; then
    echo "ERROR: missing $src" >&2
    exit 1
  fi
  sudo install -o root -g root -m 0644 "$src" "$dst"
  log "  installed ${dst}"
done

log "daemon-reload"
sudo systemctl daemon-reload

if [[ "$NO_START" == "true" ]]; then
  for u in "${UNITS[@]}"; do
    sudo systemctl enable "$u"
    log "enabled (not started): ${u}"
  done
  log "done — JAR not yet deployed. Run ./deploy.sh then 'sudo systemctl start bullpen-api bullpen-worker'."
  exit 0
fi

if [[ ! -f "${INSTALL_DIR}/app.jar" ]]; then
  log "WARN: ${INSTALL_DIR}/app.jar does not exist; enabling without starting."
  log "      Run ./deploy.sh, then: sudo systemctl start bullpen-api bullpen-worker"
  for u in "${UNITS[@]}"; do
    sudo systemctl enable "$u"
    log "  enabled (not started): ${u}"
  done
  exit 0
fi

for u in "${UNITS[@]}"; do
  sudo systemctl enable --now "$u"
  sleep 1
  if sudo systemctl is-active --quiet "$u"; then
    log "  ${u} active"
  else
    log "  ${u} FAILED to start — check 'journalctl -u ${u} --since=\"1 minute ago\"'"
  fi
done

log "done"
