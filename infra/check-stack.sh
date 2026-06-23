#!/usr/bin/env bash
#
# M3 - stack verification. Fail LOUD if the containers that are supposed to be running (per the
# configured compose profiles) are missing or unhealthy. The monitoring (Prometheus alerting) and
# error-tracking (GlitchTip) stacks live behind opt-in compose profiles, so without this nothing
# catches a stack that silently never came up - alerting absent without anyone knowing (the exact
# OOM/missing-alerting scenario M3 guards against).
#
# Run it after `docker compose ... up -d` (e.g. from deploy.sh) and on a schedule (systemd timer).
#
# Usage:
#   infra/check-stack.sh                                   # core + monitoring + errortracking
#   BULLPEN_STACK_PROFILES="monitoring" infra/check-stack.sh   # core + monitoring only
#   BULLPEN_STACK_PROFILES="" infra/check-stack.sh             # core only
#
set -euo pipefail

# Default to the full prod set; override with BULLPEN_STACK_PROFILES (space-separated, may be empty).
PROFILES="${BULLPEN_STACK_PROFILES-monitoring errortracking}"

# Always-on (the default compose profile).
REQUIRED=(bullpen-clickhouse bullpen-prometheus bullpen-grafana)

case " ${PROFILES} " in
  *" monitoring "*) REQUIRED+=(bullpen-node-exporter bullpen-alertmanager) ;;
esac
case " ${PROFILES} " in
  *" errortracking "*)
    # bullpen-glitchtip-migrate is a one-shot job (runs ./manage.py migrate then exits), so it is
    # deliberately NOT required to be running.
    REQUIRED+=(bullpen-glitchtip-postgres bullpen-glitchtip-redis bullpen-glitchtip-web bullpen-glitchtip-worker)
    ;;
esac

fail=0
for name in "${REQUIRED[@]}"; do
  state="$(docker inspect -f '{{.State.Status}}' "${name}" 2>/dev/null || echo absent)"
  if [[ "${state}" != "running" ]]; then
    echo "FAIL: ${name} is ${state} (expected running)"
    fail=1
    continue
  fi
  health="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "${name}" 2>/dev/null || echo unknown)"
  if [[ "${health}" == "unhealthy" ]]; then
    echo "FAIL: ${name} is running but unhealthy"
    fail=1
  else
    echo "OK:   ${name} (running, health=${health})"
  fi
done

if [[ "${fail}" -ne 0 ]]; then
  echo
  echo "Stack verification FAILED - required containers are missing or unhealthy."
  echo "Bring the full stack up with:"
  echo "  docker compose -f infra/docker-compose.yml --profile monitoring --profile errortracking up -d"
  exit 1
fi

echo
echo "Stack verification passed (${#REQUIRED[@]} containers checked)."
