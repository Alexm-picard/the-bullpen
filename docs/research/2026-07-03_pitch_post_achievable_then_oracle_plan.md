# Implementation Plan: Achievable model first, Oracle model next

- **Author**: Applied AI Research Lead (Claude)
- **Date**: 2026-07-03
- **Companion to**: `2026-07-03_pitch_outcome_post_accuracy.md` (research + the four handoff plans)
- **Status**: APPROVED (2026-07-03) - Stage 2 retrodiction reframe accepted; 2024+ bat-tracking ingest funded. Feature-scope (new features via a v2 contract) operative. Not yet recorded in `docs/decisions.md`.
- **Baseline**: `pitch_outcome_post` v1, 2026 holdout top-1 **0.591** / top-2 **0.808**
- **Targets**: **Stage 1 (Achievable)** -> approach ~0.65 top-1 / ~0.85 top-2 (servable, leakage-clean). **Stage 2 (Oracle)** -> ceiling **0.79-0.81 top-1 / 0.95-0.96 top-2** (retrodiction-only, NOT a served predictor; revised up from ~0.74/~0.93 on 2026-07-07 after the bat-tracking headroom search - see the Stage-2 revision note).

---

## 0. The honesty constraint (read first - it shapes Stage 2)

The outcome-dominating latent is the **batter's swing decision**. Two facts follow:

1. **Achievable ceiling (~0.65 / ~0.85)** is the best a _predictor_ can do, because at prediction time the swing has not happened and no observable feature determines it (the realized trajectory only _predicts_ swing at ~83-85%).
2. **Oracle ceiling (~0.74 / ~0.93)** requires _knowing_ the swing. You cannot feed a served next-pitch predictor the realized swing (it is the future, and it partially reveals the label). `bat_speed` / `swing_length` are on the predictor's denylist for exactly this reason.

**Therefore the oracle is realized as RETRODICTION, not prediction**: a separate head that scores a _completed_ pitch using _observed_ swing kinematics. It is legitimate and non-circular (measured bat kinematics are real observations, not label-derived), it has direct project precedent (`V011__bbip_retrodicted_labels`, decision [163]), and it is never routed as the next-pitch predictor. Its value is (a) a portfolio/postmortem demonstration that the swing tax is real and irreducible _for a predictor_, (b) a truth/eval source to score the predictor against, and (c) an "explain this outcome" retrodictive UI surface.

**One architecture spans both.** A two-stage swing-gated model:

```
P(outcome) = P(gate) x P(outcome | gate)
   gate = swing vs no-swing
   Stage 1 (ACHIEVABLE, servable):   gate := predicted P(swing) from OBSERVABLE features
   Stage 2 (ORACLE, retrodictive):   gate := OBSERVED swing kinematics (bat-tracking)
```

The only thing that changes between the two is the gate's information source. That keeps the code, the ONNX composition, and the calibration path shared, and makes the swing tax a single swappable component you can measure directly.

**Leakage rules are PER-HEAD, and this is the load-bearing distinction:**

| Field                                                 | Prediction head (`pitch_outcome_post`)   | Retrodiction head (oracle)                                                                            |
| ----------------------------------------------------- | ---------------------------------------- | ----------------------------------------------------------------------------------------------------- |
| `bat_speed`, `swing_length`, swing path/contact       | **DENYLIST** (measures the future swing) | **ALLOWED** (the head is defined as post-swing)                                                       |
| realized swing flag (label-derived)                   | **DENYLIST** (partial label)             | ALLOWED as context, but prefer _observed_ kinematics over the label-derived flag to avoid circularity |
| everything else (physics, context, `pitch_zone`, ...) | per the Stage-1 contract                 | inherited                                                                                             |

---

## Stage 1 - The Achievable Model (servable, leakage-clean)

Goal: push the _served_ post head as close to the measured achievable ceiling as observable features allow, and measure where that ceiling actually is.

### WO-1: Calibration order-preservation diagnostic (free; gates everything)

