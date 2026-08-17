# Hiring readiness

Phase 5 ships the engineering. Phase 6 makes the engineering legible to a
hiring audience. Different work, separate time block.

## Deliverables checklist

| Deliverable                 | Status   | File                                                                                                                                                                                      |
| --------------------------- | -------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| README (interview opener)   | shipped  | [`README.md`](../../README.md) - rewritten in Phase 5.6, cold-reader restructure 2026-08-16                                                                                               |
| /about page (methodology)   | shipped  | live at https://thebullpen.net/about - a slim colophon since [191]; methodology absorbed by /models/guide                                                                                 |
| Ops dashboard (recruiter)   | shipped  | live at https://thebullpen.net/ops (Phase 4e)                                                                                                                                             |
| Drift postmortem (template) | shipped  | [`docs/runbooks/drift-postmortem-template.md`](../runbooks/drift-postmortem-template.md)                                                                                                  |
| Drift postmortem (induced)  | shipped  | [`2026-07-16_induced-drift-drill.md`](../postmortems/2026-07-16_induced-drift-drill.md) - live-path drill, labeled synthetic (decision [175]); reclassified as the drill report per [187] |
| Drift postmortem (natural)  | shipped  | [`2026-08-03_pitcher-form-silent-staleness.md`](../postmortems/2026-08-03_pitcher-form-silent-staleness.md) - the real event, flagship per [187] ([169]'s clause executed)                |
| Hardening sweep             | shipped  | [`2026-05-30_sweep.md`](../hardening/2026-05-30_sweep.md) - 18 observations triaged, 11 fixed, 7 deferred                                                                                 |
| Lessons-learned             | skeleton | [`lessons-learned.md`](lessons-learned.md) — fills during operation                                                                                                                       |
| 60-second verbal pitch      | drafted  | [`60-second-pitch.md`](60-second-pitch.md)                                                                                                                                                |
| OSS PR (≥ 1 merged)         | struck   | criterion struck by decision [193] - the track (clickhouse-java investigation, LightGBM PR) was pursued honestly and deliberately ended; the targets file was deleted with it             |
| Hero screenshot of /parks   | shipped  | [`docs/screenshots/parks-full.jpeg`](../screenshots/parks-full.jpeg) - in the root README grid                                                                                            |
| Recruiter-time-test         | pending  | non-baseball friend reads README in 60 s and gets it                                                                                                                                      |

## Gated vs author-now

**Author-now** (this scaffolding pass):

- [60-second pitch](60-second-pitch.md) — drafted from README + /about prose.
- [Lessons-learned](lessons-learned.md) — skeleton; populates as the season runs.
- OSS contribution targets - file deleted along with the struck criterion
  ([193]); the track's pursuit and deliberate closure is the record.

**Real-operation gated**:

- The first real drift postmortem (decision [82] — the centerpiece). Delivered 2026-08-03 per [187].
- The first hardening sweep doc (Phase 5.5). Delivered: [`2026-05-30_sweep.md`](../hardening/2026-05-30_sweep.md).
- Lessons-learned filled in.

**Browser-harness gated**:

- Hero screenshot.
- Lighthouse / axe / Playwright CI badges in the README.

## Discipline

- The Ops dashboard must remain accessible **post-launch**, populated
  with real data even after the season ends. Don't tear it down for a
  "v2 rebuild" — recruiters click the link in the README expecting it
  live.
- The drift postmortem is **real SRE format** (5-Whys, timeline,
  threshold analysis, runbook updates), not a blog post.
- The OSS-PR requirement is STRUCK by [193] - no upstream track. The pursuit
  (clickhouse-java investigation, LightGBM PR) is recorded in the decision.

## Phase 6 exit criterion

> A hiring manager visiting the GitHub repo in 60 seconds gets the
> right impression and can find the postmortem, the Ops Dashboard, and
> proof of collaboration without scrolling.
