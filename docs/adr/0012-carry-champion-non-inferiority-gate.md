# ADR-0012: Promote the carry champion (battedball_outcome v2) on a non-inferiority gate plus a hard carry sanity gate, not a beats-the-LR-baseline gate

- **Status**: Accepted
- **Date**: 2026-06-26
- **Deciders**: alex
- **Related**: `decisions.md` entries [166] [165] [164] [163] [154] [150] [141] [72]; ADR-0011 (the re-aim discriminator this gate is checked against); CLAUDE.md discipline rules 5, 6, 7, 9; plan.md Phase 4; design.md "Pre-declared promotion criteria"

## Context

`battedball_outcome` v2 is NOT a new outcome model. It is v1's exact served
`BattedBallMLP` outcome model with an ADDITIVE per-park carry-distance head
bolted onto the shared backbone (PRs #161 / #163). Because carry is an
additive output and not a change to the feature inputs, the feature schema
hash is UNCHANGED, so rule 7 holds and no re-registration of the feature
pipeline is required. The new capability v2 buys is a per-park carry
distance for `/parks` and the game page (the rendering is already merged and
dormant, waiting on a carry champion).

This model family is, per [141] and [163], a calibrated per-park PHYSICS
ESTIMATE, and we have been honest about that on the user-facing surfaces. On
REALIZED outcomes it loses to its rule-9 LR baseline (v2 multiclass Brier
~0.117 vs ~0.086) - the documented reality gap. The current champion v1
ALSO fails that beats-LR bar: it never produced a passing
`experiment_results` row and serves only via the first-champion bootstrap
exemption ([141] / [150] INC-6). So "beats the LR baseline" was never this
model's honest headline claim, for v1 or v2.

The registry gate is mechanical and unforgiving. `RegistryService
.assertPromotionCriteriaMet` calls `ExperimentResultsRepository
.findLatestPassing`, which requires a FRESH passing `experiment_results` row
bound to (model_name="battedball_outcome", champion_version_id=v1,
challenger_version_id=v2). Per ADR-0011 and [150]/INC-6 the bootstrap
exemption is FIRST-version-only: a single only-registered version keeps the
exemption, but registering a v2 re-arms rule 5 and there is then no
exemption to lean on. v2 genuinely needs a passing row, which means we must
pre-declare, before the box runs anything, what "passing" means for v2
(rule 5).

That forces the real question: what is v2's honest claim OVER v1, and what
gate tests exactly that claim? ADR-0011 supplies the discriminator. An
honest re-aim of a primary reflects what the model actually claims and
genuinely passes on its own terms (the [141] batted-ball pattern: cross-park
rho -> per-park calibration). The abuse ADR-0011 forbids is re-aiming a
primary to a metric chosen BECAUSE it passes rather than because it is the
model's honest claim. So the v2 primary has to be chosen on the basis of
what v2 claims, not on the basis of what is convenient to pass.

## Decision

We promote `battedball_outcome` v2 on a NON-INFERIORITY gate against the
current champion v1, plus a SEPARATE hard carry sanity gate, and explicitly
NOT on a beats-the-LR-baseline gate.

v2's honest claim over v1 is precise: "add a per-park carry capability
WITHOUT regressing the served outcome." The pre-declared rule-5 criteria
(`_BATTED_BALL_CARRY` in `training/.../eval/promotion/criteria.py`, the
rule-5 source of truth) test exactly that:

- **Primary**: multiclass Brier NON-INFERIORITY vs v1. Threshold = -0.002.
  The negative sign IS the semantics: a negative threshold is a
  non-inferiority margin, so the gate reads WOULD_PASS iff
  `v2_brier <= v1_brier + 0.002`. It is not "beat v1 by 0.002"; it is "do
  not regress the served outcome by more than 0.002."
- **Guardrails (hard, and they take precedence)**: log-loss non-regression
  (max_delta 0.01) and ECE non-regression (max_delta 0.015) vs v1. A
  WOULD_FAIL_GUARDRAIL overrides a primary pass.
- **A separate HARD carry sanity gate**: every per-park carry must be
  finite and within [50, 550] ft. This is ANDed into the artifact status
  (it is a must-pass, not a soft check).

There is deliberately NO absolute ECE bar in this eval. The served per-park
isotonic calibration, re-fit and verified at registration (the [151] load
gate plus the calibrator contract), is the absolute-calibration gate. This
eval scores the RAW softmax head-to-head so it isolates the carry head's
effect on the shared backbone, rather than re-measuring calibration that the
registration path already guarantees.

The locked margin and its justification (recorded here, not only in code):
0.002 multiclass Brier is roughly 2% of the ~0.11 realized Brier and roughly
5x the rolling-origin fold standard deviation (~0.0004). It is small,
bounded, and pre-declared before the box runs the ablation.

The realized-vs-LR gap (v2 Brier ~0.117 vs LR ~0.086) is carried as an
explicit NON-GATING documented fact, exactly as [163] already surfaces it on
`/parks`. It is not the primary, it is not hidden, and it does not block
promotion - because it is not a claim v2 makes.

The pre-commitment (this is the load-bearing integrity clause): if the box
ablation shows a carry-induced outcome regression greater than 0.002, v2 is
NOT promoted AND the 0.002 margin is NOT widened post-hoc. Instead the carry
head is reworked so it does not perturb the shared backbone (for example
detach the carry gradient or freeze the backbone), and the eval is re-run.
Quietly relaxing the margin after a bad box result is the precise bypass this
gate exists to prevent.

The evidence mechanism is a 4-fold rolling-origin NON-INFERIORITY ABLATION
(`training/.../battedball/mlp/carry_promotion_eval.py`). Per fold, the served
`BattedBallMLP` recipe is trained WITHOUT carry (carry_weight=0, which is
v1's method and the baseline) and WITH carry (carry_weight=1, which is v2 and
the challenger) on identical folds with an identical seed, so the ONLY
difference between the two arms is the carry objective. Both are scored on
the home-park realized outcome, paired. The harness is leakage-audited
(ml-leakage-auditor PASS) and the rolling-origin discipline is intact (rule
13: 2026 never enters a training or validation split).

Scope and process: promotion stays HUMAN-GATED (rule 6). This ADR records
the pre-declared criteria and AUTHORIZES the box register / promote / deploy
ceremony to RUN. It performs no promotion itself and is not auto-promotion.
Rules 5, 6, 7, and 9 are unchanged - this introduces a new promotion-criteria
SHAPE alongside the existing beats-baseline shape, under the same rule-5
discipline.

## Consequences

**Easier:**

- v2's carry capability can serve on an honest, pre-declared gate that the
  model can actually pass on its own terms (non-regression of the served
  outcome), without pretending a physics estimate beats its LR baseline on
  realized outcomes.
- `/parks` and the game-page carry rendering (already merged, dormant) light
  up real per-park carry with no model swap and no schema change (carry is
  additive, schema_hash holds, rule 7).
- The discipline story stays clean: a re-armed rule 5 is satisfied by a real
  pre-declared criteria row bound to (champion, challenger), not by another
  bootstrap exemption. v2 is gated, not waved through.

**Harder:**

- The registry now carries two promotion-criteria SHAPES: beats-baseline
  (for a model whose claim is predictive edge, like the pitch heads) and
  non-inferiority-vs-champion (for an additive-capability upgrade like this
  one). A future reader must consult the criteria row (`criteria.py`) to
  know which shape applies; "promotion means beats baseline" is no longer a
  safe default assumption.
- A non-inferiority margin is a judgment call that has to be defended in
  writing every time. We defend this one (~2% of realized Brier, ~5x fold
  std), but a poorly chosen margin could wave a real regression through. The
  guardrails, the hard carry sanity gate, and the pre-commitment are the
  mitigations.
- The realized-vs-LR gap stays a standing honest caveat that must keep being
  surfaced ([163] already does so on `/parks`). Promoting v2 does not close
  it and is not claimed to.

**New failure modes (and their guards):**

- Margin creep: a bad box ablation tempts widening 0.002 after the fact.
  Guard: the written pre-commitment (rework the carry head, do not widen the
  margin) plus a unit test proving the gate can FAIL (a >0.002 carry-induced
  regression flips status to failed).
- Shape confusion: someone reads "non-inferiority" as "a weaker bar" and
  applies it where a beats-baseline claim is the honest one (for example a
  pitch head whose entire point is predictive edge). Guard: ADR-0011's
  discriminator still governs. The shape must match the model's honest
  claim; non-inferiority is correct here ONLY because v2 makes no new
  predictive-edge claim over v1.
- Self-referential ratchet: gating against the current champion rather than
  an absolute bar means a slowly degrading lineage could drift downward over
  successive promotions. Guard: the hard guardrails (log-loss and ECE
  non-regression) plus the absolute per-park isotonic calibration gate at
  registration ([151]) anchor each promotion, and carry, the only new
  capability, has its own absolute hard sanity bound of [50, 550] ft.

**Locked into:**

- v2, and any future additive-capability upgrade in this lineage, is gated
  on non-inferiority versus the serving champion plus a hard sanity gate for
  the new capability, with the criteria pre-declared in `criteria.py` and
  bound to (champion, challenger) in `experiment_results`. Re-aiming the
  margin to fit a bad result is a re-decision via `/decide`, never a quiet
  edit.

## Alternatives Considered

### Alternative A: Promote on the faithful eval's beats-LR row

- Use the faithful rolling-origin CV evidence row (the one that scores v2's
  realized outcome against the LR baseline) as the rule-5 gate, and either
  declare it passing or wave v2 through on it.
- Rejected: that row honestly reads status=failed (v2 Brier ~0.117 vs LR
  ~0.086). Treating a failed-primary row as a pass, or re-aiming so it
  "passes," is exactly the threshold bypass ADR-0011 forbids. The beats-LR
  primary tests a claim v2 does not make - v2's claim over v1 is a new
  capability without outcome regression, not a predictive edge over the
  baseline.

### Alternative B: Hold v2 in shadow forever

- Register v2 SHADOW and never promote it, leaving v1 as the serving
  champion indefinitely so the carry rendering stays dormant.
- Rejected: the carry capability is sound (the hard carry sanity gate
  passes; per-park feet are plausible), the `/parks` and game-page carry
  rendering is built and waiting, and v2 does not regress the served outcome.
  Shadow-forever is the right answer for a model with no honest passing
  claim; v2 HAS one (non-inferiority on the served outcome plus a passing
  carry sanity gate), so refusing to ever serve a validated, non-regressing
  capability would be discipline theater, not discipline.

### Alternative C: Re-aim to a self-referential calibration metric the model "passes"

- Declare some calibration metric that v2 happens to pass as the new primary
  and promote on that.
- Rejected: this is precisely ADR-0011's Alternative-A bypass - choosing a
  metric BECAUSE it passes rather than because it is the model's honest
  claim. v2's honest claim is "carry capability without outcome regression,"
  which non-inferiority-vs-champion tests directly. A convenient calibration
  pass would be the dishonest re-aim ADR-0011 exists to forbid. (And the
  absolute calibration question is already answered by the registration-time
  per-park isotonic gate, so there is nothing for a calibration primary to
  add here anyway.)

### Alternative D: Literal v1-artifact-vs-v2-artifact on a single 2025 holdout

- Skip the ablation and just score the two shipped artifacts (v1's model.onnx
  and v2's model.onnx) against each other on a single 2025 holdout year.
- Rejected: comparing two independently trained artifacts on one year
  confounds the carry effect with training noise (different runs, different
  stochasticity, no shared seed), so a measured delta could be the carry head
  OR just run-to-run variance. A single-year split also deviates from the
  4-fold rolling-origin discipline (the eval rule). The per-fold ablation
  (carry_weight=0 vs carry_weight=1 on identical folds and identical seed)
  isolates the carry objective as the only difference, which is exactly what
  the non-inferiority claim needs to measure.

## Revision History

- **2026-06-28** - Backend integration that makes this gate ENFORCEABLE (decision [167]).
  The criteria + the offline ablation were training-side ([166]), but the backend
  `experiment_results` machinery only supported ONLINE shadow comparisons (start ->
  evaluate-from-`prediction_log` -> complete; `StartExperimentRequest` rejects a negative
  threshold), so the offline non-inferiority gate had no path into the table the promote gate
  (`assertPromotionCriteriaMet` -> `findLatestPassing`) reads. The box correctly ABORTED the
  promotion at the evidence step rather than hand-edit SQLite. Added an OFFLINE-evidence import path:
  `POST /v1/admin/experiments/import-offline` -> `OfflineGateImportService` turns a committed,
  BUNDLED `*_promotion_gate.json` (read by `OfflineGateEvidenceRepository` from
  `classpath:offline-gate-evidence/`, a separate dir from `accuracy-evidence/` so it never reaches
  the `/accuracy` scorecard) into a terminal `passed` row binding the CURRENT champion to the
  challenger. Anti-bypass (registry-guard PASS): imports only a bundled+reviewed artifact (no
  operator-posted JSON); RE-DERIVES the pass from the raw numerics (challenger + threshold <=
  champion, observed guardrail deltas vs declared maxes, carry hard gate) instead of trusting the
  declared `status`; asserts the challenger's `model_name`; binds the current champion; performs NO
  promotion (rule 6). Does not touch the online lifecycle. A full register -> promote(bootstrap) ->
  register -> import -> promote IT proves the imported row clears the real gate end-to-end. Status
  stays Accepted.
- **2026-06-28 FOLLOW-UP (open, registry-guard NOTE 1)**: the gate artifact carries no challenger
  identity (no version id / `feature_schema_hash` / `training_data_hash`), so the
  evidence-to-challenger binding is operator-asserted - the offline path cannot bind by served
  predictions the way the online path does. Harden by embedding the challenger `feature_schema_hash`
  in the gate JSON and asserting it against the registered version at import. Mitigated today by
  admin-only + current-champion binding + recorded `git_commit` provenance.