- **Objective**: detect and recover any top-1/top-2 the current non-order-preserving per-class isotonic+renorm is silently discarding; standardize the serving stack on an order-preserving calibrator so downstream base-model gains pass through 1:1.
- **Why**: per-class one-vs-rest isotonic + L1 renorm can flip the argmax (established mechanism). Free to check, no retrain, no contract.
- **Inputs**: existing `artifacts/pitch_outcome_post/v1` (ONNX + `calibrator.json`), the 2026 holdout loader (`pitch.eval.backfill_accuracy`).
- **Outputs**: raw-booster-argmax vs calibrated-argmax agreement %, raw-vs-calibrated top-1/top-2 on the holdout; IF isotonic costs accuracy, a temperature-scaling swap (T fit on val-year, folded into the ONNX graph as `logits/T`), otherwise a recorded decision to keep isotonic.
- **Dependencies**: none. Run immediately.
- **Acceptance**: a numeric argmax-agreement + top-k delta reported; if TS adopted, top-1/top-2 >= isotonic AND >= raw, and ECE <= ~0.0025 / Brier <= ~0.103 held on the 4 CV folds + holdout.
- **Pitfalls**: a single global T may be too stiff to hold ECE across 5 differently-miscalibrated classes; if so, evaluate Dirichlet/vector scaling but gate on the measured top-k delta (they are not order-preserving either).
- **Test**: pure measurement by default; if TS is adopted and any guardrail regresses, revert to v1's `calibrator.json`.

### WO-2: Ceiling measurement (the oracle-as-yardstick)

- **Objective**: replace the analytic ceiling band (~0.65 / ~0.85) with a _measured_ number, so Stage 1's gate and the whole program's target are anchored to data, not a decomposition.
- **Why**: the current band is literature-plus-anchors arithmetic; a full-table ClickHouse scan crashed on the WSL2 memory gotcha and the local dev sample is class-stratified, so it cannot be measured locally without care. Measuring it decides how much prize even exists.
- **Inputs**: `default.features` (2015-2025, rule-13-clean), the LightGBM training envelope, the `label` column.
- **Outputs**: three read-only artifacts (never served):
  - **(a) Oracle estimator**: add a single swing/no-swing column derived from `label`, retrain LightGBM on <=2025, evaluate top-1/top-2 -> measured `C_oracle` (predicted ~0.74/0.93).
  - **(b) Bucket-Bayes lower bound on `C_achievable`**: discretize `x = (3-inch plate_x bin, 3-inch plate_z bin, count_balls, count_strikes, pitch_type)`, fit argmax-per-bucket on ODD `game_id`s, evaluate on EVEN `game_id`s (odd/even split removes the `E[max]` upward bias). Then add pitcher/batter TE deciles + `catcher_id` and re-measure -> how little identity moves the ceiling.
  - **(c) Full-feature `C_achievable`**: the calibrated swing-model composition of WO-4 doubles as this probe.
- **Dependencies**: none (parallel with WO-1). Rule-13-safe (no 2026).
- **Acceptance**: measured `C_oracle` and `C_achievable` reported with per-fold std; the gap `C_oracle - C_achievable` is the quantified swing tax.
- **Pitfalls**: run **chunked per-year** to avoid the fold-boundary OOM that already crashed the full scan. Do NOT commit any oracle artifact to the registry - it is analysis only.
- **Test**: sanity-check `C_achievable >= 0.591/0.808` (served floor) and `C_achievable <= C_oracle` (conditioning on more info cannot lower Bayes accuracy); if either fails, the estimator is buggy.

### WO-3: Feature enrichment (Direction A - the main achievable push)

- **Objective**: add the unused, leakage-safe, in-schema signals to the flat LightGBM; this is the only direction that imports _new information_ (not a re-expression of the swing-capped manifold).
- **Why**: the ceiling's remaining top-1 headroom lives almost entirely in new-information features (batter location-conditioned chase-rate, fatigue/familiarity), not in location/zone the booster already splits on.
- **Inputs / Outputs / Acceptance / Pitfalls**: exactly **Plan A** in the companion report (one v2 contract batching all features; `catcher_id` out-of-fold TE; chase-rate built strictly on the tier-3 `RANGE BETWEEN 28 PRECEDING AND 1 PRECEDING` template; contract denylist incl. `bat_speed`/`swing_length`; the 6 CI leakage-test extensions; ablation ladder; gate on 2026 holdout top-1/top-2 + per-class recall + ECE/Brier).
- **Dependencies**: WO-1 (so gains pass through the calibrator 1:1). Runs against the WO-2 measured ceiling as its target.
- **Acceptance**: holdout top-1 AND top-2 lift must **clear a bootstrapped fold-noise band** (not merely be positive), calibration held, no per-class recall collapse; register SHADOW, human-gated promotion.

