# Phase 2 — The Real Models · INDEX

> Three calibrated models with eval artifacts. The wrapper attaches in Phase 3.
> Weeks 8–17 · ~140–180 hours. See [`../../plan.md`](../../plan.md) §Phase 2.
>
> **Phase exit criterion**: Three models registered, all with eval artifacts, all served via Spring, all with passing leakage tests in CI. ECE < 0.02 on test data per model.
>
> **Soft cuts** (in priority order):
> 1. End of Wk 12 if behind: drop pitch post-pitch head, keep pre-pitch only (~20 h).
> 2. End of Wk 15 if 2c at risk: drop physics retrodiction, fall back to per-park naive subsets (~25 h, painful — model weakened, document honestly).
>
> **Hard rule (Discipline 4)**: NEVER cut the eval artifact. Models without eval are screenshots, not systems.

---

## Cross-cutting docs to read alongside any leaf in this phase

- [`../00-MASTER.md`](../00-MASTER.md)
- [`../00-CONVENTIONS.md`](../00-CONVENTIONS.md)
- [`../00-TESTING-STRATEGY.md`](../00-TESTING-STRATEGY.md) — leakage tests, parity tests
- [`../00-RISK-REGISTER.md`](../00-RISK-REGISTER.md) — G1, G3, G4 are blocking; G6, G7, G12 surface here
- [`../../design.md`](../../design.md) §4

---

## Sub-tree

This phase is large enough to warrant three sub-trees, one per model.

### Phase 2a — Pitch outcome (pre-pitch head) → [`2a-pitch-pre/`](2a-pitch-pre/)

Weeks 8–10. The first "real" model. Establishes the feature-pipeline plumbing, the leakage-test suite, and the rolling-origin CV harness that 2b/2c reuse.

Leaf plans:
- `2a.1-feature-pipeline-tier-1-2.md` — count, state, identity (target-encoded with strict pre-game cutoff)
- `2a.2-feature-pipeline-tier-3-form.md` — rolling form features via streaming temporal cutoff
- `2a.3-leakage-tests-ci.md` — 4 categories (future contamination, shuffled-target, calendar-date trace, ID consistency); CI-gate
- `2a.4-rolling-origin-cv-harness.md` — 4 folds 2015–2025, by-date within-fold split
- `2a.5-lightgbm-train-and-isotonic-calibrate.md` — 5-class multinomial; isotonic per class on temporal holdout
- `2a.6-logistic-regression-baseline.md` — registered as permanent reference (decision [37])
- `2a.7-eval-artifact-generator.md` — `metrics.json`, `reliability_diagrams.png`, `segment_metrics.csv`, `temporal_cv_results.csv`, `feature_importance.csv`
- `2a.8-onnx-export-and-spring-serve.md` — `/v1/predict/pitch` endpoint with `pre` head selected
- `2a.9-forward-simulator.md` — analytical (15×15 transition matrix) + Monte Carlo; convergence test

**Closes / addresses (Phase 2a, cumulative)**: G1 (feature parity end-to-end exercised), G3 (first model registered, schema-hash bootstrap path defined), G6 (TZ in feature cutoffs).

### Phase 2b — Pitch outcome (post-pitch head) → [`2b-pitch-post/`](2b-pitch-post/)

Weeks 11–12. Reuses 2a infrastructure; adds Tier 4 post-pitch features. Different `model_name` in the registry.

Leaf plans:
- `2b.1-tier-4-postpitch-features.md`
- `2b.2-train-and-eval-postpitch.md`
- `2b.3-register-and-serve-postpitch.md` — `/v1/predict/pitch` with `post` head selected

This is the **first soft-cut candidate**. If by end of Wk 12 we're behind, ship 2a only. Document in the Status Log.

### Phase 2c — Batted-ball with physics retrodiction → [`2c-batted-ball/`](2c-batted-ball/)

Weeks 13–17. The most complex sub-tree. The physics simulator must validate against 100 known Statcast trajectories *before* any training run begins (decision [49]).

Leaf plans:
- `2c.1-physics-simulator-implementation.md` — Nathan's drag/Magnus ODE, RK4
- `2c.2-physics-validation-100-trajectories.md` — gate before any training (Phase 2c blocker)
- `2c.3-park-geometry-data.md` — wall heights, distances, foul territory (sourcing + manual curation)
- `2c.4-retrodiction-labeling-pipeline.md` — 30 outcomes per BIP via simulator
- `2c.5-multi-output-mlp.md` — shared backbone + 30 per-park heads, ~50K params
- `2c.6-30-isotonic-calibrators.md`
- `2c.7-cross-park-sanity-tests.md` — monotonic park-HR-rate ordering for canonical inputs
- `2c.8-lightgbm-option-a-baseline.md` — park-as-categorical, single model, for comparison
- `2c.9-eval-artifact-mlp-vs-lgbm.md` — explicit head-to-head in the artifact

This is the **second soft-cut candidate**. If by end of Wk 15 the physics path is at risk, fall back to per-park naive subsets and document the loss honestly.

---

## Cross-cutting work that lands in Phase 2 (mention here, owned by sub-trees)

- **Three storage layers** (decision [84]) get exercised: `raw_statcast` (Phase 1.1) → `pitches` (Phase 1.2) → `features` (new in Phase 2a). The `features` table schema is finalized in 2a.1/2a.2.
- **Leakage tests** (decision [63]) are CI-gated from 2a.3 onwards. Do not merge to main in Phase 2+ if any leakage test is failing or skipped.
- **Eval artifact directory format** (decision [62]) is finalized in 2a.7 and reused for 2b and 2c.
- **Forward simulator** (decision [54]) lands as part of 2a.9 because it depends on the pitch model.

---

## Phase 2 exit gate

```bash
# Three models registered with eval artifacts:
ls /var/lib/thebullpen/models/pitch_outcome_pre/v1/
ls /var/lib/thebullpen/models/pitch_outcome_post/v1/    # OR cut by soft-cut #1
ls /var/lib/thebullpen/models/batted_ball/v1/

# Each has:
# - model.onnx
# - calibrator.json
# - metadata.json
# - feature_pipeline.json
# - training_data.parquet
# - eval/ directory with metrics.json showing ECE < 0.02

# Leakage tests pass in CI:
uv run pytest ml/tests/leakage/

# All three served:
curl POST /v1/predict/pitch  -d '<pre-pitch payload>'
curl POST /v1/predict/pitch  -d '<post-pitch payload>'   # OR cut
curl POST /v1/predict/batted-ball -d '<bbip payload>'
```

If all of the above pass: Phase 2 done. Move to Phase 3 (the wrapper).
