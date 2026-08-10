#!/usr/bin/env bash
# M2-A1/B6: render infra/clickhouse/users.d/bullpen.xml from the committed .example by
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
# TWO things this script has to get right, both learned at the 2026-07-03 window:
#
#   1. Container-readable ownership (Linux/box only). The ClickHouse container runs as uid
#      101; a file owned by the host user at mode 600 is NOT readable by 101, so CH would
#      ignore the bullpen user file - and an XML user that fails to load is WORSE than absent
#      (a grants-less/unloaded user silently gets full access - see bullpen.xml.example). The
#      chown to 101 needs root, so on Linux this script SELF-ELEVATES via sudo (mirrors
#      infra/backup/usb-backup.sh). A scoped NOPASSWD rule
#      (infra/clickhouse/sudoers.d/bullpen-render-users.template, installed by
#      infra/clickhouse/install-sudoers.sh) makes `make services-up` seamless; without it
#      sudo just prompts once. On macOS dev, Docker Desktop maps uids, so there is no chown
#      and no elevation - the script runs unprivileged and least-priv is a box-only concern.
#
#   2. Inode-preserving write. A Docker single-file bind mount pins the container to the
#      file's inode at create time. A rename/mv swaps the inode and strands the mount on the
#      deleted old file, so we render into a temp and then TRUNCATE-IN-PLACE (`cat tmp > OUT`)
#      to keep OUT's inode stable. Running the whole script as one privilege (root, via the
#      self-elevate) is what makes the re-render idempotent: after the first chown, OUT is
#      owned by 101, and only root can truncate-in-place it. NOTE: even with the inode
#      preserved, ClickHouse only reads users.d at STARTUP, so a re-render still requires a CH
#      force-recreate to take effect (the script prints the exact command on success).
#
# Rollback: none needed - re-rendering is idempotent; deleting the rendered file and
# reverting BULLPEN_CLICKHOUSE_USER to `default` restores the pre-cutover state.

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SELF="${HERE}/render-users.sh"
TEMPLATE="${HERE}/users.d/bullpen.xml.example"
OUT="${HERE}/users.d/bullpen.xml"
CH_CONTAINER_UID="${CH_CONTAINER_UID:-101}"

# Self-elevate on Linux (the box) so the write + chown-to-101 happen with one privilege and
# the re-render stays idempotent (note 1). Re-exec by ABSOLUTE path ($SELF) so the NOPASSWD
# sudoers rule (which whitelists that exact path) matches regardless of how make invoked us.
# --preserve-env carries a password passed by env; a --env-file arg re-sources under root via
# "$@". macOS dev never elevates (no uid 101 to match; Docker Desktop maps ownership).
if [[ "$(uname -s)" == "Linux" && "${EUID:-$(id -u)}" -ne 0 ]]; then
  exec sudo --preserve-env=BULLPEN_CLICKHOUSE_PASSWORD,CH_CONTAINER_UID,TMPDIR "$SELF" "$@"
fi

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

# Render into a temp OUTSIDE the repo (system temp), then truncate-in-place so OUT keeps its
# inode (see note 2). Temp-outside-repo so a SIGKILL between mktemp and the trap can't strand a
# secret-hash-bearing file in a committed directory; `cat TMP > OUT` is a copy (not a rename),
# so TMP being on another filesystem is fine. If OUT does not exist yet, `cat >` creates it.
TMP="$(mktemp "${TMPDIR:-/tmp}/bullpen-ch-users.XXXXXX")"
trap 'rm -f "$TMP"' EXIT
sed "s/__BULLPEN_CLICKHOUSE_PASSWORD_SHA256__/${HASH}/" "$TEMPLATE" > "$TMP"
cat "$TMP" > "$OUT"
chmod 600 "$OUT"

# chown to the CH container uid so uid 101 can read it (note 1). On Linux we are root here
# (self-elevated above), so a failure is a real error - let set -e surface it. On macOS,
# skip: Docker Desktop maps ownership and least-priv is box-only.
if [[ "$(uname -s)" == "Linux" ]]; then
  chown "${CH_CONTAINER_UID}:${CH_CONTAINER_UID}" "$OUT"
fi

echo "rendered ${OUT} (sha256 of BULLPEN_CLICKHOUSE_PASSWORD; mode 600, gitignored)"
echo "users.d changed: force-recreate ClickHouse for it to take effect ->"
echo "  docker compose -f infra/docker-compose.yml --env-file infra/.env up -d --force-recreate clickhouse"
