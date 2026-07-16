> **Drill - not real production drift.** This is the controlled LIVE-PATH
> induced-drift drill (decision [175]'s primary path for the Phase-6 drift
> postmortem). The distribution shift was injected deliberately, on production
> infrastructure, to prove the detect -> alert chain end-to-end against the
> real ClickHouse `prediction_log` and to exercise the operator triage process.
> Every synthetic row was tagged and excludable by construction, and the drill
> surfaced two real defects (a cleanup-endpoint privilege mismatch - the
> least-priv grants working exactly as designed against an endpoint that assumed
> mutation rights - and a documentation error) that a purely in-test drill could
> never have found - which is the point.
> [169]'s natural-event-supersedes clause stands: if a real in-season drift
> event fires and is confirmed, its write-up supersedes this one and this
> document reclassifies as a drill report.

# Postmortem: live-path induced feature drift on `battedball_outcome` (E-2)

- **Date range**: 2026-07-15 -> 2026-07-16 (one injected night + close-out)
- **Severity**: NOTICE (feature PSI) - deliberately induced
- **Affected model**: `battedball_outcome` (active CHAMPION; carry-head v2 line, [168])
- **Detected by**: `PsiFeatureJob` (2 AM ET, `launchSpeedMph` PSI vs the champion's
  `metadata.json` training baseline) -> `DriftAlertEvaluator` (3 AM ET, NOTICE
  threshold 0.25, sustain window set to 1 day for the drill via #277)
- **Operator**: alex (box, per ADR-0006); Mac-side tooling + this write-up: dev
- **Companion artifact**: `2026-07-15_c31-retrain-saga.md` - C-31 proved the
  trigger -> retrain -> register control plane ([178]); this drill proves the
  detect -> alert front half. Together they cover the ML-systems loop.

**Summary.** A committed, reviewed, admin-gated injector (`DriftInjectionService`,
PR #276) wrote synthetic `battedball_outcome` rows into the production
`prediction_log` with `launchSpeedMph` shifted +1 sigma off the champion's own
training baseline. The real 2 AM `PsiFeatureJob` computed the feature PSI over
its ordinary 24h window and wrote a tagged over-threshold `PSI_FEATURE` row into
`drift_metrics`; the real 3 AM `DriftAlertEvaluator` fired the feature-drift
NOTICE to Discord and recorded the `alert_history` row. Cleanup then removed
every synthetic row. Detection and alerting ran on production code paths,
production tables, and the production schedule - nothing was mocked. The drill
also exposed two gaps, both now fixed: the cleanup endpoint could not perform
its mutation under the least-privilege ClickHouse user (GAP 1), and the drill
docs wrongly implied the automated retrain leg fires from feature PSI (GAP 2).

## Why induced, and why now (honesty section)

This drift was **synthetic**. Decision [175] made the induced drill the PRIMARY
path for this artifact after the 2026-07-04 scheduled PSI run wrote zero rows:
natural drift was both undetectable (no champion carried a training baseline
until E-1) and unlikely (thin break-window traffic). Waiting for a natural event
risked never delivering a rule-4 no-cut Phase-6 artifact. The drill ran a month
ahead of [169]'s 2026-08-15 tripwire, during the All-Star break (no live games,
near-zero organic traffic - the cleanest possible conditions and zero deploy-rule
tension). The PSI values are real math on real tables; only the data was staged.

Equally honest about scope: this drill exercised the **feature-PSI lane only**.

- The calibration (ECE / PAGE) lane is not inducible for `battedball_outcome`
  via `prediction_log` injection: `CalibrationJob`'s truth-join
  (`ClickHouseTruthJoinedPredictionFetcher`) is pitch-family only
  (`game_id IS NOT NULL` + a pitch-vocabulary payload), and the batted-ball
  champion's calibration is offline by design (the `/accuracy` scorecard + the
  isotonic promotion gate). A pitch-family variant during live games is the
  post-season follow-up if ever wanted.
- The automated retrain leg did not and could not fire: `DriftTrigger` (4 AM ET)
  keys exclusively on 7-day sustained `CALIBRATION_ERROR`. For this lane the
  NOTICE is the terminal signal (see GAP 2 - the drill docs originally implied
  otherwise). The retrain control plane is separately proven on real data by
  BOX HAND-OFF #1 ([178]).

## Timeline

Merge instants are git-provable (ET, commit clocks); the `v{date-time}` release
tags carry a UTC clock (so `v2026.07.16-0040` = 20:40 ET on 07-15). Box instants
are operator-relayed from the ops journal; the authoritative instants are the
`computed_at` / `fired_at` columns in the retained evidence rows.

