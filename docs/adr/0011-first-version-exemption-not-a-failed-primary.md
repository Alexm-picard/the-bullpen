# ADR-0011: The first-version promotion exemption does not cover a failed primary metric

- **Status**: Accepted
- **Date**: 2026-06-09
- **Deciders**: alex
- **Related**: `decisions.md` entries [72] [141] [145] [150] [154]; CLAUDE.md discipline rules 5 and 6; ADR-0006 (dev/prod boundary - why box-trained candidates are authored and evaluated, then promotion-gated)

## Context

Rule 5 (decision [72]) is the promotion-discipline floor: a model moves to
CHAMPION only against pre-declared promotion criteria (primary metric,
sample size, threshold, guardrails) AND a passing row in
`experiment_results`. No promotion without that passing record. Rule 6
keeps the final promotion human-gated.

Decision [145] bound the `experiment_results` gate at SHADOW -> CHAMPION
(not at registration), because the registry's experiment flow is
structurally challenger-vs-champion: an `ExperimentResult` row carries a
non-null `championVersionId` AND a non-null `challengerVersionId`. A FIRST
champion (no prior champion exists) therefore cannot mechanically produce
an `experiment_results` row - there is no champion to compare against.
[145] explicitly left "the experiment flow's bootstrap / first-champion
path" as an open question to settle at promotion time. The first-champion
recovery work then sharpened this: [150]/INC-6 noted that a single
only-registered version keeps a "rule-5 bootstrap exemption," and that
registering a v2 re-arms rule 5 with no `experiment_results` row available
to satisfy it.

The trigger for this ADR is the Step-5 -> Step-6 live-data campaign, which
forked on how to gate live pitch predictions. The `pitch_outcome_pre` v1
evidence row honestly reads FAILED on its declared primary: edge 0.00084
against a 0.002 threshold. (PRE does beat its LR baseline on all three
metrics, with ECE 0.0036 - good calibration, but not the declared
primary.) One option on the table was to bootstrap-promote PRE v1 via the
first-version exemption, framed as "the same path the batted-ball champion
used."

That framing conflates two different things, and the conflation is
load-bearing enough to record. The batted-ball first champion (decision
[141]) did NOT promote on a failed primary. It RE-AIMED its primary from
cross-park rho - which it failed at 0.333 - to outcome calibration, which
it PASSED at per-park ECE 0.0005 against a <0.05 / <0.02 gate, because
calibration is what that model honestly claims. The bootstrap exemption
the batted-ball champion relied on covered only the `experiment_results`
challenger-vs-champion mechanics (there was no prior champion to compare
against), never a primary metric the model had failed.

## Decision

The rule-5 first-version / bootstrap exemption covers ONLY the
challenger-vs-champion mechanics of `experiment_results`: when no prior
champion exists, there is no comparison row to produce, so that specific
mechanical requirement is waived. The exemption does NOT excuse a model
from clearing a primary metric.

A first-version model must either:

- clear its declared primary metric on honest, passing evidence, OR
- clear an honestly re-aimed primary that reflects what the model actually
  claims and genuinely passes on its own terms (the decision [141]
  batted-ball pattern: rho -> per-park calibration).

The exemption must NEVER be used to wave a model through a primary metric
it failed.

Applied to the trigger: live pitch predictions stay champion-less. Live
ingest needs no champion (the degrade path - NaN form features /
skip-and-log - is verified safe), so the poller flips ingest-only with no
pitch model serving user-visible predictions. Pitch promotion waits for
the POST head (or a re-aimed-and-passing PRE) to provide honest, passing
evidence. This is a sharpening of how rule 5 is applied; rules 5, 6, 7,
and 9 themselves are unchanged.

## Consequences

**Easier:**

- The rule-5 / hiring-narrative story stays clean: "we promote on passing
  evidence, full stop." No asterisk that says "unless it is the first one,
  in which case a failed primary is fine."
- No re-introduction of the first-champion-incident churn just closed in
  decisions [149]-[152]. Promoting a weak PRE now would re-arm exactly
  that machinery-safety surface.
- The live page can light up with real ingest immediately, on a verified
  degrade path, without coupling that to a not-yet-credible prediction.

**Harder:**

- No live pre-pitch predictions until a pitch head honestly passes. The
  live page is ingest-only in the interim - it shows real data flowing,
  but no user-visible pitch-outcome prediction.
- Someone reading the registry sees a registered PRE v1 that is not
  serving; the "why is this not promoted" answer lives in [154] and here,
  not in the registry row alone.

**New failure mode and its guard:**

- The escape hatch is an honest re-aim of the primary (as batted-ball did
  with calibration). The abuse of that hatch is re-aiming to a metric
  chosen BECAUSE it passes, rather than because it is the model's honest
  headline claim. The guard: a re-aim must reflect what the model
  genuinely claims and pass on its own terms. Re-aiming to a convenient
  passing metric is the dishonest move this ADR forbids - the same
  integrity discipline ADR-0009 enforces against circular tuning (tuning a
  knob to hit a target, then reporting the target as evidence).

**Locked into:**

- Promote on passing evidence. The first-version bootstrap exemption is a
  mechanics waiver only, never a failed-primary bypass. Any future "just
  promote the first one to get the page live" pressure resolves the same
  way: ingest can proceed without a champion; predictions cannot proceed
  without honest passing evidence.

## Alternatives Considered

### Alternative A: Bootstrap-promote PRE v1 on the failed primary

- Promote `pitch_outcome_pre` v1 to champion now, citing the first-version
  exemption and the framing that this is "the same path the batted-ball
  champion used," so live pre-pitch predictions can ship immediately.
- Rejected: it is NOT the batted-ball precedent. The batted-ball first
  champion ([141]) re-aimed to a primary it genuinely passed
  (calibration); it did not promote on a failed one. Promoting PRE on a
  failed edge (0.00084 < 0.002) is a genuine threshold-bypass dressed as
  precedent-following. It also re-introduces the first-champion-incident
  churn just closed in [149]-[152], and a clearly stronger candidate (the
  POST head, partial-fold Brier ~0.103 vs PRE ~0.148) is inbound, so the
  bypass buys very little and costs the discipline story.

### Alternative B: Build champion-less shadow-without-champion routing now

- Add a routing/logging path so the PRE head can run in shadow and surface
  its predictions without ever being promoted to champion, lighting up the
  live page from shadow output.
- Rejected for now: it is the most new router/logger code of the three
  options and is not needed to light up live INGEST (ingest needs no
  model at all). Deferred, not foreclosed - this is roughly the WS2
  shadow-observability direction, to be specified when a credible pitch
  head exists.

### Alternative C: Re-aim PRE's primary away from edge

- Follow the [141] pattern directly: declare a new primary for PRE (for
  example its calibration, ECE 0.0036) that it passes, and promote on
  that.
- Considered, and this is the LEGITIMATE escape hatch in principle. But
  PRE has no honest re-aim on offer today. Edge is the whole point of a
  pitch-outcome model; its calibration, while good, is not the model's
  headline claim the way per-park calibration WAS the honest claim for the
  batted-ball outcome model. Re-aiming PRE to calibration because
  calibration passes - not because calibration is what PRE claims - is
  exactly the dishonest re-aim the Decision section forbids. Revisit if a
  defensible re-aim emerges.

## Revision History

(none)
