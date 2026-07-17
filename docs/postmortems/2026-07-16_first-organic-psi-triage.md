# Triage note: first organic feature-PSI NOTICEs (pitch_outcome_post, 2026-07-16)

- **Fired**: 2026-07-16 ~03:00 ET `DriftAlertEvaluator` cycle - feature-drift
  NOTICEs on `pitch_outcome_post`: `pfxZIn` PSI = 1.62, `spinRateRpm` PSI = 0.87
- **Observed window**: 24h, sample_size = 27 (All-Star break)
- **Context**: the E-2 drill's `feature-psi-notice-days=1` was still armed on the
  worker, so ONE over-threshold day fired these; at the prod default (7) neither
  would have fired (#277's `daily.size() < noticeDays` gate)
- **Verdict**: **small-sample false positive** - no evidence of population
  drift, and the suspected F2.1a derivation skew is ruled out by code + the
  box-validated derivation record. Two real findings fall out anyway (below).
- **Triage**: per `docs/runbooks/feature-drift-investigation.md`; authored
  Mac-side from code + math (box queries for confirmation are embedded)

## Why the values do not indicate drift (the load-bearing math)

`Psi.computeContinuous` bins against 10 equal-frequency deciles derived from the
champion's 5000-point training reference and floors empty proportions at
EPSILON = 1e-4. Consequences at n = 27:

- **Every observed-empty decile contributes ~0.69** to PSI
  ((1e-4 - 0.1) \* ln(1e-4 / 0.1)).
- **The no-drift noise floor is E[PSI] ~= (B-1)/n = 9/27 = 0.33** - already
  above the 0.25 NOTICE threshold before any drift exists. Below n ~= 36 the
  expected PSI of a perfectly-matched sample crosses the threshold on its own.
- Under the null (27 iid draws from the training distribution), expected empty
  deciles = 10 x 0.9^27 ~= 0.58 and P(>= 2 empty) ~= 12% per feature per day.
- **PSI 1.62 ~= two empty deciles + mild imbalance; 0.87 ~= one.** A physically
  clustered 27-pitch sample (a handful of pitchers' pitch mix) produces exactly
  this shape with zero population drift.
- Diagnostic contrast: a GROSS derivation/unit skew (e.g. the feet-vs-inches
  x12 trap) would put all 27 observations into one reference tail, giving PSI
  ~= 7-8 at these parameters - not 1.6. The observed magnitudes are the
  _small-sample_ signature, not the _skew_ signature.

## The F2.1a skew hypothesis: ruled out

The suspicion: live-derivation-vs-training skew on pfx would be a material
kinematics finding. Verified against code, it is not present:

- **Training side** (`training/src/bullpen_training/ingest/sql/transform_raw_to_pitches.sql:56-57`):
  `pfx_x AS pfx_x_in, pfx_z AS pfx_z_in` - a straight pass-through of
  Statcast's `pfx_x`/`pfx_z`, which are in **FEET**. No x12 anywhere. The
  champion trained on feet and its `metadata.json` baseline encodes feet.
- **Live side** (`backend/.../ingest/GumboKinematics.java`): derives pfx in
  feet from GUMBO's raw 9-parameter fit _specifically because_ GUMBO's own
  reported pfx is definitionally different (measured over the y=40 span). The
  formulas were box-validated against one full 2026 game, n=250 pitches matched
  pitch-for-pitch: pfx within the table's rounding + live-vs-postgame tracking
  refit (sd 0.065-0.09 **ft**). Feet flow into `LivePitch.pfxZIn` and the
  logged request - matching the training column exactly.
- **spinRateRpm** is a validated pass-through on both sides (Statcast
  `release_spin_rate` <-> GUMBO `breaks.spinRate`, RPM, same frame). No skew.

So live and training are consistent by construction; the derivation layer is
doing its job. See finding 2 for the naming hazard this uncovered.

## The n=27 source (attribution pending box query)

During the break the [177] ingest leg should be silent (no live games in the
window), which leaves: direct HTTP `POST /v1/predict/pitch?head=post` calls
(operator/testing traffic; `game_id` NULL), or box-side tooling. Nightly k6 and
schemathesis run against CI-local boots, not the box. Attribution query:

```sql
SELECT request_at, correlation_id, game_id,
       JSONExtractFloat(features, 'pfxZIn')     AS pfx_z,
       JSONExtractFloat(features, 'spinRateRpm') AS spin
FROM prediction_log
WHERE model_name = 'pitch_outcome_post'
  AND request_at BETWEEN '2026-07-15 03:00:00' AND '2026-07-16 03:00:00'
ORDER BY request_at;
```

If the 27 rows are hand-crafted test requests, their feature values are
arbitrary and the PSI values are doubly meaningless. `game_id IS NULL` on all
rows confirms the HTTP path; the value ranges vs the baseline (pfx feet:
roughly -1.5..+1.5) confirm or refute any residual scale concern in one glance.

## Findings

1. **No minimum-sample guard exists anywhere in the PSI chain** (the real gap
   this NOTICE exposes). `PsiFeatureJob` computes on any n >= 1 (only skips
   empty), `DriftAlertEvaluator` never reads `sampleSize`, and the
   feature-drift runbook's "sample_size < 1000 = noisy" row explicitly defers
   the guard to a "follow-up commit". The math above makes the threshold
   principled: the noise floor (B-1)/n crosses the 0.25 NOTICE line at n ~= 36,
   and PSI is not usefully stable until n is in the hundreds.
   **Recommendation**: an alert-side minimum-sample property
   (`bullpen.drift.alert.feature-psi-min-sample`, default ~300) in
   `DriftAlertEvaluator` - gate the NOTICE, keep writing the rows (visibility
   is unharmed; the ops grid still shows the values with their sample sizes).
2. **`_in`-suffixed Tier-4 columns store FEET** (`pfx_x_in`, `pfx_z_in`,
   `release_pos_*_in`, and plate coordinates) - the V009/V010 "suffix
   convention" was applied without a unit conversion, and Statcast's native
   feet passed through. Everything is CONSISTENT (training, baseline, live
   derivation, serving), so no code change is warranted - a rename would churn
   the schema-hash surface for zero numeric effect - but the misnomer is a trap
   for every future reader (this investigation initially chased it as a
   suspected x12 bug). **Recommendation**: unit comments at the three defining
   sites (transform SQL, V009/V010, `LivePitch`) + a glossary line.
3. **The drill's days=1 hair-trigger surfaced all of this** - working as
   intended, twice over: the drill proved the alert leg, and its sensitivity
   window caught a real gap (finding 1) in the alert design. Once the box
   disarms the drill env (runbook step 6), one-day blips like these stop firing
   at the default 7-day sustain.

## Action items

| #   | Item                                                                                                                                        | Status                                                    |
| --- | ------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------- |
| 1   | Box: attribute the 27 rows (query above); confirm `game_id` NULL + value ranges                                                             | Open (operator)                                           |
| 2   | Box: confirm the drill env (incl. `feature-psi-notice-days=1`) is disarmed per runbook step 6                                               | Open (operator; likely already done with drill close-out) |
| 3   | Alert-side minimum-sample property (finding 1) - small PR, java-reviewer                                                                    | Open (recommended next)                                   |
| 4   | Unit comments on the `_in`-means-feet columns (finding 2) - doc-only PR                                                                     | Open                                                      |
| 5   | Update the feature-drift runbook's false-positive row to cite this note's noise-floor math ((B-1)/n) instead of the bare "< 1000" heuristic | Open (fold into #3)                                       |

## References

- `docs/runbooks/feature-drift-investigation.md` (the triage script followed)
- `docs/postmortems/2026-07-16_induced-drift-drill.md` (the drill whose armed
  days=1 fired these; its action item #5 is the natural-drift watch)
- `Psi.java` (EPSILON/binning), `PsiFeatureJob.java` (no observed-side n gate),
  `DriftAlertEvaluator.java` (no sampleSize read), `GumboKinematics.java` (the
  validated derivation), `transform_raw_to_pitches.sql` (the feet pass-through)
- Decisions: [175] (drill primary path), [177] (post head's ingest-side logging)
