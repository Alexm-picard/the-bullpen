# ADR-0016: `prediction_log` records delivered predictions, not internal evaluations

- **Status**: Accepted
- **Date**: 2026-08-04
- **Deciders**: alex
- **Related**: `decisions.md` entries [188], [30], [143], [175], [176]; `design.md` §3.2 (A/B routing + logging), §3.3 (drift detection), §4.3 (forward simulation); ADR-0012 (the promotion-evidence path that reads this table); rule 5

## Context

`prediction_log` is the ClickHouse table every model-quality claim in this project ultimately
rests on. Three independent consumers read it, and all three were built against the same
unstated assumption:

- **Drift PSI** (`drift/RealFeatureDistributionFetcher`, `drift/RealPredictionDistributionFetcher`)
  samples `features` and `prediction` over a time window, scoped by `model_name` +
  `model_version_id`, and compares that empirical distribution against the training baseline. Its
  answer is only meaningful if the sample is "the inputs production actually sent this model."
- **Promotion evidence** (`registry/experiment/PairedPredictionFetcher`, the online leg behind
  rule 5 and ADR-0012) pairs champion and challenger rows on the same request to compute the
  metric a promotion is gated on.
- **Truth-join** (`drift/ClickHouseTruthJoinedPredictionFetcher`, `data/RollingAccuracyRepository`,
  `data/LivePitchesRepository`) joins logged predictions to realized outcomes on the live key
  `(game_id, at_bat_index, pitch_number)`.

The assumption is never written down anywhere: **each row is an input the served model actually
received in production.** The table's contents are in fact already heterogeneous. Worker-path
rows from `LivePitchPredictor` (decision [143], predict-the-next-pitch) carry live keys.
HTTP-path rows carry NULL live keys by construction and are pruned from the truth-join by an
explicit `game_id IS NOT NULL` filter. Decision [175]'s induced-drift drill wrote synthetic rows
tagged with a `drill:` `correlation_id` prefix and deleted them afterward. Phase 1's
`_toy_batted_ball` rows are still there, excluded by nothing, surviving only because every
consumer scopes by model name and time window.

The forcing case is a proposed display feature: replay the batted-ball champion over a team's
historical balls in play (a couple of hundred evaluations per team per page render) and show one
aggregate number. The obvious question was cost. The real question turned out to be population.
Only an order of a hundred genuine `battedball_outcome` rows exist from the parks-page path; a
single render of the new feature would produce more rows than the model's entire production
history, and its PSI lane would stop measuring "what inputs the batted-ball model sees" and start
measuring "which teams people looked at before first pitch." Logging intermediates does not add
volume to a measurement; it redefines what is being measured.

Three attempts to state the boundary failed before the fourth held, which is why this needs an
ADR rather than a one-line entry: the failures are the content.

## Decision

**A model evaluation is logged to `prediction_log` when its result is delivered as an answer. An
evaluation that exists only as an intermediate in computing something else is not logged, and
must instead emit its own metrics.**

Scope and mechanics:

- **The test binds the INPUT, not the model leg.** For a delivered input, the serving row *and*
  every shadow/challenger row evaluated on that same input are logged. That dual-log is precisely
  what shadow routing is (`InferenceRouter`, glossary "shadow routing") and what rule-5 online
  evidence consumes. "Delivered" qualifies the input, not the individual leg; a shadow leg is not
  excluded for being invisible to the user.
- **The discriminator is a cardinality test, checkable by inspection: when N evaluations collapse
  into 1 delivered value, the N are intermediates.** No judgment about requesters, profiles, or
  realism is required.
- **1-to-N is untouched.** `POST /v1/predict/batted-ball/all-parks` takes one input and delivers
  30 per-park probabilities from one inference; it logs one row whose `prediction` payload is the
  full per-park distribution. That is 1-to-N, not N-to-1, so it logs, and nothing about this
  decision changes it.
- **Internal evaluations MUST be observable some other way.** Bypassing the log may not trade
  pollution for invisibility. The required minimum is latency, count, and errors under a metric
  name distinct from the served-prediction histogram. `/v1/simulate` is the reference
  implementation: `thebullpen_inference_simulate_latency_seconds` with `role="simulator"`
  (decision [176]).
- **Scope**: every code path that can reach a registered model, in any profile, present or future.

This **codifies existing practice rather than inventing a rule**. `/v1/simulate` runs about 12
per-state ONNX probes to deliver one plate-appearance answer and already writes nothing to
`prediction_log`; neither `api/SimulateController` nor anything under `simulation/` references
`AsyncPredictionLogger`. Decision [176] reached that outcome by reasoning about routing coherence
and drift hygiene, without naming the general rule. It is this rule's first instance.

### The three phrasings that failed

**Failure 1: "predictions SERVED TO A REQUESTER."** The intuitive phrasing, and wrong at the
majority case. `LivePitchPredictor` is `@Profile("worker")`; it runs on the poll tick with no HTTP
requester anywhere, and it produces the bulk of the table, essentially all of it carrying live
keys. Those rows are the drift baseline and the entire truth-join. A requester-based rule would
have excluded exactly the population it was written to protect.

**Failure 2: "a real game event the system predicted in the normal course of operation."** Fails
at the opposite edge. A user dragging launch-speed and launch-angle sliders on `/parks` is not a
game event, yet those inputs are real inputs the served champion received, and drift over them is
meaningful signal about what the model is being asked to do. That path is the order-hundred
`battedball_outcome` rows (plus the tens of thousands of legacy `_toy_batted_ball` rows), all
NULL-keyed. A realism-based rule would have excluded a genuine production population and left the
batted-ball model with no observed side at all.

