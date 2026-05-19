#!/usr/bin/env bash
# PreToolUse hook on Bash matching 'git push'.
# Non-blocking reminder when pushing to main during a likely live-game window.
# Always exits 0; just prints to stderr.

set -uo pipefail

INPUT="$(cat)"
CMD=$(printf '%s' "$INPUT" | python3 -c 'import sys,json; d=json.load(sys.stdin); print(d.get("tool_input",{}).get("command",""))' 2>/dev/null || true)

echo "$CMD" | grep -qE 'git[[:space:]]+push' || exit 0

# Only flag pushes that touch main
echo "$CMD" | grep -qE '(main|HEAD:main|origin[[:space:]]+main)' || {
  # Default branch push without explicit ref also goes to main on this repo
  CURRENT_BRANCH=$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo unknown)
  [[ "$CURRENT_BRANCH" != "main" ]] && exit 0
}

# Time check: April-October (months 4-10), 16:00-23:59 ET
# Operator may not be in ET; approximate with system clock + offset
MONTH=$(date +%m)
HOUR=$(date +%H)
MONTH_N=$((10#$MONTH))
HOUR_N=$((10#$HOUR))

if [[ $MONTH_N -ge 4 && $MONTH_N -le 10 && $HOUR_N -ge 16 && $HOUR_N -le 23 ]]; then
  echo "" >&2
  echo "REMINDER: It's $(date '+%Y-%m-%d %H:%M %Z'), which may be inside a live-game window (CLAUDE.md rule 3)." >&2
  echo "If real users are watching predictions right now, defer this deploy until after games end." >&2
  echo "(This is a reminder, not a block. Continuing in 2s — Ctrl+C to abort.)" >&2
  sleep 2
fi

exit 0
