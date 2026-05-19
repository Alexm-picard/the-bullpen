# Phase 5 — Polish + Operate · INDEX

> Public launch. Operate through season. Write postmortems.
> Weeks 31–38+ · ~80–100 hours. See [`../../plan.md`](../../plan.md) §Phase 5.
>
> **Phase exit criterion**: Project publicly accessible; README links to all artifacts; ≥1 drift event observed and documented in postmortem; system running ≥4 weeks with documented uptime.
>
> **MVP cuts**: Extend timeline rather than cut. Polish compounds; cutting it produces a worse final artifact.
> Exception: if dragging into October and season is ending, accept "ships in winter, postmortem in 2027 spring training" as the actual timeline (decision [82]).

---

## Cross-cutting docs to read alongside any leaf in this phase

- [`../00-MASTER.md`](../00-MASTER.md)
- [`../00-CONVENTIONS.md`](../00-CONVENTIONS.md)
- [`../00-OBSERVABILITY-STRATEGY.md`](../00-OBSERVABILITY-STRATEGY.md) — postmortem structure
- [`../../design.md`](../../design.md) §8 (polish phase), §11 (v1.5 roadmap)

---

## What polish actually means

Cohesion compounds at the end. The polish pass is small, deliberate adjustments across the whole UI, not feature work. Decision [113].

---

## Leaf plans

### 5.1 — `5.1-typography-pass.md`
Sweep all five pages. Verify type scale obeyed. Verify monospace + serif don't co-occur on the same line. Verify tabular figures on for all numeric data. Verify Source Serif headlines on About + Park Explorer marquee. Adjust line-height, letter-spacing where regression-worthy.
- **Decisions referenced**: [103], [104].

### 5.2 — `5.2-color-audit.md` ★ **DISCIPLINE GATE**
`pnpm lint:hex-codes` must return zero hits in `src/` outside `src/design/`. If any are found, replace with tokens. This is the StudyForesight regression-prevention discipline (decision: design system tokens; CLAUDE.md rule 1).
- **Decisions referenced**: [105]–[107].
- **Acceptance**: zero inline hex codes in component files; documented audit in PR description.

### 5.3 — `5.3-perf-bundle-audit.md`
Bundle budget per page. Lazy-load Park Explorer (the heaviest). Image optimization. Lighthouse > 80 across all 5 pages.
- **Decisions referenced**: design.md §7 (perf constraints).
- **Acceptance**: Lighthouse run captured per page; bundle budget < 300 KB gz initial; lazy-loaded Park Explorer measurable in network panel.

### 5.4 — `5.4-a11y-audit.md`
Mantine handles primitives. Custom visualizations need the manual pass: alt text on visualizations, keyboard nav on interactive viz, color-blind-safe palettes (Viridis everywhere data viz appears), focus management.
- **Decisions referenced**: [106], design.md §7.
- **Acceptance**: axe-core run produces no critical/serious findings on any page.

### 5.5 — `5.5-park-explorer-polish-iteration.md`
The "fine → memorable" pass on the marquee component. Iterate the heatmap visual: chart-junk reduction, legend placement, tooltip behavior, transition timing. Allocate 8–12 hours specifically here; do not cap at 4.
- **Decisions referenced**: [98], [102].

### 5.6 — `5.6-readme-rewrite-and-public-launch.md`
README at repo root: project framing, architecture diagram, links to design.md / decisions.md / eval artifacts / Ops dashboard, "what works / what's known to be limited", data sources + licensing, contact. Links validated.
- **Closes / addresses**: I7 (data licensing) — final write-up.
- **Acceptance**: public posts to r/baseball, r/sabermetrics, r/programming, HN. The README and the live site survive a recruiter clicking around for 5 minutes without finding a broken link or unstyled state.

### 5.7 — `5.7-drift-postmortem-template.md` ★ **CENTERPIECE ARTIFACT**
A repeatable template for incident postmortems. Lives in `ops/runbooks/drift-postmortem-template.md` and is copy-pasted to `docs/postmortems/<date>-<slug>.md` when a drift event fires. See [`../00-OBSERVABILITY-STRATEGY.md`](../00-OBSERVABILITY-STRATEGY.md) §"Drift postmortem".
- **Decisions referenced**: [82].
- **Acceptance**: template exists; first real postmortem authored when first real drift event lands; postmortem is linked from README.

---

## Operating discipline (Weeks 31+)

Once Phase 5 leaf plans 5.1–5.6 ship, the project is operating. Discipline rules continue to apply:

- **No deploys during live games** (Discipline Rule 2; decision [21]).
- **2-week reviews continue** through the season — for operational health, not feature progress.
- **Each detected drift event produces a postmortem** under `docs/postmortems/`. Postmortems are linked from the Ops Dashboard and the README.

---

## Phase 5 exit gate (and project completion)

```bash
# Public launch:
# - README links resolve
# - All 5 pages load without console errors on a fresh browser
# - Ops Dashboard public read paths show real data

# At least one real drift event captured:
ls docs/postmortems/*.md   # ≥ 1 file

# Uptime evidence:
# - Better Stack monthly report screenshot saved to docs/operating-reports/
# - Window: ≥ 4 consecutive weeks
# - Availability ≥ 98% (SLO from Risk Register I1)
```

If all three pass: project is "done" by the v1 contract. v1.5 roadmap (`design.md` §11) becomes the next-phase backlog; cherry-pick rather than committing to all.