| When                                        | What                                                                                                                                                                                                                                                                                                                                                                                  |
| ------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 2026-07-04                                  | [175] locks the induced drill as the primary path, with tagging hygiene as a hard constraint                                                                                                                                                                                                                                                                                          |
| 2026-07-14/15                               | Preconditions land on the box: E-1 baselines backfilled (champion `metadata.json` carries `feature_distributions`); #274/[179] CH cap 6g                                                                                                                                                                                                                                              |
| 2026-07-15 12:02 ET                         | #275 merges: the tagging half (V027 `drift_metrics.tag` + the `BULLPEN_DRIFT_TAG` choke point). Deployed as release `v2026.07.15-2335` (19:35 ET)                                                                                                                                                                                                                                     |
| 2026-07-15 20:34 ET                         | #276 merges: the live-path injector (self-calibrating +N-sigma shift, `drill:` correlation prefix, refuses unless the drift tag is armed). Deployed as release `v2026.07.16-0040` (20:40 ET)                                                                                                                                                                                          |
| 2026-07-15 21:15 ET                         | The pre-run review catches the 7-day sustain gate BEFORE the drill night (path C): a one-night injection could never fire the NOTICE at the default. #277 merges: `feature-psi-notice-days` configurable (default 7 = prod unchanged; the drill arms 1). No release tag records its deploy - it reached the box untagged (relayed as deployed; a deploy-tag ritual miss worth noting) |
| night 07-15 -> 07-16 (relayed)              | Box arms the five drill env vars + injects; the injector writes the synthetic batch through the real `PredictionLogWriter`                                                                                                                                                                                                                                                            |
| 2 AM / 3 AM ET cycles, 2026-07-16 (relayed) | `PsiFeatureJob` writes the tagged over-threshold `PSI_FEATURE` row; with days=1 armed, `DriftAlertEvaluator` fires the **feature-drift NOTICE** (Discord + the `alert_history` row for `drift/battedball_outcome/psi_feature/launchSpeedMph`). The operator captures the detection row at 03:15                                                                                       |
| 2026-07-16 (relayed)                        | Cleanup attempt: `DELETE /v1/admin/drift/synthetic` returns **500 - ClickHouse Code 497 ACCESS_DENIED, `ALTER DELETE ON default.prediction_log`** as the least-priv `bullpen` user (**GAP 1**); operator performs the mutation manually as the CH admin user; drill env disarmed                                                                                                      |
| 2026-07-16                                  | Drill closed. Tagged `drift_metrics` rows retained as evidence; this postmortem + the GAP 1/GAP 2 fixes authored Mac-side (ADR-0006)                                                                                                                                                                                                                                                  |

## What fired (evidence)

The synthetic `prediction_log` rows are gone (cleaned up - that is the drill's
hygiene working). The durable evidence is the tagged metric + alert trail, which
persists deliberately:

```sql
-- The induced detection rows (ClickHouse, box):
SELECT computed_at, feature_or_segment, metric_value, sample_size, tag
FROM drift_metrics
WHERE tag = 'induced-drill-2026-07'
ORDER BY computed_at;
-- Expect: PSI_FEATURE row(s) for launchSpeedMph with metric_value > 0.25,
-- plus the sibling per-feature rows computed in the same job runs.
```

```sql
-- Zero synthetic rows remain (ClickHouse, box):
SELECT count() FROM prediction_log WHERE correlation_id LIKE 'drill:%';  -- 0
```

```sql
-- The alert trail (SQLite registry, box):
SELECT * FROM alert_history
WHERE alert_key = 'drift/battedball_outcome/psi_feature/launchSpeedMph';
```

