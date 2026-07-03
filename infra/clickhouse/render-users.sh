#!/usr/bin/env bash
# M2-A1: render infra/clickhouse/users.d/bullpen.xml from the committed .example by
# substituting the sha256 hex of BULLPEN_CLICKHOUSE_PASSWORD. The rendered file is
# GITIGNORED (a sha256 of the real secret is offline-brute-forceable, so it never lands
# in the public repo) and is what docker-compose mounts into the container's users.d.
#
# Usage:
#   BULLPEN_CLICKHOUSE_PASSWORD=... infra/clickhouse/render-users.sh
#   infra/clickhouse/render-users.sh --env-file infra/.env     # reads the var from the file
#
# `make services-up` runs this automatically with --env-file infra/.env, so the compose
# file-mount target always exists before `docker compose up` (a missing bind source would
# otherwise become an empty DIRECTORY inside users.d and break the server's config scan).
#
# Rollback: none needed - re-rendering is idempotent; deleting the rendered file and
# reverting BULLPEN_CLICKHOUSE_USER to `default` restores the pre-cutover state.

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEMPLATE="${HERE}/users.d/bullpen.xml.example"
OUT="${HERE}/users.d/bullpen.xml"

if [[ "${1:-}" == "--env-file" ]]; then
  ENV_FILE="${2:?--env-file requires a path}"
  [[ -f "$ENV_FILE" ]] || { echo "ERROR: env file not found: $ENV_FILE" >&2; exit 1; }
  # shellcheck disable=SC1090
  set -a; source "$ENV_FILE"; set +a
fi

: "${BULLPEN_CLICKHOUSE_PASSWORD:?set BULLPEN_CLICKHOUSE_PASSWORD (or pass --env-file infra/.env)}"

# sha256 of the raw password, hex-encoded - portable across macOS (shasum) and Linux (sha256sum).
if command -v sha256sum >/dev/null 2>&1; then
  HASH="$(printf '%s' "$BULLPEN_CLICKHOUSE_PASSWORD" | sha256sum | cut -d' ' -f1)"
else
  HASH="$(printf '%s' "$BULLPEN_CLICKHOUSE_PASSWORD" | shasum -a 256 | cut -d' ' -f1)"
fi

sed "s/__BULLPEN_CLICKHOUSE_PASSWORD_SHA256__/${HASH}/" "$TEMPLATE" > "$OUT"
chmod 600 "$OUT"
echo "rendered ${OUT} (sha256 of BULLPEN_CLICKHOUSE_PASSWORD; file mode 600, gitignored)"
