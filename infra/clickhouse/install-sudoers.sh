#!/usr/bin/env bash
# Install the narrow sudoers rule for render-users.sh (M2-B). Parallel to
# infra/backup/install-sudoers.sh; lets render-users.sh self-elevate (to chown the rendered
# users.d/bullpen.xml to the ClickHouse container uid) without a password prompt, so
# `make services-up` is seamless on the box.
#
# Behavior:
#   - Substitutes your current username + the absolute path to render-users.sh into the template
#   - Validates with `visudo -c` (refuses to install a broken rule)
#   - Copies to /etc/sudoers.d/bullpen-render-users, mode 0440, owner root:root
#
# Flags:
#   --uninstall   Remove the sudoers rule.
#   --dry-run     Print what would be installed without writing anything.

set -euo pipefail

UNINSTALL=false
DRY_RUN=false
for arg in "$@"; do
  case "$arg" in
    --uninstall) UNINSTALL=true ;;
    --dry-run)   DRY_RUN=true ;;
    *) echo "Unknown flag: $arg"; exit 1 ;;
  esac
done

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
TEMPLATE="${REPO_ROOT}/infra/clickhouse/sudoers.d/bullpen-render-users.template"
TARGET="/etc/sudoers.d/bullpen-render-users"
USER_NAME="$(whoami)"
SCRIPT_PATH="${REPO_ROOT}/infra/clickhouse/render-users.sh"

if [[ "$UNINSTALL" == "true" ]]; then
  if [[ -f "$TARGET" ]]; then
    sudo rm "$TARGET"; echo "Removed $TARGET"
  else
    echo "$TARGET does not exist - nothing to remove."
  fi
  exit 0
fi

[[ -f "$TEMPLATE" ]] || { echo "ERROR: template not found at $TEMPLATE"; exit 1; }
[[ -x "$SCRIPT_PATH" ]] || { echo "ERROR: $SCRIPT_PATH must exist and be executable. Run: chmod +x $SCRIPT_PATH"; exit 1; }

RENDERED=$(sed -e "s|{{USER}}|${USER_NAME}|g" -e "s|{{SCRIPT_PATH}}|${SCRIPT_PATH}|g" "$TEMPLATE")

TMP_FILE=$(mktemp)
trap 'rm -f "$TMP_FILE"' EXIT
printf '%s\n' "$RENDERED" > "$TMP_FILE"

if ! sudo visudo -c -f "$TMP_FILE" >/dev/null; then
  echo "ERROR: rendered sudoers content failed visudo -c. Aborting."
  echo "---"; cat "$TMP_FILE"; echo "---"
  exit 1
fi

if [[ "$DRY_RUN" == "true" ]]; then
  echo "=== DRY RUN - would install the following to $TARGET ==="
  cat "$TMP_FILE"
  echo "=== (not written) ==="
  exit 0
fi

sudo install -o root -g root -m 0440 "$TMP_FILE" "$TARGET"
echo "Installed: $TARGET"
echo ""
echo "Rule:"; sudo cat "$TARGET" | grep -v '^#' | grep -v '^$'
echo ""
echo "Test it:"
echo "  make services-up      # render self-elevates with NO password prompt"
echo "  sudo ls /root         # should STILL prompt (rule is narrow)"
echo ""
echo "Remove later via: $0 --uninstall"