Plus the Discord NOTICE in the ops journal (operator-relayed). Exact instants
and metric values live in those rows; this document deliberately does not
restate numbers it cannot itself verify from the Mac (the relay discipline in
ADR-0006's revision history).

## What the drill proved

1. **The live detect -> alert chain works on production infrastructure.** Real
   `prediction_log` writes through the real serving writer, real
   `RealFeatureDistributionFetcher` SQL over the real `features` JSON, real
   `Psi.computeContinuous` against the champion's real training baseline, real
   scheduler, real Discord. The in-test `DriftInductionDrillIT` (2026-05-30
   drill) proved the math; this proved the plumbing the math rides on.
2. **The [175] hygiene held end-to-end.** Every synthetic `prediction_log` row
   carried the server-set `drill:` correlation prefix; every induced
   `drift_metrics` row carries `tag='induced-drill-2026-07'` via the single
   `DriftMetricsRepository` choke point; organic baselines exclude on
   `WHERE tag = ''`. Structural isolation exceeded the design: drill rows carry
   `game_id = NULL`, so the promotion gate and the calibration lane could never
   have seen them even without the tag.
3. **The operator triage path is real.** The NOTICE was verified and walked per
   `feature-drift-investigation.md` - the drill exercised the human half, which
   is the half a postmortem process actually needs rehearsed.
4. **Reviews and pre-flight caught what they should have.** The ml-leakage
   audit caught the two-JVM tag-arming hazard before the run (worker stamps the
   tag; api guards injection - arming only one unit would have mis-tagged the
   metric rows as organic). The TD's pre-run review caught the 7-day sustain
   gate that would have silently swallowed the NOTICE (path C, #277).

## What went wrong (the two gaps)

**GAP 1 - the cleanup endpoint advertised a mutation it could not perform.**
`DELETE /v1/admin/drift/synthetic` issues `ALTER TABLE prediction_log DELETE`,
but the app's least-privilege `bullpen` CH user ([171]) deliberately carries no
mutation grants - the grants template even lists `ALTER DELETE` under
"deliberately ABSENT". The endpoint 500'd with Code 497 and the operator had to
clean up manually as the admin user. The IT never caught it because the test
container's user is an admin. **Fix (this PR): the mutation now runs over a
separate one-shot connection as the CH admin identity**
(`BULLPEN_DRIFT_CLEANUP_ADMIN_USER/-PASSWORD`, armed only for the drill window,
disarmed with the other drill vars), and the endpoint refuses with a 400 naming
those vars when they are unarmed. The grant was deliberately NOT widened: the
grants template calls mutation absence load-bearing and widening "a re-decision,
not a quiet grant widening" - the drill hitting 497 is that file working.

**GAP 2 - the drill docs implied the retrain leg fires from feature PSI.** The
runbook and PR texts said the NOTICE "enqueues the DriftTrigger retrain".
`DriftTrigger` is calibration-driven only; the feature-PSI NOTICE is terminal
for this lane. **Fix (this PR): runbook + code comments corrected** to state the
terminal-NOTICE semantics and point at [178] for the separately-proven retrain
control plane. The 2026-05-30 in-test drill DID fire `DriftTrigger`, but only
because it also injected calibration drift - the live battedball lane cannot.

## Action items

| #   | Item                                                                                                                                                               | Status                       |
| --- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ---------------------------- |
| 1   | GAP 1: cleanup mutation via one-shot CH-admin connection + 400 refusal when unarmed; grants template annotated; runbook arms/disarms the creds with the drill vars | **Fixed in this PR**         |
| 2   | GAP 2: terminal-NOTICE semantics corrected in the runbook + `DriftAlertEvaluator` / IT comments                                                                    | **Fixed in this PR**         |
| 3   | Pitch-family drill variant (feature PSI + real ECE lane, live-game window) if a calibration-lane rehearsal is ever wanted                                          | Open - post-season, optional |
| 4   | E-4: wire the Ops dashboard drift snapshot from fixture to live (`/v1/ops/drift`); the tagged drill rows are a ready-made live payload to render                   | Open - backlog               |
| 5   | Natural-event watch: [169]'s supersedes clause - if a confirmed real drift event fires in-season, write it up and reclassify this document as a drill report       | Standing                     |

## References

- Decisions: [175] (induced drill = primary path + tagging hygiene), [169]
  (backstops + supersedes clause), [178] (C-31 / retrain control plane), [179]
  (CH cap 6g), [171] (least-priv CH user), [168] (carry champion)
- PRs: #274 (CH cap), #275 (V027 tagging), #276 (injector), #277 (notice-days
  knob), plus this PR (GAP 1 + GAP 2 fixes + this document)
- Runbook: `docs/runbooks/e2-induced-drift-drill.md`
- Predecessor: `docs/postmortems/drill-2026-05-30-induced-battedball-drift.md`
  (in-test drill: proved the math + alert semantics, mocked every CH read)
- Companion: `docs/postmortems/2026-07-15_c31-retrain-saga.md` (the back half
  of the loop: trigger -> train -> register on real data, [178])
- ADRs: ADR-0006 (box-relayed evidence, Mac-authored artifacts), ADR-0013
  (scale-ready topology; the drift chain's idempotency inventory)
