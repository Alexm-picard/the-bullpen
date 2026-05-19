#!/usr/bin/env bash
# Install the narrow sudoers rule for usb-backup.sh.
#
# Behavior:
#   - Substitutes your current username and the absolute path to usb-backup.sh into the template
#   - Validates the result with `visudo -c` (refuses to install a broken rule)
#   - Copies to /etc/sudoers.d/bullpen-backup with mode 0440, owner root:root
#   - Prints next-step instructions for testing
#
# Flags:
#   --hardened    Use /usr/local/sbin/bullpen-usb-backup as the allowed path (you must
#                 manually copy the script there with root ownership first).
#   --uninstall   Remove the sudoers rule.
#   --dry-run     Print what would be installed without writing anything.

set -euo pipefail

HARDENED=false
UNINSTALL=false
DRY_RUN=false
for arg in "$@"; do
  case "$arg" in
    --hardened)  HARDENED=true ;;
    --uninstall) UNINSTALL=true ;;
    --dry-run)   DRY_RUN=true ;;
    *) echo "Unknown flag: $arg"; exit 1 ;;
  esac
done

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
TEMPLATE="${REPO_ROOT}/infra/backup/sudoers.d/bullpen-backup.template"
TARGET="/etc/sudoers.d/bullpen-backup"
USER_NAME="$(whoami)"

if [[ "$UNINSTALL" == "true" ]]; then
  if [[ -f "$TARGET" ]]; then
    sudo rm "$TARGET"
    echo "Removed $TARGET"
  else
    echo "$TARGET does not exist — nothing to remove."
  fi
  exit 0
fi

if [[ ! -f "$TEMPLATE" ]]; then
  echo "ERROR: template not found at $TEMPLATE"
  exit 1
fi

if [[ "$HARDENED" == "true" ]]; then
  SCRIPT_PATH="/usr/local/sbin/bullpen-usb-backup"
  if [[ ! -f "$SCRIPT_PATH" ]]; then
    echo "ERROR: --hardened requires $SCRIPT_PATH to exist."
    echo "Copy first: sudo install -o root -g root -m 0755 ${REPO_ROOT}/infra/backup/usb-backup.sh $SCRIPT_PATH"
    exit 1
  fi
else
  SCRIPT_PATH="${REPO_ROOT}/infra/backup/usb-backup.sh"
  if [[ ! -x "$SCRIPT_PATH" ]]; then
    echo "ERROR: $SCRIPT_PATH must exist and be executable. Run: chmod +x $SCRIPT_PATH"
    exit 1
  fi
fi

# Build the rule by substituting template vars
RENDERED=$(sed -e "s|{{USER}}|${USER_NAME}|g" -e "s|{{SCRIPT_PATH}}|${SCRIPT_PATH}|g" "$TEMPLATE")

# Validate via visudo against a temp file before touching the real one
TMP_FILE=$(mktemp)
trap 'rm -f "$TMP_FILE"' EXIT
printf '%s\n' "$RENDERED" > "$TMP_FILE"

if ! sudo visudo -c -f "$TMP_FILE" >/dev/null; then
  echo "ERROR: rendered sudoers content failed visudo -c. Aborting."
  echo "Rendered content:"
  echo "---"
  cat "$TMP_FILE"
  echo "---"
  exit 1
fi

if [[ "$DRY_RUN" == "true" ]]; then
  echo "=== DRY RUN — would install the following to $TARGET ==="
  cat "$TMP_FILE"
  echo "=== (not written) ==="
  exit 0
fi

# Install: owner root:root, mode 0440 (sudoers.d requires 0440)
sudo install -o root -g root -m 0440 "$TMP_FILE" "$TARGET"

echo "Installed: $TARGET"
echo ""
echo "Rule:"
sudo cat "$TARGET" | grep -v '^#' | grep -v '^$'
echo ""
echo "Test it:"
echo "  ${SCRIPT_PATH}      # should NOT prompt for password"
echo "  sudo ls /root       # should STILL prompt for password (rule is narrow)"
echo ""
echo "Remove later via: $0 --uninstall"
