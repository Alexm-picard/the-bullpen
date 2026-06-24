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

# Prometheus runs as nobody (uid 65534) in-container and reads the bind-mounted secret, so on the
# Linux box the file must be 0400 AND owned by that uid - which needs root (run this with sudo). The
# chown is what makes a fresh restore work without a hand-rolled wrapper. Docker Desktop (macOS dev)
# maps uids so the container reads the mount regardless, so the chown is skipped there.
chmod 0400 "$secrets/metrics_user" "$secrets/metrics_pw"
if [ "$(uname -s)" = "Linux" ]; then
  if ! chown 65534:65534 "$secrets/metrics_user" "$secrets/metrics_pw" 2>/dev/null; then
    echo "ERROR: chown to 65534:65534 (nobody) failed - re-run as root (sudo). Prometheus reads the" >&2
    echo "       0400 secret as nobody(65534) in-container; without this chown the scrape cannot read" >&2
    echo "       it and the thebullpen-api/-worker targets stay DOWN." >&2
    exit 1
  fi
fi

echo "wrote $secrets/metrics_user + $secrets/metrics_pw (0400; chowned to nobody:nobody on Linux)"
echo "for prometheus.yml basic_auth username_file + password_file"
echo "now run: docker compose -f infra/docker-compose.yml up -d prometheus"
