# Postmortem: two months of silent Tier-3 form staleness on the live pitch path

> **Real production degradation - not a drill.** This is the confirmed natural
> event decision [169] anticipated: its natural-event-supersedes clause
> executes with this document, which replaces the honestly-labeled synthetic
> induced-drift drill (`2026-07-16_induced-drift-drill.md`) as the flagship
> drift postmortem. The drill reclassifies as a drill report, exactly as its
> own banner said it would.

- **Date range**: ~2026-05-28 -> 2026-07-31 (degradation window);
  2026-07-27 (detection); 2026-07-28 (decision [186]); 2026-07-29 -> 2026-07-31
  (fix deployed + recovery verified); 2026-08-02 (alerting layer hardened)
- **Severity**: silent feature degradation on the live serving path - no
  outage, no wrong-looking output, which is precisely what made it dangerous
- **Affected surface**: `pitcher_form_current` (V007), the Tier-3 28-day
  rolling form features consumed by `pitch_outcome_pre` - the model serving
  the live game page's next-pitch panel (champion since [182], 2026-07-22)
- **Detected by**: the NEIGHBOURING table's deliberately louder design -
  `pitcher_pitchtype_prior_current` (V030) anchors its freshness to the DATA
  and refuses to serve past a 2-day staleness bound; its refusal at 64 days
  is what surfaced the whole problem
- **Operator**: alex (box, per ADR-0006); Mac-side diagnosis, fix, and this
  write-up: dev; decision [186]: alex + TD converged 2026-07-28

**Summary.** The `pitches` table is a manually-backfilled historical corpus.
The last backfill ran 2026-05-28 and loaded games through 2026-05-25 - and
both nightly snapshot jobs that read it were designed against an implicit
assumption that `pitches` stays near-current. For roughly two months that
assumption was false and nothing said so. `pitcher_form_current`'s 28-day
windows drained as the corpus edge receded (by late June the historical leg
contributed nothing; only same-day rows from the intra-day live leg
survived), so the majority of live next-pitch predictions were served with
NaN or near-empty Tier-3 form - while the job's clock-anchored
`as_of_date = today()` stamped every snapshot "fresh" and its log line
reported a handful of live-leg strays as refreshed pitchers. The failure was
found on 2026-07-27 not by the degraded table but by its younger sibling:
V030's pitch-type prior anchors `as_of_date` to the data and enforces a
staleness bound at the deriver, so instead of degrading it REFUSED, loudly,
at 64 days (2,670 rows, as_of 2026-05-25, verified 2026-07-28). Pulling that
thread unmasked the form table's two silent months. The fix ([186]) made
both snapshot queries read `pitches` UNION `pitches_live`, moved V007 onto
V030's data-anchored honesty pattern bundled with an age gauge, and
parity-tested both queries because they feed a leakage boundary. Recovery
was verified with numbers: prior as_of from 64 days to 1; the form cohort at
the data anchor from 1 pitcher to 467 with fully populated 28-day windows
(avg 109 pitches) across three nightly refreshes. The alerting layer that
would have caught this in week one - not month two - shipped the following
week and is behaviorally tested in CI.

## Why this is the flagship postmortem

The 2026-07-16 drill was honestly labeled synthetic, and the external audit
called that out fairly: an injected shift proves the detector has teeth, not
that the operator can find real ones. This event is the genuine article on
every axis the drill could not be:

- a REAL degradation, on a production model's live path, lasting two months;
- detected by DESIGNED machinery (a data-anchored staleness refusal), not by
  luck or a user report;
- diagnosed to an architectural root cause, not a bug;
- fixed through a locked, adversarially-reviewed decision ([186]) with
  parity-tested code on both affected queries;
- alerted on honestly in BOTH failure directions afterwards (recency AND
  contiguity, job-dead AND corpus-stale);
- and verified recovered with numbers at every step.

## Timeline

