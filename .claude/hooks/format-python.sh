#!/usr/bin/env bash
# PostToolUse hook: format edited Python files with ruff.
# Always exits 0; never blocks editing.

set -uo pipefail

INPUT="$(cat)"
FILE=$(printf '%s' "$INPUT" | python3 -c 'import sys,json; d=json.load(sys.stdin); print(d.get("tool_input",{}).get("file_path",""))' 2>/dev/null || true)

[[ -z "$FILE" ]] && exit 0
[[ "$FILE" != *.py ]] && exit 0
[[ ! -f "$FILE" ]] && exit 0

REPO_ROOT="${REPO_ROOT:-/home/$(whoami)/code/thebullpen}"
[[ -d "$REPO_ROOT/training" ]] || REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"

if command -v uv >/dev/null 2>&1; then
  (cd "$REPO_ROOT" && uv run ruff format "$FILE" 2>/dev/null) || true
  (cd "$REPO_ROOT" && uv run ruff check --fix --quiet "$FILE" 2>/dev/null) || true
elif command -v ruff >/dev/null 2>&1; then
  ruff format "$FILE" 2>/dev/null || true
  ruff check --fix --quiet "$FILE" 2>/dev/null || true
fi

exit 0
