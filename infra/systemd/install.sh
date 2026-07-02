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
  # WS6 / D1 + P2: tear down the snapshot/offsite timers + template services. Handles both the
  # legacy plain timer name (bullpen-snapshot.timer, pre-templating) and the current @-template
  # instances, so re-running uninstall after the timer templating migrates cleanly.
  for stem in bullpen-snapshot bullpen-offsite; do
    if systemctl list-unit-files | grep -q "^${stem}\.timer"; then
      log "stop + disable ${stem}.timer (legacy plain unit)"
      sudo systemctl stop "${stem}.timer" 2>/dev/null || true
      sudo systemctl disable "${stem}.timer" 2>/dev/null || true
    fi
    while read -r inst _; do
      [[ -n "$inst" ]] || continue
      log "stop + disable ${inst}"
      sudo systemctl stop "$inst" 2>/dev/null || true
      sudo systemctl disable "$inst" 2>/dev/null || true
    done < <(systemctl list-units --all --no-legend "${stem}@*.timer" 2>/dev/null || true)
    for f in "${stem}.timer" "${stem}@.timer" "${stem}@.service"; do
      if [[ -f "${TARGET_DIR}/${f}" ]]; then
        sudo rm "${TARGET_DIR}/${f}"
        log "removed ${TARGET_DIR}/${f}"
      fi
    done
  done
  # WS3: tear down the retrain + stale-claim-reaper job timers (plain units).
  for t in bullpen-retrain.timer bullpen-stale-claim-reaper.timer; do
    if systemctl list-unit-files | grep -q "^${t}"; then
      log "stop + disable ${t}"
      sudo systemctl stop "$t" 2>/dev/null || true
      sudo systemctl disable "$t" 2>/dev/null || true
    fi
  done
  for f in bullpen-retrain.timer bullpen-retrain.service \
           bullpen-stale-claim-reaper.timer bullpen-stale-claim-reaper.service; do
    if [[ -f "${TARGET_DIR}/${f}" ]]; then
      sudo rm "${TARGET_DIR}/${f}"
      log "removed ${TARGET_DIR}/${f}"
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

# M1-R3: the INSTALL path must also retire the LEGACY plain snapshot/offsite timers when
# present, not just uninstall. The 2026-07-02 box finding: installing the @-templates left
# the pre-templating plain units live alongside them, and both sets would have double-fired
# at 03:0x/03:3x. A fresh bootstrap or restore drill re-running this script must converge on
# exactly one timer of each - so retire the plain names here, idempotently.
for stem in bullpen-snapshot bullpen-offsite; do
  if systemctl list-unit-files 2>/dev/null | grep -q "^${stem}\.timer"; then
    log "retiring legacy plain ${stem}.timer (replaced by the ${stem}@<user>.timer template)"
    sudo systemctl disable --now "${stem}.timer" 2>/dev/null || true
  fi
  if [[ -f "${TARGET_DIR}/${stem}.timer" ]]; then
    sudo rm "${TARGET_DIR}/${stem}.timer"
    log "  removed ${TARGET_DIR}/${stem}.timer"
  fi
done

# WS6 / D1: also install the daily snapshot units (rule 8 forcing function). BOTH units are
# TEMPLATES - %i is the operator user (ExecStart=/home/%i/... in the .service; the .timer's
# Unit= names bullpen-snapshot@%i.service), so no username is hardcoded in a committed unit.
# The timer instance (not the service) is what gets enabled, below.
SNAPSHOT_SVC_SRC="${REPO_ROOT}/infra/backup/bullpen-snapshot.service"
SNAPSHOT_TIMER_SRC="${REPO_ROOT}/infra/backup/bullpen-snapshot@.timer"
INSTALL_SNAPSHOT=false
if [[ -f "$SNAPSHOT_SVC_SRC" && -f "$SNAPSHOT_TIMER_SRC" ]]; then
  sudo install -o root -g root -m 0644 "$SNAPSHOT_SVC_SRC" "${TARGET_DIR}/bullpen-snapshot@.service"
  sudo install -o root -g root -m 0644 "$SNAPSHOT_TIMER_SRC" "${TARGET_DIR}/bullpen-snapshot@.timer"
  log "  installed ${TARGET_DIR}/bullpen-snapshot@.service + bullpen-snapshot@.timer"
  INSTALL_SNAPSHOT=true
else
  log "  WARN: snapshot units not found under infra/backup; skipping the snapshot timer"
fi

# P2 (decision [153] / ADR-0007): the offsite (R2) push units - same template shape as the
# snapshot pair, fired at 03:30 after the 03:00 local snapshot. The script no-ops unless
# BULLPEN_OFFSITE_REMOTE is set in /etc/default/bullpen, so enabling the timer is safe
# before the env is staged.
OFFSITE_SVC_SRC="${REPO_ROOT}/infra/backup/bullpen-offsite.service"
OFFSITE_TIMER_SRC="${REPO_ROOT}/infra/backup/bullpen-offsite@.timer"
INSTALL_OFFSITE=false
if [[ -f "$OFFSITE_SVC_SRC" && -f "$OFFSITE_TIMER_SRC" ]]; then
  sudo install -o root -g root -m 0644 "$OFFSITE_SVC_SRC" "${TARGET_DIR}/bullpen-offsite@.service"
  sudo install -o root -g root -m 0644 "$OFFSITE_TIMER_SRC" "${TARGET_DIR}/bullpen-offsite@.timer"
  log "  installed ${TARGET_DIR}/bullpen-offsite@.service + bullpen-offsite@.timer"
  INSTALL_OFFSITE=true
