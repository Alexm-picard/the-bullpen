#!/usr/bin/env bash
# [190] enforcement: check SHELF: markers for expiry.
#
# Greps the repo for <!-- SHELF: YYYY-MM source --> markers. Warns on expired
# dates, fails only on markers expired > 30 days. Non-blocking under 30 days
# so a one-day-stale marker doesn't halt all PRs.
#
# Pattern: same shape as check-drill-freshness.sh (warn/fail thresholds,
# data-anchored, no memory dependency).
set -euo pipefail

WARN_DAYS="${SHELF_WARN_DAYS:-0}"
FAIL_DAYS="${SHELF_FAIL_DAYS:-30}"

TODAY=$(date -u '+%Y-%m-%d')
TODAY_EPOCH=$(date -u -d "$TODAY" '+%s' 2>/dev/null || date -u -j -f '%Y-%m-%d' "$TODAY" '+%s' 2>/dev/null)

warnings=0
failures=0

while IFS=: read -r file line content; do
  # Extract the date from SHELF: YYYY-MM or SHELF: YYYY-MM-DD
  shelf_date=$(echo "$content" | grep -oE '20[0-9]{2}-[0-9]{2}(-[0-9]{2})?' | head -1)
  [[ -z "$shelf_date" ]] && continue

  # Normalize YYYY-MM to YYYY-MM-01 for comparison
  if [[ ${#shelf_date} -eq 7 ]]; then
    shelf_date="${shelf_date}-01"
  fi

  shelf_epoch=$(date -u -d "$shelf_date" '+%s' 2>/dev/null || date -u -j -f '%Y-%m-%d' "$shelf_date" '+%s' 2>/dev/null || continue)
  age_days=$(( (TODAY_EPOCH - shelf_epoch) / 86400 ))

  if [[ $age_days -gt $FAIL_DAYS ]]; then
    echo "::error file=${file},line=${line}::SHELF marker expired ${age_days}d ago (limit ${FAIL_DAYS}d): ${content}"
    failures=$((failures + 1))
  elif [[ $age_days -gt $WARN_DAYS ]]; then
    echo "::warning file=${file},line=${line}::SHELF marker expired ${age_days}d ago: ${content}"
    warnings=$((warnings + 1))
  fi
done < <(grep -rn 'SHELF: 20[0-9][0-9]-[0-9][0-9]' README.md frontend/src/data/ frontend/src/pages/ \
  docs/design/ docs/runbooks/ docs/research/ docs/capacity.md docs/plan.md \
  2>/dev/null | grep -v 'decisions.md' || true)

if [[ $failures -gt 0 ]]; then
  echo "check-shelf-markers: ${failures} marker(s) expired > ${FAIL_DAYS}d (FAIL), ${warnings} warning(s)"
  exit 1
fi

if [[ $warnings -gt 0 ]]; then
  echo "check-shelf-markers: ${warnings} marker(s) expired (warn-only, under ${FAIL_DAYS}d)"
else
  echo "check-shelf-markers: all markers current (or none found)"
fi