| When (ET)        | What                                                                                                                                                                                                                                                                                                                                                                                                                                                        |
| ---------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 2026-05-28       | Last manual `pitches` backfill runs; corpus max game_date lands at 2026-05-25.                                                                                                                                                                                                                                                                                                                                                                              |
| ~2026-06-22      | The 28-day form windows fully clear the corpus edge. From here the historical leg contributes zero rows; only same-day `pitches_live` rows feed form. Nothing alerts: `as_of_date = today()` reads fresh nightly.                                                                                                                                                                                                                                           |
| 2026-07-22       | `pitch_outcome_pre` v2 promotes to champion via the first-champion gate ([182]). The live next-pitch panel begins serving user-visible predictions - with degraded Tier-3 form underneath.                                                                                                                                                                                                                                                                  |
| 2026-07-27       | THE FINDING. On the box, the pitch-type prior deriver is refusing predictions: V030's data-anchored `as_of_date` reads 2026-05-25, 64 days past its 2-day bound. The refusal is correct behaviour. Asking why a "nightly-refreshed" snapshot is 64 days old unmasks the form table: corpus 63 days stale, the job's "refreshed 7 pitchers" log line built from live-leg strays stamped with current dates.                                                  |
| 2026-07-28       | Decision [186] locked (alex + TD): snapshot windows read `pitches` UNION `pitches_live`; V007 adopts the data-anchored `as_of_date` + age gauge bundled; parity tests mandatory on both queries; `ml-leakage-auditor` mandatory on both PRs. Alternatives rejected in the entry: a live-to-historical promotion job (new infrastructure plus its own leakage surface) and widening the staleness bound (a bound that absorbs an unbounded gap is no bound). |
| 2026-07-28       | PR #378 (V007 union + data anchor + `bullpen_pitcher_form_age_days`) and PR #381 (V030 union + `bullpen_pitchtype_prior_coverage_gap_days`) merge, parity-tested, leakage-audited.                                                                                                                                                                                                                                                                          |
| 2026-07-29 -> 31 | Deploy in the morning window. Recovery verified in prod: prior as_of 2026-07-31 (1 day old, was 64); `pitcher_form_current` cohort at the anchor 1 -> 12 -> 467 pitchers over three nightly refreshes, all with populated 28-day windows, avg 109 pitches. PRE's Tier-3 form is genuinely restored.                                                                                                                                                         |
| 2026-08-02       | The alerting layer hardens (PR #384): last-success timestamp stamps on both jobs (a dead job freezes its age gauge at a fresh-looking value - the same silent shape one level up), the first tests that scrape the served metrics endpoint, and staleness alert rules whose fire-AND-silence semantics are pinned by `promtool test rules` fixtures in CI.                                                                                                  |
| 2026-08-03       | The coverage-gap GROWTH alert lands (PR #388, TD-corrected): the gap between the corpus edge and the live TTL floor climbs ~1/day while the backfill is unrun, and each day of climb is history leaving both tables permanently - growth fires; the accepted standing level does not.                                                                                                                                                                       |

## Impact, stated plainly

- **What degraded**: the Tier-3 rolling-form feature block (28-day windows)
  for live `pitch_outcome_pre` predictions. For most pitchers on most nights,
  form arrived as NaN or near-empty - the documented cold-start shape, served
  as the steady state.
- **What did NOT break**: no requests failed, no wrong-looking output, no
  calibration alert fired (the model handles NaN form by design - that
  graceful degradation is exactly what let this hide). Offline evaluation,
  training, and the other model families were unaffected.
- **User-visible window**: shadow-logged predictions carried degraded form
  from late June; USER-VISIBLE serving began at the [182] champion promotion
  on 2026-07-22, so the live panel served with degraded form for roughly nine
  days before detection and twelve before verified recovery.
- **Data permanently lost**: none from this event window - the union restored
  access to everything still inside `pitches_live`'s 14-day TTL, and the
  manual backfill covers the rest. The COVERAGE GAP between the corpus edge
  and the TTL floor (49 days at fix time, climbing ~1/day until the next
  backfill) is real and tracked by its own gauge and growth alert; history
  that ages past the TTL before a backfill runs is gone from both tables.

## Five whys

1. **Why were live predictions served with degraded form?** The nightly
   `pitcher_form_current` refresh computed its 28-day windows over `pitches`,
   whose newest row was two months old - the windows were empty.
2. **Why did nothing say the windows were empty?** The snapshot's freshness
   stamp was CLOCK-anchored (`as_of_date = today()`): it certified that the
   job ran, not that the data moved. A truthful-looking column nobody could
   distrust.
3. **Why did the job's own logging not expose it?** The log line counted
   pitchers with rows at the current date - live-leg strays qualified, so it
   reported "refreshed 7" nightly. A count that answers a different question
   than the one the reader asks.
4. **Why was the corpus stale at all?** `pitches` is manually backfilled by
   design (decision [186] keeps it so). The jobs were built when live ingest
   did not exist, against an implicit "pitches is near-current" assumption
   that was true at build time and never encoded anywhere it could be
   checked.
5. **Why did the assumption fail silently instead of loudly?** Because
   nothing OWNED it. No gauge measured data recency, no alert rule existed,
   and the one honest signal in the system - V030's data-anchored refusal -
   existed only because the younger table's design had learned from a prior
   near-miss (the 2026-07-04 zero-rows drift job, [175]). The root cause is
   an unowned assumption, not a bug: every mechanism above behaved exactly
   as written.

## What the fix looks like (and what it deliberately is not)

Decision [186], in three parts, each with its own guard:

1. **The union**: both snapshot queries read `pitches` UNION `pitches_live`,
   deduped on the pitch key across the overlap, with strictly-before
   semantics preserved - the leakage boundary moves to the union's
   max(game_date), which tracks reality. Both queries carry parity tests
   (serving-derived values vs training-path values on fixed rows) because
   changing what feeds a leakage boundary without a parity witness is how
   silent skew is born.
2. **The honesty pattern, bundled**: V007 adopted V030's data-anchored
   `as_of_date` AND an age gauge in the same change - the [186] entry's own
   words: the anchor alone is a truthful column nobody reads; the gauge is
   what alerts. Shipping one without the other is the half-fix this
   postmortem exists to warn against.
3. **The alerting, in both directions**: age gauges catch corpus staleness
   but freeze at fresh-looking values if the job dies, so last-success
   timestamps catch job-death; the coverage-gap gauge catches discontinuity
   that recency cannot see, and its GROWTH alert fires while the gap climbs
   without paging on the accepted standing backlog. Every rule's fire and
   silence behaviour is pinned by `promtool test rules` fixtures in CI -
   a check config pass proves rules parse, not that they work.

Deliberately NOT built: a live-to-historical promotion job. It would add
infrastructure and a second leakage surface to solve a problem the union
already solves, and the manual-backfill discipline - now with a growth alert
enforcing its cadence - keeps the historical corpus's provenance simple.

## Action items

All closed unless marked open.

| Item                                                                        | Status                  |
| --------------------------------------------------------------------------- | ----------------------- |
| [186] union in both snapshot queries, parity-tested, leakage-audited        | DONE - PRs #378, #381   |
| V007 data-anchored as_of + `bullpen_pitcher_form_age_days`                  | DONE - PR #378          |
| Coverage-gap gauge (contiguity, not recency)                                | DONE - PR #381          |
| Last-success timestamps + served-scrape tests + staleness alert rules       | DONE - PR #384          |
| Behavioral alert-rule testing lane (`promtool test rules` in CI)            | DONE - PR #384          |
| Coverage-gap GROWTH alert (level accepted, growth is loss)                  | DONE - PR #388          |
| Timezone-basis sweep of CH IT fixtures (server-tz `today()` vs ET bounds)   | OPEN - issue #392       |
| Manual `pitches` backfill run | DONE - box, 2026-08-03: coverage gap 54d -> 0.0, and #388 completed its first ORGANIC fire-and-resolve cycle (paged on real growth, resolved on real closure - the alert doing exactly its job on day one) |
| Freshness thresholds locked via /decide (3d age, 26h stamp are provisional) | OPEN - decision backlog |

## What this cost, honestly

Two months of a production feature quietly absent, nine days of it under a
user-visible champion, and an operator morning to find it - against a
detection design that cost one extra column and one gauge when V030 was
built. The asymmetry is the lesson: the loud design was not harder, it was
just younger. Every table that certifies its own freshness with a clock is
carrying this bug at some maturity.

## References

- Decision [186] (`docs/decisions.md`) - the full decision text with
  alternatives and the accepted TTL-window limit
- Decisions [143]/[169]/[175]/[182] - the form-skew documentation, the
  supersession clause, the synthetic drill mandate, the champion promotion
- PRs #378, #381 (the fix), #384 (observability), #388 (growth alert);
  issues #389, #392 (follow-ups)
- `2026-07-16_induced-drift-drill.md` - the superseded synthetic drill,
  reclassified as a drill report per its own banner
- `2026-07-16_first-organic-psi-triage.md` - the prior near-miss whose
  lesson shaped V030's loud design