### WO-4: Two-stage swing-gated model, PREDICTED gate (Direction B, re-purposed)

- **Objective**: make the swing latent an explicit, swappable component. Stage-1 gate = a calibrated P(swing) predicted from observable features; stage-2a = {ball, called_strike} | no-swing; stage-2b = {swinging_strike, foul, in_play} | swing. Soft-marginalize into a single composed ONNX graph.
- **Why**: for pure servable accuracy this is expected to ~tie feature-matched flat (WO-3) - but it is the **bridge to the oracle**: Stage 2 reuses this exact composition with the gate swapped from predicted to observed swing. It also doubles as the WO-2(c) full-feature ceiling probe. Fund it for the architecture, not for an expected accuracy win.
- **Inputs**: WO-3's feature set; the existing `hierarchical_model.py` template (pitch-TYPE, not drop-in); `onnxmltools` + a new `onnx.compose` graph.
- **Outputs**: a composed `[N,5]` ONNX graph (Gather/Mul/Concat in canonical label order) + one composed IsotonicCalibrator; a **lighter variant** to A/B against it: add the calibrated `P(swing)` as a single _feature_ into the flat WO-3 model (cheaper, no composition).
- **Dependencies**: WO-3 (feature-matched baseline + contract). Built WITH WO-3's features.
- **Acceptance**: A/B **against the WO-3 feature-matched flat** on the 2026 holdout (never v1, never a class-prior null); ship only on a positive top-1 delta clearing fold-noise with ECE/Brier held; composed ONNX parity <= 1e-4 (validate the float32 Concat node + canonical `[ball, called_strike, swinging_strike, foul, in_play]` ordering).
- **Pitfalls**: **never hard-route the gate** (soft-marginalize; hard routing caused -5 to -10pp regressions elsewhere). Nested dichotomies are underconfident (Leathart) - add per-node isotonic only if composed ECE regresses. Drop logit-adjustment and focal loss (macro-recall objective, calibration-harmful, mild imbalance).
- **Test**: composed-graph parity harness (new; no `onnx.compose` precedent in the repo); per-class recall table.

**Stage-1 exit**: served model as close to the measured `C_achievable` as the data allows, calibration at or better than v1, evidence pack in `experiment_results`, promotion human-gated.

---

## Stage 2 - The Oracle Model (retrodiction; ceiling 0.79-0.81 top-1 / 0.95-0.96 top-2)

Goal: recover as much of the swing tax as physically possible, realized honestly as a **retrodictive** head, never as a served predictor.

> **2026-07-07 revision - ceiling raised from ~0.74/~0.93 to 0.79-0.81 / 0.95-0.96, empirically verified** (workflow `wf_4d1596b3-853`: 5 web sweeps + 2 independent probe re-runs + synthesis). The original ~0.74 assumed the oracle knows only the binary swing flag and the swing branch is physics-only (~0.53-0.60). Both assumptions were wrong: the seven public bat-tracking fields (backfilled to April 2024, 94-99% populated on swings INCLUDING whiffs) lift the measured swing branch to **S = 0.62** (LightGBM probes, replicated on three temporal/cross-season holdouts: 0.616 May-2025, 0.623 June-2025, 0.616 cross-season 2024, with only 27k training swings and no batter priors), and the take branch measures **0.940** on 121k held-out 2025H2 takes (860k-take ablation). Arithmetic at measured base rates P(take)=0.525: measured-today floor `0.525x0.940 + 0.475x0.620 = 0.788`; central full-scale (S=0.63-0.66) **~0.80**; optimistic evidenced 0.808; S=0.68 is an unevidenced tail. Top-2 ~0.957. The binding wall is **foul vs in_play given contact = 0.67-0.68 everywhere** (the decisive bat-ball centerline offset is unpublished; intercept fields are batter-center-relative). Probe artifacts live in the session scratchpad (`verify_all.py`, `take_branch_ablation.py`, `pull_umps.py`); scratchpad-only, ADR-0006 respected, rule-13 clean.