else
  log "  WARN: offsite units not found under infra/backup; skipping the offsite timer"
fi

# M1 task 7: the GPU thermal textfile collector - a 30s sample of nvidia-smi into
# /var/lib/node_exporter/gpu_temp.prom, feeding the GpuTempHigh/GpuTempCritical rules. Same
# template shape as the snapshot pair (script path is /home/%i/code/the-bullpen/...). The
# script self-skips (exit 0) on hosts without nvidia-smi, so enabling is safe everywhere.
GPU_SVC_SRC="${UNIT_DIR}/bullpen-gpu-temp@.service"
GPU_TIMER_SRC="${UNIT_DIR}/bullpen-gpu-temp@.timer"
INSTALL_GPU_TEMP=false
if [[ -f "$GPU_SVC_SRC" && -f "$GPU_TIMER_SRC" ]]; then
  sudo install -o root -g root -m 0644 "$GPU_SVC_SRC" "${TARGET_DIR}/bullpen-gpu-temp@.service"
  sudo install -o root -g root -m 0644 "$GPU_TIMER_SRC" "${TARGET_DIR}/bullpen-gpu-temp@.timer"
  log "  installed ${TARGET_DIR}/bullpen-gpu-temp@.service + bullpen-gpu-temp@.timer"
  INSTALL_GPU_TEMP=true
else
  log "  WARN: gpu-temp units not found under infra/systemd; skipping the GPU thermal timer"
fi

# WS3 (decision [19]): the worker-profile JOB timers - the nightly retrain (02-06 ET, drives the
# retraining queue) and the stale-claim reaper (every 30 min). Plain (non-template) units that live
# in infra/systemd alongside the app units. The timers (not the .service units) get enabled.
# Installing them was previously missed: the units existed in-repo but install.sh never copied them.
JOB_TIMER_UNITS=(
  bullpen-retrain.service bullpen-retrain.timer
  bullpen-stale-claim-reaper.service bullpen-stale-claim-reaper.timer
)
INSTALL_JOB_TIMERS=false
if [[ -f "${UNIT_DIR}/bullpen-retrain.timer" && -f "${UNIT_DIR}/bullpen-stale-claim-reaper.timer" ]]; then
  for u in "${JOB_TIMER_UNITS[@]}"; do
    sudo install -o root -g root -m 0644 "${UNIT_DIR}/${u}" "${TARGET_DIR}/${u}"
    log "  installed ${TARGET_DIR}/${u}"
  done
  INSTALL_JOB_TIMERS=true
else
  log "  WARN: retrain/reaper units not found under infra/systemd; skipping the job timers"
fi

log "daemon-reload"
sudo systemctl daemon-reload

# Enable the snapshot timer regardless of the app.jar (it does not depend on the running service).
# Template timers: the instance is the operator user, matching the @.service %i.
INSTANCE_USER="${SUDO_USER:-$(whoami)}"
if [[ "$INSTALL_SNAPSHOT" == "true" ]]; then
  if [[ "$NO_START" == "true" ]]; then
    sudo systemctl enable "bullpen-snapshot@${INSTANCE_USER}.timer"
    log "enabled (not started): bullpen-snapshot@${INSTANCE_USER}.timer"
  else
    sudo systemctl enable --now "bullpen-snapshot@${INSTANCE_USER}.timer"
    log "enabled + started: bullpen-snapshot@${INSTANCE_USER}.timer (fires daily at 03:00 local)"
  fi
fi

if [[ "$INSTALL_OFFSITE" == "true" ]]; then
  if [[ "$NO_START" == "true" ]]; then
    sudo systemctl enable "bullpen-offsite@${INSTANCE_USER}.timer"
    log "enabled (not started): bullpen-offsite@${INSTANCE_USER}.timer"
  else
    sudo systemctl enable --now "bullpen-offsite@${INSTANCE_USER}.timer"
    log "enabled + started: bullpen-offsite@${INSTANCE_USER}.timer (fires daily at 03:30 local; no-ops until BULLPEN_OFFSITE_REMOTE is set)"
  fi
fi

if [[ "$INSTALL_GPU_TEMP" == "true" ]]; then
  if [[ "$NO_START" == "true" ]]; then
    sudo systemctl enable "bullpen-gpu-temp@${INSTANCE_USER}.timer"
    log "enabled (not started): bullpen-gpu-temp@${INSTANCE_USER}.timer"
  else
    sudo systemctl enable --now "bullpen-gpu-temp@${INSTANCE_USER}.timer"
    log "enabled + started: bullpen-gpu-temp@${INSTANCE_USER}.timer (30s GPU thermal sample)"
  fi
fi

# Job timers (retrain + stale-claim reaper). Enable the .timer units; they do not depend on app.jar.
if [[ "$INSTALL_JOB_TIMERS" == "true" ]]; then
  for t in bullpen-retrain.timer bullpen-stale-claim-reaper.timer; do
    if [[ "$NO_START" == "true" ]]; then
      sudo systemctl enable "$t"
      log "enabled (not started): ${t}"
    else
      sudo systemctl enable --now "$t"
      log "enabled + started: ${t}"
    fi
  done
fi

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
