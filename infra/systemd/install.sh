#!/usr/bin/env bash
# Install the bullpen-api / bullpen-worker systemd units on this WSL2 host.
#
# What this does (idempotent):
#   1. Creates /opt/thebullpen + /opt/thebullpen/data + /var/log/bullpen (root-owned, alepic-writable)
#   2. Copies infra/systemd/bullpen-{api,worker}.service to /etc/systemd/system/
#   3. Reloads systemd
#   4. enable --now both units (so they survive reboot AND start now if the JAR exists)
#
# Prereqs (does NOT auto-install):
#   - Java 21 at /usr/bin/java
#   - /opt/thebullpen/app.jar already deployed (run ./deploy.sh first), OR
#     start without --now and run a deploy before activating
#
# Flags:
#   --no-start    Install + enable but do not start (use when no JAR is present yet)
#   --uninstall   Disable + stop + remove the unit files

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
USER_NAME="alepic"

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
  log "uninstall complete"
  exit 0
fi

log "creating runtime dirs"
sudo install -d -o "$USER_NAME" -g "$USER_NAME" -m 0755 /opt/thebullpen
sudo install -d -o "$USER_NAME" -g "$USER_NAME" -m 0755 /opt/thebullpen/data
sudo install -d -o "$USER_NAME" -g "$USER_NAME" -m 0755 /var/log/bullpen

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
  log "done — JAR is not yet deployed. Run ./deploy.sh then 'sudo systemctl start bullpen-api bullpen-worker'."
  exit 0
fi

if [[ ! -f /opt/thebullpen/app.jar ]]; then
  log "WARN: /opt/thebullpen/app.jar does not exist; enabling without starting."
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