**Failure 3: "evaluations whose result is delivered as an answer," read leg-by-leg.** The
surviving formulation is itself wrong if applied to each model leg independently: a shadow or
challenger leg's result is by definition never delivered, so a strict leg-wise reading would
exclude the rule-5 online evidence population and the shadow half of every drift lane. This one
was caught during drafting rather than in review, which is why the Decision above scopes the test
to the input rather than the leg. It is recorded here because it is the failure a future reader is
most likely to reproduce: the wording sounds leg-wise.

**What survives.** Answer-versus-intermediate, scoped to the input, is the only formulation that
gets all four cases right: worker rows in (delivered, no requester), slider rows in (delivered,
not a game event), shadow legs in (delivered input, undelivered leg), page-aggregate probes out
(N collapsing to 1). It is also the only one that is mechanical rather than interpretive.

## Consequences

**Easier:**

- The unstated assumption behind three consumers is now stated, so the drift, evidence, and
  truth-join populations mean something defensible.
- New feature review has a one-question test with an inspectable answer: how many evaluations back
  each delivered value?
- The rule generalizes to a shape this project will keep producing: "run the model over a set of
  rows to show something." Leaderboards, team aggregates, backfilled scorecards, park comparisons.
- It gives the reasoned decline in [176] a general home, so the next instance is not re-argued from
  scratch.

**Harder:**

- Two paths now reach the same model with different logging obligations, and **nothing in the type
  system enforces which is which.** Both paths can construct a `PredictionLogEvent`; the boundary
  is maintained by discipline and review, not by the compiler.
- Internal evaluations are invisible unless someone remembers the metrics clause. That is why it is
  a MUST, but a MUST is still a convention. The concrete failure this guards against is a
  page-level feature quietly burning hundreds of inferences per render with nothing in Grafana to
  show it.
- Margin cases still need judgment. A feature that delivers both a summary AND the per-row values
  is 1-to-1 per row and therefore logs; a feature that shows a sparkline of 200 points delivers 200
  values and logs. The cardinality test resolves these, but only if it is actually applied rather
  than pattern-matched from a prior feature.

**New failure modes:**

- **Silent over-logging.** A future feature logs its intermediates because the author copied the
  orchestrator path, and the pollution appears as a PSI shift, not as an error. Nothing alerts.
  Detection today is a human noticing an implausible row count.
- **Silent under-logging.** A genuinely delivered prediction is skipped because its author read
  this ADR as "avoid logging," costing a promotion its online evidence. The mitigation is the
  wording: the default is log, the exemption is narrow.

**Locked into:**

- `prediction_log` is a production-input record, not a general model-call audit trail. If a future
  need requires the audit-trail shape, it gets its own table, not this one relaxed.
- Any new path to a registered model owes a metric name.

**Follow-on work (not done here):**

- No automated gate exists. A worthwhile future pin is a test or ArchUnit rule constraining which
  components may construct a `PredictionLogEvent` (today: `PredictionOrchestrator`,
  `LivePitchPredictor`, `PitchTypePredictionService`, and the drill injector), so a new logging
  site becomes a deliberate, reviewed act.

## Alternatives Considered

### Alternative A: Phrase the rule as "predictions served to a requester"

- The boundary would be "an HTTP request produced this row."
- Rejected: factually wrong at the majority case. `LivePitchPredictor` has no requester and
  produces most of the table plus the entire truth-join population. Adopting this phrasing would
  have made the project's most important prediction rows technically out of contract, and any later
  literal reading of it would have justified deleting them.

### Alternative B: Phrase the rule as "a real game event, predicted in the normal course of operation"

- The boundary would be "this prediction corresponds to something that happened in a baseball game."
- Rejected: wrong at the opposite edge. Parks-page slider inputs are counterfactual by design, yet
  they are real inputs the served champion received and are the only observed-side population the
  batted-ball model has. This phrasing would have deleted the batted-ball drift lane while claiming
  to protect it.

### Alternative C: Log everything, tag the internal rows

- Write the intermediate evaluations with a discriminating tag and have consumers filter, following
  the E-2 induced-drift drill (V027 added the tag column to `drift_metrics`; the `prediction_log`
  side reused the existing `correlation_id` with a `drill:` prefix, no migration).
- Rejected: the precedent does not transfer. The drill's injection was **deliberate and bounded**
  and was cleaned up afterward by an explicit
  `ALTER TABLE prediction_log DELETE WHERE correlation_id LIKE 'drill:%'`. Display-aggregate
  traffic would be **continuous and ambient**, which converts a one-off tag into a standing filter
  that every current and future consumer must remember, forever, including consumers not yet
  written. The in-repo evidence that this does not hold: the legacy `_toy_batted_ball` rows are
  excluded by no filter anywhere in the codebase. They are invisible only incidentally, because
  every query happens to scope by model name and a recent time window. A tag whose enforcement is
  "everyone remembers" is not a boundary.

### Alternative D: Precompute the aggregate in a worker job

- A scheduled job computes the per-team aggregate ahead of time; the page reads a materialized
  value.
- Rejected: this relocates the decision instead of answering it. The job still evaluates the
  champion N times and still has to decide whether those N rows land in `prediction_log`, so the
  question is unchanged and now lives somewhere less visible. It also adds staleness and scheduling
  infrastructure to solve a cost problem that a per-game memo already solves, and it settles
  nothing general: the next feature of this shape starts the argument over.

### Alternative E: Say nothing and decide case by case

- Handle each feature on its merits at review time.
- Rejected: [176] already decided one instance of this on narrower grounds. A second instance
  arriving with a different answer, decided by whoever reviewed it that week, is how the table's
  meaning erodes without anyone choosing to erode it. The population assumption is load-bearing for
  rule 5; it deserves a written boundary.

## Revision History

- (none yet)
