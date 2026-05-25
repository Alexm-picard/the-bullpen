# Hardening — process

The artifact this directory produces is **one** mid-season (or end-of-season)
sweep doc with a triaged Impact table, each row carrying a measurable
before / after metric and a PR / file reference. That doc is the
deliverable Phase 5.5 lives or dies on.

## Files in this directory

| File                    | Purpose                                                                                                                                           |
| ----------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------- |
| `README.md` (this file) | The process — read first.                                                                                                                         |
| `observations.md`       | Running list. Anything that surprised you during operation lands here, dated, one line. Cheap to add; don't filter at capture time.               |
| `_template_sweep.md`    | Copy this to `YYYY-MM-DD_sweep.md` when you sit down to triage + implement. The triaged Impact table is the headline.                             |
| `YYYY-MM-DD_sweep.md`   | The actual sweep doc(s). Phase 5.5 exits when at least one of these exists with ≥ 5 honest impact items (≥ 8 is the target; 5 is the hard floor). |

## Capture cadence

- **During operation**: tail `journalctl -u bullpen-*`, watch the Ops
  dashboard, read the Discord alerts. Anything noticed → one line in
  `observations.md` with the date and (where applicable) a metric
  snapshot. Don't pre-judge severity at capture.
- **Per drill / postmortem**: add a "Things worth chasing later" line
  at the bottom of each report → mirror into `observations.md` so the
  drill doesn't bury it.
- **Per deploy**: if the deploy revealed friction (rollback time,
  config drift, missing health probe), add it.

## Triage → sweep

1. Pick a day. Read `observations.md` end to end.
2. Score each item: rough effort (hours) × rough impact (incident
   prevention, perf, recruiter signal, operator ergonomics).
3. Take top 8–15. Discard the rest from the sweep (they stay in
   `observations.md` for the next sweep).
4. Copy `_template_sweep.md` → `YYYY-MM-DD_sweep.md`.
5. Implement each, **measuring before AND after** with the same
   instrument. PR-per-item is the cleanest commit history.
6. Fill the Impact table as you go — don't wait until the end.
7. When the table has ≥ 5 rows with real before/after numbers,
   commit and link from the README's "Operating evidence" section
   and from Phase 6's hiring deliverables.

## Discipline

- **Do not cut.** If energy is limited, shrink the table count, not
  the rigor. 5 honest items > 12 items with hand-wavy "feels faster"
  rows.
- Before/after with the same instrument. "p99 latency 412ms → 187ms,
  same wrk -t4 -c100 -d30s" not "feels snappier."
- Reference the PR or commit SHA for every row.
- Decisions revised during the sweep update an ADR in
  [`docs/adr/`](../adr/) and log under
  [`docs/decisions.md`](../decisions.md). The sweep is allowed to
  change locked decisions — that's the point.
