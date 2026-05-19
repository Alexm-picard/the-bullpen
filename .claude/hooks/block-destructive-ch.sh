#!/usr/bin/env bash
# PreToolUse hook on Bash.
# Blocks destructive ClickHouse operations (DROP, TRUNCATE, ALTER) unless a snapshot
# file under /var/lib/clickhouse-backup/ (or REPO_ROOT/snapshots/clickhouse/) exists
# and is newer than 1 hour.
# Exit 2 = block; exit 0 = allow.

set -uo pipefail

INPUT="$(cat)"
CMD=$(printf '%s' "$INPUT" | python3 -c 'import sys,json; d=json.load(sys.stdin); print(d.get("tool_input",{}).get("command",""))' 2>/dev/null || true)

# Look for destructive CH operations
UPPER=$(echo "$CMD" | tr '[:lower:]' '[:upper:]')
echo "$UPPER" | grep -qE '(DROP[[:space:]]+(TABLE|DATABASE|PARTITION)|TRUNCATE[[:space:]]+TABLE|ALTER[[:space:]]+TABLE.*DROP)' || exit 0

# Confirm it looks like ClickHouse context (not SQLite registry)
echo "$CMD" | grep -qiE '(clickhouse|9000|8123|prediction_logs|pitches|drift_metrics)' || exit 0

SNAPSHOT_DIRS=(
  "/var/lib/clickhouse-backup"
  "$(git rev-parse --show-toplevel 2>/dev/null)/snapshots/clickhouse"
  "${HOME}/clickhouse-backups"
)

RECENT_SNAPSHOT=""
for d in "${SNAPSHOT_DIRS[@]}"; do
  [[ -d "$d" ]] || continue
  # find any file modified in last 60 minutes
  FOUND=$(find "$d" -type f -mmin -60 2>/dev/null | head -1)
  if [[ -n "$FOUND" ]]; then
    RECENT_SNAPSHOT="$FOUND"
    break
  fi
done

if [[ -z "$RECENT_SNAPSHOT" ]]; then
  echo "" >&2
  echo "BLOCKED: Destructive ClickHouse operation detected:" >&2
  echo "  $CMD" >&2
  echo "" >&2
  echo "No snapshot found newer than 1 hour in any of:" >&2
  for d in "${SNAPSHOT_DIRS[@]}"; do echo "  - $d" >&2; done
  echo "" >&2
  echo "Per CLAUDE.md: never touch live ClickHouse without a snapshot first." >&2
  echo "Take a snapshot (e.g., 'clickhouse-backup create pre_$(date +%Y%m%d_%H%M)'), then retry." >&2
  exit 2
fi

echo "OK: destructive CH op allowed (recent snapshot: $RECENT_SNAPSHOT)" >&2
exit 0
