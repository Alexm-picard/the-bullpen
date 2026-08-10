#!/usr/bin/env bash
# Host bootstrap for a fresh bullpen production box (K4).
#
# Reconstructs every box-only artifact from the repo + a filled-in env file.
# Idempotent: safe to re-run on an already-configured box (creates or skips).
#
# What this does NOT do:
#   - Install system packages (java, docker, rclone, cloudflared, sqlite3, uv)
#   - Create the Cloudflare Tunnel (one-time: cloudflared tunnel login + create)
#   - Run deploy.sh (deploys the JAR + contracts)
#   - Start services (the operator decides when to enable --now)
#
# Prerequisites:
#   1. A filled-in env file (copy infra/bootstrap/env.example, fill secrets)
#   2. System packages installed: java 21, docker + compose, rclone, sqlite3
#   3. Cloudflare Tunnel created: /etc/cloudflared/<UUID>.json + config.yml
#   4. rclone configured: ~/.config/rclone/rclone.conf with bullpen-r2 remote
#
# Usage:
#   sudo infra/bootstrap/host-bootstrap.sh /path/to/filled-env-file [USERNAME]
#
# USERNAME defaults to the invoking user (SUDO_USER or whoami).
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
ENV_FILE="${1:?usage: host-bootstrap.sh /path/to/filled-env-file [USERNAME]}"
BOX_USER="${2:-${SUDO_USER:-$(whoami)}}"

log() { echo "[bootstrap] $*"; }
fail() { echo "[bootstrap] FAIL: $*" >&2; exit 1; }

[[ -f "$ENV_FILE" ]] || fail "env file not found: $ENV_FILE"
[[ "$(id -u)" -eq 0 ]] || fail "must run as root (sudo)"

# shellcheck source=/dev/null
source "$ENV_FILE"

# --- validate required secrets ---
REQUIRED=(
  THEBULLPEN_ADMIN_BASIC_AUTH
  BULLPEN_CLICKHOUSE_PASSWORD
  GRAFANA_ADMIN_PASSWORD
  S3_ACCESS_KEY_ID
  S3_SECRET_ACCESS_KEY
)
for var in "${REQUIRED[@]}"; do
  if [[ -z "${!var:-}" ]]; then
    fail "required secret $var is empty in $ENV_FILE"
  fi
done
log "all required secrets present"

# --- system user ---
if ! id -u bullpen &>/dev/null; then
  useradd --system --no-create-home --shell /usr/sbin/nologin bullpen
  log "created system user: bullpen"
else
  log "system user bullpen already exists"
fi

# --- directory structure ---
for d in /opt/bullpen /opt/bullpen/data /opt/bullpen/logs \
         /opt/bullpen/contracts /opt/bullpen/retrain-artifacts \
         /var/lib/node_exporter /etc/bullpen; do
  mkdir -p "$d"
done
chown -R bullpen:bullpen /opt/bullpen
log "directory structure ready"

# --- /etc/default/bullpen ---
cp "$ENV_FILE" /etc/default/bullpen
chmod 600 /etc/default/bullpen
log "installed /etc/default/bullpen"

# --- /etc/bullpen/secrets.env (derived from admin auth) ---
ADMIN_USER="${THEBULLPEN_ADMIN_BASIC_AUTH%%:*}"
ADMIN_PW="${THEBULLPEN_ADMIN_BASIC_AUTH#*:}"
cat > /etc/bullpen/secrets.env <<SECRETS_EOF
BULLPEN_ADMIN_USER=${ADMIN_USER}
BULLPEN_ADMIN_PASSWORD=${ADMIN_PW}
CH_ADMIN_PASSWORD=${BULLPEN_CLICKHOUSE_PASSWORD}
SECRETS_EOF
chmod 600 /etc/bullpen/secrets.env
log "installed /etc/bullpen/secrets.env"

# --- ClickHouse users.d (rendered XML) ---
if [[ -x "${REPO_ROOT}/infra/clickhouse/render-users.sh" ]]; then
  BULLPEN_CLICKHOUSE_PASSWORD="$BULLPEN_CLICKHOUSE_PASSWORD" \
    "${REPO_ROOT}/infra/clickhouse/render-users.sh"
  log "rendered ClickHouse users.d"
else
  log "WARN: render-users.sh not found; ClickHouse users.d not rendered"
fi