### WO-5: Retrodictive oracle head using observed swing kinematics (spec REVISED 2026-07-07)

- **Objective**: build a separate registry head that scores a _completed_ pitch with observed bat-tracking, targeting the verified ceiling. Same two-stage architecture as WO-4, with the gate fed by observed swing kinematics rather than predicted P(swing).
- **Why**: this is the only honest way to realize oracle-grade accuracy. It quantifies and _demonstrates_ the swing tax, provides a truth/eval source for the predictor, and can back an "explain this outcome" retrodictive UI. It is the natural sibling of the batted-ball retrodiction (decision [163]). Per the published-models sweep, no public model reports top-1 above ~0.74 on this task - **a WO-5 at ~0.79-0.80 would be first-of-kind in public**.
- **Inputs**:
  - **NEW INGEST (MANDATORY, all seven fields - upgraded from "if pulled")**: `bat_speed`, `swing_length`, `attack_angle`, `attack_direction`, `swing_path_tilt`, `intercept_ball_minus_batter_pos_x_inches`, `intercept_ball_minus_batter_pos_y_inches`. All per-pitch in the same Savant CSV the project already pulls; the May-2025 swing-path metrics are **backfilled to April 2024** (verified 94-99% populated on swings including whiffs; 2023 is exactly 0% - hard boundary). Trainable span 2024-2025 (~650k swings); 2026 stays holdout. `intercept_y` and `attack_direction` carry the lift (#2/#3 by gain).
  - Take-branch features: **rulebook-geometry** (signed distance to zone edges from `plate_x/plate_z` + per-pitch `sz_top/sz_bot`, ball radius) + GBM on location + count, with a **per-era flag**. All already in the raw pulls - zero new ingest.
  - A **separate v-retro contract** whose denylist DIFFERS from the predictor's: the seven bat-tracking fields are ALLOWED here (per-head rule). Physics + context inherited from Stage 1.
- **Outputs**: a `pitch_outcome_retro` registry entry (NEW model_name - not a version of the predictor, so rule 9 is not tripped and the two heads never mix), ONNX + calibrator + contract + parity fixtures, clearly labeled RETRODICTION.
- **Dependencies**: WO-4 (shares the two-stage composition + calibration path); the bat-tracking ingest; **the full-scale 650k-swing training runs OFF-BOX** (thermal/Path-2 precedent).
- **Acceptance (pre-declared, rule 5)**: in-era (2025H2 temporal holdout): swing-branch top-1 >= 0.62, take-branch >= 0.94, overall >= 0.78 top-1 / >= 0.95 top-2, per-branch ECE/Brier reported. The 2026 holdout is reported SEPARATELY with the ABS tailwind flagged (see harmonization below). Gated as a _retrodiction_: the bar is calibration + the ceiling demonstration, not a served-prediction promotion.
- **2026 harmonization layer (NEW, mandatory)**: 2026 redefined `plate_x/plate_z` (front-of-plate -> middle-of-plate, ~-0.7in discontinuity), made `sz_top/sz_bot` ABS per-batter constants (within-batter std 0.079 -> 0.001 ft), and records FINAL post-challenge calls (~1.4% of takes snapped to the ABS zone). The eval harness must apply era-correct geometry or the trained model underperforms the free ABS floor (measured: 2024-25-trained models score 0.9443-0.9479 on 2026 takes vs the zero-model ABS rulebook floor of **0.9485**). If a shift-aware model cannot beat that floor, the 2026 take branch should simply BE the rulebook zone.
- **Missingness path**: NaN + `has_bt` indicator with physics-only fallback for the 3.5-5% untracked (partial/check) swings; no imputation, no competitive-swing row filter (the retrodictor must score every completed pitch). Measured haircut ~0.3pt; ablation confirmed the indicator does not leak the label (0.505 vs 0.504 physics-only).
- **v-retro denylist (tightened by verification)**: **`miss_distance` stays OUT by default** - it IS a per-pitch CSV column (col 93) but is populated ONLY on whiffs (99.2%/0/0), so its presence equals the swinging_strike label. Admitting the contact-occurrence bit yields an **"oracle+" variant at ~0.85 top-1** but is one bit short of the label - that is a separate explicit `/decide`, never a silent feature. `squared_up`/`blasts`/`swords` stay out (EV-derived). Launch data out.
- **Dropped from the accuracy path (measured dead)**: umpire ID (+0.07-0.13pp branch marginal; the 2026 ABS challenge era erases the learned umpire-deviation signal on the eval season) and catcher ID (~0 after location+count). Ingest umpire ID only if wanted for postmortem color (feasibility verified: MLB Stats API `boxscore.officials[]`, all 5,665 games 2024-2026 pulled in ~7 min, 0 missing, joins on `game_pk`).
- **Pitfalls**:
  - **Never route it as the predictor.** Physically un-wireable into the next-pitch path: no shadow/champion routing on `POST /v1/predict/pitch`, a distinct endpoint/label, and a registry guard that refuses to promote a retro head into a prediction slot.
  - **Circularity**: the gate uses _observed_ bat-tracking, not the label-derived swing flag.
  - Small trainable span (2024-2025, single-era) - report honestly; foul_tip -> foul label mapping must match the served head's mapping (record the choice).
- **Test**: isolation tests asserting no bat-tracking column can appear in the _predictor's_ `feature_pipeline_post.json`, `miss_distance` cannot appear in ANY contract without the oracle+ `/decide`, and no retro artifact can register into a prediction routing slot.

**Stage-2 exit**: a retrodictive head that demonstrates the achievable-vs-oracle gap on real data, with a written postmortem: "the predictor reaches ~0.63-0.67 achievable; the retrodictor reaches ~0.79-0.81; the gap is the swing tax, recoverable only post-swing - and the residual foul-vs-in_play wall (~0.67-0.68) is the public-data Bayes limit set by the unpublished bat-ball contact offset." That honest ceiling story is stronger hiring signal than any single accuracy number.

---

## Dependency graph and completion order

```
WO-1 (calibration diagnostic)  ─┐        [free, immediate]
WO-2 (ceiling measurement)     ─┼─> WO-3 (feature enrichment / Direction A)  ─> WO-4 (two-stage, predicted gate)
                                │        [Stage 1: ACHIEVABLE, servable]              │
                                │                                                     v
                                └───────────────────────────────────────────────> WO-5 (retrodictive oracle, observed gate)
                                                                                  [Stage 2: ORACLE, retrodiction]
                                                                                  + NEW bat-tracking ingest
```

1. **WO-1 + WO-2 in parallel** (both cheap, read-only, no contract). WO-2 tells you if the prize is real before you spend a contract PR.
2. **WO-3** (the real achievable push; the prerequisite feature-matched baseline).
3. **WO-4** (the swing-gate architecture; A/B vs WO-3; the bridge).
4. **WO-5** (the oracle, as retrodiction; needs the bat-tracking ingest, which can start in parallel with WO-3/4).

## Locked decisions (2026-07-03)

- **Stage 2 = retrodiction, ACCEPTED.** WO-5 ships as a separate `pitch_outcome_retro` head, never routed as the next-pitch predictor; oracle-grade accuracy is realized honestly as retrodiction, not prediction.
- **2024+ bat-tracking ingest, FUNDED.** WO-5 uses _observed_ swing kinematics (`bat_speed`, `swing_length`, and swing path / contact if pulled) via a new ingest + a separate v-retro contract with per-head leakage rules (bat-tracking allowed for the retrodictor, denylisted for the predictor). This supersedes the cheaper label-derived-swing fallback.
- **Feature scope**: new features via a v2 contract remain in play (operative assumption).

## Open housekeeping (not blocking)

- Record the retrodiction-vs-prediction split + per-head leakage rule in `docs/decisions.md` (and likely an ADR, since it is architecturally load-bearing) via the `lock-decision` flow.
- Commit this plan + the companion research report to a `docs/pitch-post-research` branch (pending go-ahead).
- Whether WO-1 + WO-2 ship as a single "measure and calibrate" work order (recommended - both are free/cheap, no dependencies, startable immediately).
