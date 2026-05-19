#!/usr/bin/env bash
# PostToolUse hook: format edited Java files with Spotless (google-java-format).
# Reads tool input from stdin JSON, extracts file_path, runs spotlessApply if it's *.java.
# Always exits 0 so editing flow is never blocked by formatter issues.

set -uo pipefail

INPUT="$(cat)"
FILE=$(printf '%s' "$INPUT" | python3 -c 'import sys,json; d=json.load(sys.stdin); print(d.get("tool_input",{}).get("file_path",""))' 2>/dev/null || true)

[[ -z "$FILE" ]] && exit 0
[[ "$FILE" != *.java ]] && exit 0

REPO_ROOT="${REPO_ROOT:-/home/$(whoami)/code/thebullpen}"
[[ -d "$REPO_ROOT/backend" ]] || REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"

# Spotless runs across the module; gate by file existence
if [[ -f "$REPO_ROOT/backend/build.gradle.kts" ]] || [[ -f "$REPO_ROOT/backend/build.gradle" ]]; then
  (cd "$REPO_ROOT/backend" && ./gradlew spotlessApply --quiet -PspotlessFiles="$FILE" 2>/dev/null) || true
fi

exit 0
