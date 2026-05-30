> **Drill — not real production drift.** This is a controlled drift-induction
> drill (plan S3), run via `DriftInductionDrillIT` (`./gradlew test -PrunDrills
--tests "*DriftInductionDrillIT"`). The drift was injected deliberately to
> validate the detect → alert → retrain chain end-to-end and to exercise the
> postmortem process itself before the first real in-season event lands. The
> PSI/ECE values are real math on the injected data; the alert + trigger + queue
> are production code. Decision [82] + `docs/postmortems/README.md` bless drill
> postmortems as the thing that keeps the detector honest before the real one.

# Postmortem: induced calibration + feature drift on `battedball_outcome`

- **Date range**: 2026-05-30 (single-run drill)
- **Severity**: page (calibration) + notice (feature)
- **Affected model**: `battedball_outcome` v1 (CHAMPION)
- **Detected by**: `CalibrationJob` (ECE PAGE, threshold 0.10) + `PsiFeatureJob`
  (`launch_speed` PSI NOTICE, threshold 0.25), evaluated by `DriftAlertEvaluator`
- **Operator**: alex (drill)
- **Summary**: A controlled injection put a 1σ mean shift on `launch_speed` and a
  systematic over-confidence into `battedball_outcome`'s predictions. The detector
  measured PSI 0.912 and ECE 0.188 — both well over threshold and sustained 7
  days — fired a PAGE + a NOTICE, and `DriftTrigger` enqueued a `DRIFT`-typed
  retrain. The whole chain worked; the exercise is the receipt.

## Timeline (UTC)

| When                    | What                                                                   |
| ----------------------- | ---------------------------------------------------------------------- |
| 2026-05-30T22:09:55.618 | Drill start — inject 1σ `launch_speed` shift + over-confident outputs  |
| 2026-05-30T22:09:55.624 | `Psi.computeContinuous` → **PSI = 0.912** on `launch_speed`            |
| 2026-05-30T22:09:55.676 | `CalibrationJob` → **ECE = 0.188** (0.75 confidence, 58% accuracy)     |
| 2026-05-30T22:09:55.685 | `DriftAlertEvaluator` fired **2 alerts**: PAGE + NOTICE                |
| 2026-05-30T22:09:55.685 | Discord **PAGE**: "battedball_outcome calibration drifted"             |
| 2026-05-30T22:09:55.685 | Discord **NOTICE**: "battedball_outcome feature drift on launch_speed" |
| 2026-05-30T22:09:55.694 | `DriftTrigger` enqueued **1** `DRIFT` retrain                          |
| 2026-05-30T22:09:55.698 | Queue row `drift-2026-05-30-battedball_outcome` → status `QUEUED`      |

## What happened

The drill stood the champion (`battedball_outcome` v1) up against the real
detection pipeline and fed it deliberately-drifted data:

1. **Feature drift.** Reference `launch_speed` ~ N(0,1); observed shifted to
   N(1,1) — a clean 1σ mean shift, the kind a sensor recalibration or a juiced/
   dead-ball season would produce. `Psi.computeContinuous` (10 quantile bins)
   scored **0.912**, far past the 0.25 "significant" line.
2. **Calibration drift.** Predictions were emitted at 0.75 confidence for class 0
   while only ~58% were actually class 0 — systematic over-confidence.
   `CalibrationJob`'s real ECE math scored **0.188**, past the 0.10 PAGE line.
3. Both metrics were staged sustained over the alert windows (7 daily rows).
   `DriftAlertEvaluator.runOnce()` raised a **PAGE** on calibration (≥3 days over
   threshold) and a **NOTICE** on the feature PSI (≥7 days over threshold), with
   24h dedup armed.
4. `DriftTrigger.runOnce()` saw the 7-day sustained calibration drift, dedup-checked
   the queue, and enqueued a `TriggerType.DRIFT` retrain
   (`drift-2026-05-30-battedball_outcome`, `QUEUED`). Per rule 6, the retrain is
   queued but **promotion stays human-gated** — nothing reached users.

## Drift metrics that fired

| Metric                    | Value | Threshold          | Notes                   |
| ------------------------- | ----- | ------------------ | ----------------------- |
| `psi_launch_speed`        | 0.912 | 0.25 (significant) | 1σ mean shift, 7d       |
| `calibration_error` (ECE) | 0.188 | 0.10 (PAGE)        | 0.75 conf / 58% acc, 7d |

