# Hardening sweep — \<window label, e.g. "All-Star break 2026"\>

> Copy this file to `YYYY-MM-DD_sweep.md` (the date the sweep STARTED).
> Fill the Impact table as you go — don't wait until the end. Phase 5.5
> exits when this table has ≥ 5 honest rows with real before/after
> numbers (≥ 8 is the target).

- **Sweep window**: YYYY-MM-DD → YYYY-MM-DD
- **Operator**: <name>
- **Sources triaged**: `docs/hardening/observations.md` (count: N items),
  recent postmortems (links), drill reports (links)
- **Items considered**: N · **selected**: M · **deferred**: N − M

## Triage rationale

<One paragraph. Why these M? What got dropped and why? Honest about
what we're choosing not to chase this sweep.>

## Impact table

Each row: what changed, the measurement instrument (same before AND
after), the numbers, and a PR / commit reference. No hand-wave rows.

| #   | Area | Change | Instrument | Before | After | PR / commit |
| --- | ---- | ------ | ---------- | ------ | ----- | ----------- |
| 1   |      |        |            |        |       |             |
| 2   |      |        |            |        |       |             |
| 3   |      |        |            |        |       |             |
| 4   |      |        |            |        |       |             |
| 5   |      |        |            |        |       |             |

## Per-item notes

### 1. <one-line title>

- **Observation source**: `observations.md` row dated YYYY-MM-DD
- **What broke / wasn't good enough**: <prose>
- **Hypothesis**: <prose>
- **Approach**: <prose>
- **Measurement**: <instrument, repeat-count, conditions>
- **Result**: <numbers + interpretation>
- **What changed**: PR <link>, files <list>
- **Lessons**: <prose>

### 2. <one-line title>

…

## Decisions revised during the sweep

- `decisions.md` `[N]` — <one line on what changed and why>
- ADR-NNNN updated — <link to revision history entry>

## Items deferred to the next sweep

- `observations.md` row YYYY-MM-DD — reason
- `observations.md` row YYYY-MM-DD — reason

## Footnotes

- Total wall-time spent: <hours>
- Estimated effort going in: <hours>
- Variance: <delta + why>
