#!/usr/bin/env bash
# Seed the Prometheus scrape secrets for the ADMIN-gated /actuator/prometheus (M5) from
# THEBULLPEN_ADMIN_BASIC_AUTH ("user:password"). Writes the RAW user-half to secrets/metrics_user
# and the RAW password-half to secrets/metrics_pw (both with NO trailing newline), matching
# prometheus.yml's basic_auth.username_file + password_file. ANY username works - nothing is
# hardcoded, so this can never drift from the env the way an inline username could.
#
# Run on the box after a deploy/pull, then `docker compose -f infra/docker-compose.yml up -d
# prometheus`. The real metrics_user / metrics_pw are gitignored (infra/prometheus/secrets/* except
# *.example); this script + the *.example templates are committed (ADR-0006 reproducibility).
set -euo pipefail

: "${THEBULLPEN_ADMIN_BASIC_AUTH:?set THEBULLPEN_ADMIN_BASIC_AUTH=user:password (see /etc/default/bullpen)}"
case "$THEBULLPEN_ADMIN_BASIC_AUTH" in
  *:*) ;;
  *) echo "ERROR: THEBULLPEN_ADMIN_BASIC_AUTH must be in user:password form" >&2; exit 1 ;;
esac

secrets="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/secrets"
mkdir -p "$secrets"
# %%:* = everything before the FIRST colon (the username); #*: = everything after the FIRST colon
# (the password, which may itself contain colons - both halves are split on the first colon only).
printf '%s' "${THEBULLPEN_ADMIN_BASIC_AUTH%%:*}" > "$secrets/metrics_user"
printf '%s' "${THEBULLPEN_ADMIN_BASIC_AUTH#*:}" > "$secrets/metrics_pw"
chmod 600 "$secrets/metrics_user" "$secrets/metrics_pw"

echo "wrote $secrets/metrics_user + $secrets/metrics_pw (basic_auth username_file + password_file)"
echo "now run: docker compose -f infra/docker-compose.yml up -d prometheus"
