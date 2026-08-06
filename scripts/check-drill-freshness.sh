#!/usr/bin/env bash
# Rule-8 self-policing: how stale is the newest drill record?
#
# WHY THIS EXISTS. Rule 8 says restore and reboot drills must run, and nothing enforced or even
# SURFACED it. A drill ran 2026-07-26, passed, and its record was dropped in the box->Mac handoff;
# docs/drills/ read as 51 days stale for ten days and nobody noticed. The record IS the artifact
# rule 8 is about - an untested backup and an untested-LOOKING backup are indistinguishable to an
# auditor - so an unrecorded drill has to surface as an INCOMPLETE drill, not as a completed one
# with missing paperwork.
#
# WHY TWO THRESHOLDS RATHER THAN ONE. ci-gate aggregates every check-run on the SHA, so a job that
# goes red on day 31 blocks every unrelated PR in the repo. A gate that halts all work over a drill
# being one day late is a gate that gets bypassed, and a bypassed gate is worse than none because it
# still looks green afterwards. So: WARN at the 30-day guidance (visible, non-blocking, early), FAIL
# at 45 (blocking, and by then the delay is the problem rather than the check).
#
# Anchored to DATA - the newest filename in docs/drills/ - not to anyone remembering, the same shape
# as the freshness gauges.
set -euo pipefail

DRILLS_DIR="${1:-docs/drills}"
WARN_DAYS="${DRILL_WARN_DAYS:-30}"
FAIL_DAYS="${DRILL_FAIL_DAYS:-45}"

# ls+grep rather than `find -printf`: -printf is GNU-only and silently finds nothing on BSD/macOS,
# which would mean this script could not be tested on the machine it is authored on - and an
# untestable check is the category this repo keeps finding.
newest=$(ls -1 "$DRILLS_DIR" 2>/dev/null \
  | grep -E '^20[0-9]{2}-[0-9]{2}-[0-9]{2}_.*\.md$' | sort | tail -1 || true)
# `|| true` is load-bearing: grep exits 1 when nothing matches, and under `set -euo pipefail` that
# aborts the script inside the command substitution - so the empty-directory case exited 1 with NO
# message at all. Right exit code, wrong reason, and the one explanation a maintainer needs never
# printed.

if [[ -z "$newest" ]]; then
  echo "::error::no drill records found in ${DRILLS_DIR} - rule 8 has no evidence at all"
  exit 1
fi

drill_date="${newest:0:10}"
drill_epoch=$(date -u -d "$drill_date" +%s 2>/dev/null || date -u -j -f "%Y-%m-%d" "$drill_date" +%s)
age_days=$(( ( $(date -u +%s) - drill_epoch ) / 86400 ))

echo "newest drill record: ${newest} (${drill_date}, ${age_days} days ago)"
echo "thresholds: warn ${WARN_DAYS}d, fail ${FAIL_DAYS}d"

if (( age_days > FAIL_DAYS )); then
  echo "::error::drill records are ${age_days} days stale (>${FAIL_DAYS}). Rule 8 requires a drill AND its committed record - if a drill ran, its record was dropped in the box->Mac handoff; commit it. See docs/drills/2026-07-26_restore-from-r2.md for that exact failure."
  exit 1
fi

if (( age_days > WARN_DAYS )); then
  echo "::warning::drill records are ${age_days} days stale (>${WARN_DAYS} guidance). Run a drill, or commit the record of one that already ran - a dropped handoff is silent and reads identically to a missed drill."
fi

echo "drill freshness OK"
