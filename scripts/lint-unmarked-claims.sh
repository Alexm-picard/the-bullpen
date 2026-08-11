#!/usr/bin/env bash
# [190] layer 3: warning-only lint for numeric claims without SHELF: markers.
#
# Scans README.md + docs/ + frontend/src/data/ for lines containing metric-shaped
# values (percentages, row counts, "as of" dates, Brier/ECE figures) that do NOT
# have a SHELF: marker on the same line or the line above. Surfaces unmarked
# claims so they can be marked; never blocks.
#
# Pattern: deliberately noisy rather than silent. False positives are tolerable
# (the operator skims the list); false negatives (missing a real perishable) are
# the failure mode this exists to prevent.
set -euo pipefail

TARGETS=(
  README.md
  docs/design/
  docs/capacity.md
  docs/plan.md
  docs/runbooks/
  docs/research/
  frontend/src/data/about-fixtures.ts
  frontend/src/pages/model-guide-page.tsx
)

# Patterns that suggest a perishable numeric claim:
#   - Percentages: 82%, 0.104, ~95%
#   - Row counts: 7,925,701 or 138676 or 710k
#   - "as of" dates: 2026-06-20, as of July
#   - Metric values: Brier 0.104, ECE 0.0013
CLAIM_PATTERN='[0-9]+%|[0-9],[0-9]{3}|[0-9]+k |Brier [0-9]|ECE [0-9]|as of [A-Z]'

hits=0
while IFS=: read -r file line content; do
  # Check if this line or the line above has a SHELF: marker
  prev_line=$((line - 1))
  has_shelf=false
  if [[ $prev_line -ge 1 ]]; then
    prev_content=$(sed -n "${prev_line}p" "$file" 2>/dev/null || true)
    if echo "$prev_content" | grep -q 'SHELF:'; then
      has_shelf=true
    fi
  fi
  if echo "$content" | grep -q 'SHELF:'; then
    has_shelf=true
  fi

  if ! $has_shelf; then
    echo "::warning file=${file},line=${line}::Unmarked numeric claim (add a SHELF: marker): $(echo "$content" | sed 's/^[[:space:]]*//' | head -c 120)"
    hits=$((hits + 1))
  fi
done < <(
  for target in "${TARGETS[@]}"; do
    if [[ -e "$target" ]]; then
      grep -rn --include='*.md' --include='*.ts' --include='*.tsx' \
        -E "$CLAIM_PATTERN" "$target" 2>/dev/null || true
    fi
  done
)

if [[ $hits -gt 0 ]]; then
  echo "lint-unmarked-claims: ${hits} unmarked numeric claim(s) found (warning-only, non-blocking)"
else
  echo "lint-unmarked-claims: clean (all numeric claims have SHELF: markers or none found)"
fi

# Never fail - this is warning-only per [190]
exit 0
