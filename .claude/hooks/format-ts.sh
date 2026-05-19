#!/usr/bin/env bash
# PostToolUse hook: format edited TypeScript/JavaScript files with Prettier.
# Always exits 0; never blocks editing.

set -uo pipefail

INPUT="$(cat)"
FILE=$(printf '%s' "$INPUT" | python3 -c 'import sys,json; d=json.load(sys.stdin); print(d.get("tool_input",{}).get("file_path",""))' 2>/dev/null || true)

[[ -z "$FILE" ]] && exit 0
case "$FILE" in
  *.ts|*.tsx|*.js|*.jsx|*.json|*.css|*.html|*.md) ;;
  *) exit 0 ;;
esac
[[ ! -f "$FILE" ]] && exit 0

REPO_ROOT="${REPO_ROOT:-/home/$(whoami)/code/thebullpen}"
[[ -d "$REPO_ROOT/frontend" ]] || REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"

if [[ -f "$REPO_ROOT/frontend/package.json" ]]; then
  (cd "$REPO_ROOT/frontend" && npx --no-install prettier --write "$FILE" 2>/dev/null) || true
fi

exit 0