# --- Prometheus scrape secrets ---
if [[ -x "${REPO_ROOT}/infra/prometheus/mk-metrics-secrets.sh" ]]; then
  THEBULLPEN_ADMIN_BASIC_AUTH="$THEBULLPEN_ADMIN_BASIC_AUTH" \
  THEBULLPEN_METRICS_BASIC_AUTH="${THEBULLPEN_METRICS_BASIC_AUTH:-}" \
    "${REPO_ROOT}/infra/prometheus/mk-metrics-secrets.sh"
  log "seeded Prometheus scrape secrets"
else
  log "WARN: mk-metrics-secrets.sh not found; Prometheus secrets not seeded"
fi

# --- Alertmanager Discord webhook ---
ALERT_SECRETS_DIR="${REPO_ROOT}/infra/alertmanager/secrets"
if [[ -n "${BULLPEN_DISCORD_WEBHOOK:-}" ]]; then
  mkdir -p "$ALERT_SECRETS_DIR"
  echo -n "$BULLPEN_DISCORD_WEBHOOK" > "${ALERT_SECRETS_DIR}/discord_url"
  chmod 600 "${ALERT_SECRETS_DIR}/discord_url"
  log "installed Alertmanager discord_url"
else
  log "SKIP: BULLPEN_DISCORD_WEBHOOK not set; Alertmanager webhook not installed"
fi

# --- systemd units ---
log "installing systemd units"
"${REPO_ROOT}/infra/systemd/install.sh" --no-start

# Backup units (template instances)
for unit in bullpen-snapshot@.service "bullpen-snapshot@.timer" \
            bullpen-offsite.service "bullpen-offsite@.timer" \
            bullpen-registry-backup@.service "bullpen-registry-backup@.timer"; do
  src="${REPO_ROOT}/infra/backup/${unit}"
  if [[ -f "$src" ]]; then
    cp "$src" "/etc/systemd/system/${unit}"
  fi
done

# GPU temp units
for unit in "bullpen-gpu-temp@.service" "bullpen-gpu-temp@.timer"; do
  src="${REPO_ROOT}/infra/systemd/${unit}"
  if [[ -f "$src" ]]; then
    cp "$src" "/etc/systemd/system/${unit}"
  fi
done

systemctl daemon-reload
log "systemd units installed (not started)"

# --- sudoers fragments ---
for installer in "${REPO_ROOT}/infra/backup/install-sudoers.sh" \
                 "${REPO_ROOT}/infra/clickhouse/install-sudoers.sh"; do
  if [[ -x "$installer" ]]; then
    "$installer" "$BOX_USER"
    log "installed sudoers from $(basename "$(dirname "$installer")")"
  fi
done

# --- infra/.env for docker compose ---
INFRA_ENV="${REPO_ROOT}/infra/.env"
cat > "$INFRA_ENV" <<COMPOSE_EOF
BULLPEN_CLICKHOUSE_PASSWORD=${BULLPEN_CLICKHOUSE_PASSWORD}
GRAFANA_ADMIN_PASSWORD=${GRAFANA_ADMIN_PASSWORD}
COMPOSE_EOF
if [[ -n "${GLITCHTIP_SECRET_KEY:-}" ]]; then
  echo "GLITCHTIP_SECRET_KEY=${GLITCHTIP_SECRET_KEY}" >> "$INFRA_ENV"
fi
chmod 600 "$INFRA_ENV"
log "wrote infra/.env for docker compose"

# --- summary ---
log ""
log "Bootstrap complete. Remaining manual steps:"
log "  1. Verify Cloudflare Tunnel: /etc/cloudflared/config.yml + credentials JSON"
log "  2. Verify rclone config: ${RCLONE_CONFIG:-~/.config/rclone/rclone.conf}"
log "  3. Start docker services: cd ${REPO_ROOT}/infra && docker compose up -d"
log "  4. Deploy the app: ./deploy.sh"
log "  5. Enable timers:"
log "       systemctl enable --now bullpen-api bullpen-worker"
log "       systemctl enable --now bullpen-snapshot@${BOX_USER}.timer"
log "       systemctl enable --now bullpen-offsite@${BOX_USER}.timer"
log "       systemctl enable --now bullpen-registry-backup@${BOX_USER}.timer"
log "       systemctl enable --now bullpen-retrain.timer"
log "       systemctl enable --now bullpen-stale-claim-reaper.timer"
log "  6. Verify: curl -u <admin> http://localhost:8080/actuator/health"