## Hypotheses + tests

(What a real investigation off this alert would walk through — included to
exercise the muscle; the drill's actual cause is the controlled injection.)

1. **Hypothesis A — upstream feature-pipeline change.** A `launch_speed` unit or
   scaling change between training + serving would shift the whole distribution.
   - **Test**: diff the serving `feature_pipeline_battedball.json` schema hash
     against the model's pinned hash (rule 7); compare raw Statcast units.
   - **Result (drill)**: N/A — injected, not a real pipeline change.
2. **Hypothesis B — genuine population shift.** A rule change (e.g. a juiced ball,
   ABS zone) genuinely moves `launch_speed` league-wide.
   - **Test**: check the feature distribution against the same window's raw
     Statcast; cross-reference MLB rule-change dates.
   - **Result (drill)**: N/A.
3. **Hypothesis C — calibrator staleness.** The 30 isotonic calibrators were fit
   on a window no longer representative, so confidence drifts even if features
   don't.
   - **Test**: re-fit calibrators on the recent window; compare ECE.
   - **Result (drill)**: this is the closest analogue to the injected
     over-confidence — a real version would land here.

## Root cause (5-Whys)

1. Why did the model drift? Because a controlled drill injected a 1σ feature
   shift + systematic over-confidence.
2. Why inject it? Because the detection → alert → retrain chain and the
   postmortem process needed to be proven **before** the first real in-season
   event, not during it.
3. Why prove it pre-season? Because an untested detector is indistinguishable from
   no detector — the first real drift is the worst time to discover a silent gap
   (the same logic as the restore/reboot drills, rule 8).
4. Why does that matter for this project? Because "operate through a season for a
   real drift postmortem" (decision [82]) is the project's whole reason to exist;
   a dead detection pipeline makes that story hollow.
5. **Root cause**: by design — the drill is the forcing function. The finding
   isn't a bug; it's the confirmation that inject → PSI/ECE → PAGE/NOTICE →
   `DRIFT` retrain works end-to-end with real math + real alerting.

## Resolution

No production change required — the drill behaved exactly as intended. The retrain
queue row was left for inspection; a real event would proceed to the human-gated
promotion review (`promote-model` skill), never auto-promoting (rule 6).

## What changed in the system

- [x] Code added: `DriftInductionDrillIT` (the reusable drift drill) — commit on
      this branch.
- [x] Test added (CI regression for the detection chain): the drill is `@Tag("drill")`,
      run on demand via `-PrunDrills`; `SyntheticDriftTest` + `DriftAlertEvaluatorIT`
      remain the per-PR guards.
- [ ] Alert tightened / loosened: no — thresholds (PSI 0.25, ECE 0.10) held.
- [ ] Decision logged: not needed (no decision changed).
- [ ] ADR raised: no.
- [x] Runbook: this postmortem + the drill are referenced from the README drift
      story.

## Lessons

The detection pipeline is real, not decorative: a 1σ feature shift and a 0.75/0.58
over-confidence both scored where the industry rules-of-thumb say they should
(PSI ≫ 0.25, ECE ≫ 0.10), and the alert + retrain wiring carried it all the way to
a queued `DRIFT` trigger without a human in the loop — exactly up to the
promotion gate, where a human is required (rule 6). That gate is the right place
for the chain to stop on its own.

Writing this postmortem against a drill (rather than waiting for a real event)
surfaced one real gap worth fixing before the season: `battedball_outcome` doesn't
yet have a committed `feature_pipeline_battedball.json`, so Hypothesis A's
schema-hash diff can't be run as written until Sprint 2's registration closes that
out. Better to find that now than mid-incident.

## References

- Drill: `backend/src/test/java/.../drift/DriftInductionDrillIT.java`
- Detection chain: `PsiFeatureJob`, `CalibrationJob`, `DriftAlertEvaluator`,
  `retraining/triggers/DriftTrigger`
- Synthetic-drift unit guards: `drift/jobs/SyntheticDriftTest.java`,
  `drift/alerting/DriftAlertEvaluatorIT.java`
- Related decisions: `[64]` (synthetic drift tests), `[78]` (alert thresholds),
  `[82]` (operate-for-a-postmortem), rule 6 (human-gated promotion)
- Template: `ops/runbooks/drift-postmortem-template.md`
