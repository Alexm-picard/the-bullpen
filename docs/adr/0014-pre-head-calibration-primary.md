# ADR-0014: The pre-pitch head's declared primary is absolute calibration, not accuracy

- **Status**: Accepted
- **Date**: 2026-07-20
- **Deciders**: alex
- **Related**: `decisions.md` [72] [141] [145] [150] [154] [180]; ADR-0011; CLAUDE.md rules 5, 6; the 2026-07-19 engineering audit

## Context

The audit's #1 finding is that no user-visible pitch prediction exists. ADR-0011
established that pitch_outcome_pre v1 FAILED its declared primary (Brier edge
0.00084 vs a 0.002 threshold) and therefore could not bootstrap-promote. But
ADR-0011 also recorded that PRE beats its rule-9 LR baseline on all three
metrics and is well-calibrated (ECE 0.0036).

The batted-ball first champion (decision [141]) faced the identical situation
and resolved it honestly: it RE-AIMED its declared primary from a metric it
failed (cross-park rho, 0.333) to the metric it genuinely claims and passes
(per-park outcome calibration, ECE 0.0005 against a <0.02 gate). That is not a
weakening of rule 5 - it is a correction of the *declared claim* to what the
model actually earns, then an honest pass against it.

PRE is a calibrated pre-pitch outcome-distribution estimator. Its value is a
trustworthy probability distribution over the 5 outcome classes, not a
best-guess accuracy win. Declaring accuracy (Brier edge) as its primary was the
wrong claim for what it is.

## Decision

pitch_outcome_pre's declared primary metric is **absolute expected calibration
error: ECE < 0.02** (the absolute_ece_bar already encoded in criteria.py and the
same bar the batted-ball champion cleared). PRE passes at ECE 0.0036.

Guardrails (must all hold, so this is not a soft landing):

- PRE must beat the co-registered LR baseline on **Brier** (lower is better).
- PRE must beat the LR baseline on **log-loss** (lower is better).

PRE passes both.

The model's honest public claim, surfaced in the UI, is exactly this:
"calibrated pre-pitch outcome probabilities (ECE < 0.02), strictly better than
the linear baseline; makes no accuracy-superiority claim."

This unblocks a user-visible forward-looking next-pitch prediction while keeping
rules 5 (pre-declared criteria + passing experiment_results row) and 6
(human-gated promotion) fully intact.

## Consequences

**Easier:** the flagship capability becomes visible and honest; the drift/serving
story now covers a model users actually consume.

**Harder / watch:** a future PRE retrain must re-clear ECE < 0.02; if calibration
drifts upward the champion must be pulled. The calibration lane (not accuracy) is
now the promotion gate for this head.

**Locked into:** PRE's claim is calibration. Any future attempt to market it as an
accuracy win is a re-decision, not a quiet reframing.

## Alternatives Considered

- **Keep the hold ([154]/ADR-0011 as-is), surface only the retrospective post
  scorecard.** Rejected: lowest resume payoff; leaves the flagship invisible.
- **Confidence-gated demonstration surface (shadow, "did not pass gate" label).**
  Rejected: honest but weaker than a real promoted champion; the re-aim path is
  both honest AND a real champion.
- **Bootstrap-promote on the failed Brier primary.** Rejected by ADR-0011 and not
  revisited - the exemption never covered a failed primary.

## Revision History

- 2026-07-20 - Created. Annotates ADR-0011 (which stays Accepted): ADR-0011's bar
  on the FAILED-primary path is unchanged; this ADR changes PRE's DECLARED primary
  to the metric it honestly passes, exactly as [141] did for batted-ball.
