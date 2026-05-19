#!/usr/bin/env bash
# PreToolUse hook on Bash matching 'git commit'.
# Blocks the commit if docs/decisions.md was modified in any way other than pure appends.
# Exit 2 = block with message; exit 0 = allow.

set -uo pipefail

INPUT="$(cat)"
CMD=$(printf '%s' "$INPUT" | python3 -c 'import sys,json; d=json.load(sys.stdin); print(d.get("tool_input",{}).get("command",""))' 2>/dev/null || true)

# Only fire on actual commits (not git status, git log, etc.)
echo "$CMD" | grep -qE '(^|[[:space:]&;])git[[:space:]]+commit([[:space:]]|$)' || exit 0

REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
DECISIONS="$REPO_ROOT/docs/decisions.md"
[[ -f "$DECISIONS" ]] || exit 0

# Check staged diff for decisions.md
DIFF=$(cd "$REPO_ROOT" && git diff --cached --unified=0 -- docs/decisions.md 2>/dev/null)
[[ -z "$DIFF" ]] && exit 0

# Look for any removed lines (lines starting with - that aren't the diff header ---)
REMOVED=$(echo "$DIFF" | grep -E '^-[^-]' | wc -l | tr -d ' ')
if [[ "${REMOVED:-0}" -gt 0 ]]; then
  echo "BLOCKED: docs/decisions.md modification removes ${REMOVED} line(s)." >&2
  echo "docs/decisions.md is append-only per CLAUDE.md rule. Reversals add a new numbered entry referencing the original." >&2
  echo "Either: (a) restage with only additions, or (b) override with --no-verify if intentional and explicitly approved." >&2
  exit 2
fi

# Also check that additions are at end of file (no interior insertions).
# A pure-append change has no '@@ -N,M +K,L' hunks where K < (existing line count - 1).
EXISTING_LINES=$(wc -l < "$DECISIONS" | tr -d ' ')
FIRST_HUNK_START=$(echo "$DIFF" | grep -m1 -oE '\+[0-9]+' | head -1 | tr -d '+')
if [[ -n "$FIRST_HUNK_START" && "$FIRST_HUNK_START" -lt "$((EXISTING_LINES - 10))" ]]; then
  echo "BLOCKED: docs/decisions.md modification starts at line $FIRST_HUNK_START (file has $EXISTING_LINES lines)." >&2
  echo "Looks like an interior edit rather than an append. Append new entries to the bottom." >&2
  exit 2
fi

exit 0
