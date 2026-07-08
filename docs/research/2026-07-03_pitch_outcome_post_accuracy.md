# Research Handoff: Lifting `pitch_outcome_post` top-1 / top-2 holdout accuracy

- **Author**: Applied AI Research Lead (Claude)
- **Date**: 2026-07-03
- **Status**: DRAFT for lead adjudication -> three work orders
- **Target model**: `pitch_outcome_post` v1 (served champion-STAGE, UI-held)
- **Baseline (2026 rule-13 holdout, n=237,396)**: top-1 **0.591**, top-2 **0.808**; marginal 0.366 / 0.554; ECE ~0.0025, Brier ~0.103
- **Objective**: raise top-1 / top-2 on the 2026 holdout. Calibration (ECE / Brier) is a MUST-NOT-REGRESS guardrail, not the objective.
- **Method**: 6 parallel literature sweeps + 3 per-direction evaluations (workflow `wf_c38e4421-e45`), grounded in the repo.

> This document is uncommitted and lives in a new `docs/research/` folder. Move, rename, or delete freely. It is research evidence, not a locked decision; nothing here promotes a model (rule 6).

---

## 1. Executive Research Summary

**The single most important finding is a reframe, not a technique.** There is essentially **no published work that reports top-1/top-2 accuracy on a 5-class `{ball, called_strike, swinging_strike, foul, in_play}` label**. The field decomposes per-pitch outcome into sub-models (swing/take, called-strike-probability, whiff-given-swing) and evaluates them by **calibration / AUC / run value**, never top-1, because per-pitch outcome is treated as irreducibly stochastic once the swing decision and contact quality enter. Consequently:

- **`0.591` top-1 / `0.808` top-2 has no published peer to beat. The Bullpen's own holdout number IS the benchmark.** That is a genuine, defensible portfolio differentiator, and the honest ceiling analysis is itself a deliverable.
- The **outcome-dominating latent is the batter's swing decision, which is not a feature**. Published swing/take models top out at **~82% top-1** from location + count + pitch type, features the post head already has. That entropy is a hard cap on any flat 5-class model. A large chunk of the residual error is **irreducible by any architecture or feature set**.

**Quantified ceiling (adversarial analytic decomposition; verify empirically per Section 9 Task 0).** Decompose by the swing latent (no-swing `{ball .366, called_strike .15}` = 51.7%; swing `{swinging_strike .10, foul .17, in_play .213}` = 48.3%): an **oracle** handed the realized swing flag tops out at **~0.74 top-1 / ~0.93 top-2** (take-branch ball-vs-called_strike ~0.91 location-separable; swing-branch whiff/foul/in_play only ~0.53-0.60 Bayes, whiff ROC ~0.78). But no post-head feature OBSERVES swing intent - the realized trajectory only PREDICTS it (~83-85%), so the **achievable (swing-marginalized) ceiling is ~0.63-0.67 top-1 (point ~0.65) and ~0.83-0.87 top-2 (point ~0.85)**. The served model at 0.591/0.808 already sits at **~91% of the achievable top-1 ceiling and ~95% of the achievable top-2 ceiling**. The whole program (all feature + architecture work combined) is capped at roughly **+4 to +7pp top-1 / +4pp top-2**; the ~9-11pp oracle-minus-achievable gap is an irreducible swing-latent tax **no direction can recover**. The ceiling caps B and C HARDER than A: they import no new columns, only re-express the swing-capped manifold, so the remaining headroom lives almost entirely in A's new-information features (batter chase-rate, fatigue/familiarity). CAVEAT: this is an analytic band, not a live measurement - the ClickHouse full-table scan crashed on the WSL2 memory gotcha and the local dev sample is class-stratified to uniform 0.2/class; Task 0 turns the band into a number.

**Therefore the realistic prize is a modest, honest, defensible ~+1pp, not a step-change** - and the biggest risk is trading away the excellent calibration to chase points that may not exist. Because only ~+4-7pp of top-1 headroom exists at all and A targets ~+1pp, **raise the promotion bar: the 2026-holdout delta must clear a bootstrapped fold-noise band (per-fold std + CI on top-1/top-2), not merely be positive.**

**Second finding (free, zero-cost, highest ROI of anything here):** the current serving pipeline (raw LightGBM probs -> per-class one-vs-rest isotonic -> L1 renormalize, in Java, outside the ONNX graph) is **provably NOT order-preserving**. Per-class isotonic + renormalization can move the argmax and may be **silently depressing top-1/top-2 right now**. A zero-cost diagnostic on the existing 2026 holdout artifacts measures this; if isotonic is costing accuracy, switching to **temperature scaling** (order-preserving for all k, ONNX-foldable) recovers it with **no retrain and no contract change**. This is **Phase 0** and gates everything.

**Direction ranking (all five literature sweeps independently reached the same ordering):**

| Rank  | Direction                                    | Expected top-1 lift                         | Confidence                                  | Cost / risk                      | Verdict                          |
| ----- | -------------------------------------------- | ------------------------------------------- | ------------------------------------------- | -------------------------------- | -------------------------------- |
| **0** | Calibration diagnostic + temperature scaling | 0 to ~+1pp (recovery, not new)              | mechanism established, magnitude unmeasured | trivial, no retrain, no contract | **DO FIRST**                     |
| **A** | Feature enrichment into flat LightGBM        | +1.0 to +1.5pp (range 0 to +2.5)            | promising                                   | low-moderate, CPU-only           | **PROCEED (primary)**            |
| **B** | Hierarchical swing-gate decomposition        | ~0 over feature-matched flat (-0.3 to +0.5) | speculative                                 | cheapest, CPU-only               | **CHALLENGER (portfolio value)** |
| **C** | Sequence transformer + entity embeddings     | 0 to +0.5pp over A (likely wash)            | speculative                                 | highest, off-box GPU required    | **LATER R&D only**               |

**Recommended funding order:** Phase 0 (free) -> A (real, prerequisite) -> B as a cheap honest challenger against feature-matched flat -> C only if A/B plateau below target and portfolio signal justifies the cost.

---

## 2. Literature Review

Six sweeps, ~40 sources. Findings are labelled **[E]** empirical / **[P]** promising / **[S]** speculative.

### 2.1 Tabular model families at scale (25M rows)

- **[E]** Well-tuned GBDT and modern deep-tabular are close; the family gap is usually small and light GBDT **tuning matters more than family choice** (McElfresh et al., NeurIPS 2023, 19 algos x 176 datasets). The current v1 is already a tuned LightGBM, so it sits near the achievable frontier.
- **[E]** Trees win via three inductive biases DL lacks: fitting irregular/non-smooth targets, robustness to uninformative features, rotation non-invariance (preserving individually-meaningful columns) (Grinsztajn et al., NeurIPS 2022). **All three match this feature set.**
- **[E]** Where DL wins it is small and expensive: TabM (ICLR 2025) beats XGBoost by **~1% RMSE at 20-54x training cost** on the only genuinely large datasets (6.5-13M rows); the retrieval model **TabR OOMs at 6.5M**. TabPFN-v2 / TabR need the training corpus resident at inference -> **break ONNX-in-process-Java and OOM at 25M**. Rule them out on serving grounds.
- **[E]** If deep is ever tried, use **MLP-family** (RealMLP with categorical embeddings; TabM). RealMLP+GBDT **ensembles beat either alone** (Holzmuller et al., NeurIPS 2024) and export cleanly to ONNX.

### 2.2 Baseball pitch-outcome modeling (the decisive domain sweep)

- **[E]** No published 5-class top-1. The field decomposes: **swing/take ~82% top-1** (logistic on location+count+type, AUC 0.899; arXiv 2511.19672; Wilson TDS ~80.5%); **take -> ball/called_strike ~90%** location-dominated (Northwestern RF 0.911 vs 0.669 majority); **swing -> {whiff, foul, in_play} is HARD, whiff-given-swing ROC only ~0.78** (FanGraphs xWhiff).
- **[E]** The SOTA academic per-pitch outcome model (arXiv 2110.04321: player embeddings + conv over sequence + 4-class softmax, 147,799 test pitches) is reported **only via calibration, never top-1**.
- **[E]** Industry `Stuff+/Location+/Pitching+` (FanGraphs/Driveline) predict **run value**, not a class; evaluated by predictiveness/stability, not accuracy.
- **[E]** Called-strike geometry is dominated by `(plate_x, plate_z)` entrance point; pitch type/velocity add little. **Catcher framing (CSAA) is real but marginal** (shadow-zone, fractions of a strike), and helps calibration more than top-1.

### 2.3 Sequence modeling

- **[E/P]** Sequence work targets next-pitch-**TYPE**, not outcome; reported type accuracy is modest (LSTM-attn 46-48%, the repo's own transformer ~45.4%). **No study cleanly shows sequence context raises OUTCOME accuracy over a strong tabular+lag baseline.** The famous transformer "+40pp" (Kneita) is measured against a class-prior sampler, not a booster.
- **[E]** Pitch tunneling is real, but its predictive content lives in **pairwise deltas from the previous pitch** (velo diff, location/release proximity, same-vs-different type) - i.e. **flat lag features, not a sequence encoder**.

### 2.4 Entity embeddings vs target encoding

- **[E]** On-domain and damning: `(batter|pitcher)2vec` (MIT Sloan 2018) gave only **~0.94% cross-entropy over naive and TIED plain logistic regression** on unseen at-bat outcomes. Identity barely moves outcome accuracy because the dominant latent is unobserved.
- **[E]** Regularized target encoding is a very strong baseline for high-cardinality categoricals (Pargent 2022; Matteucci 2024); **NN entity embeddings were among the worst** in one comparison. CatBoost **ordered target statistics** remove target-encoding leakage natively and export to ONNX.
- **[E]** Cold-start favors TE decisively: rare/new entities back off to the global prior automatically; embeddings need a bespoke OOV path reproduced in Java. TE degrades below ~100 samples/level (rookie regime is hard for both).

### 2.5 Hierarchical / imbalance

- **[E]** `P(y) = P(gate) x P(y|gate)` is a reparametrization of flat softmax; any gain is **finite-sample/inductive-bias only**. Flat is favored exactly in this regime (balanced-ish, abundant per-class data, strong learner) - **unless** the tree matches the true generative structure with **noise-free gate labels**, which is this task's one genuine argument for hierarchy.
- **[E]** **Never hard-route the gate** (caused -5 to -10pp regressions in cascaded classifiers). Soft-marginalize. Nested dichotomies are **underconfident**; calibrate per-node AND the composed output (Leathart, PAKDD 2019).
- **[E]** Imbalance losses (focal, class-balanced, logit adjustment) optimize **macro/balanced recall, not overall top-1**, and their gains scale with severity; at this task's **mild ~4:1** they give **<=5% F1 and inconsistent** results on tabular GBDT. **Rebalancing degrades calibration** across model families (Van den Goorbergh 2022; Carriero 2025). **Do not resample/reweight.**

### 2.6 Calibration and accuracy

- **[E]** **Temperature scaling is accuracy-preserving for all k** (single scalar, monotone, never moves argmax; Guo 2017). **Per-class one-vs-rest isotonic + renormalize is NOT** (introduces ties, renorm can flip argmax; scikit-learn docs; NA-FIR/SCIR ICML 2025).
- **[E]** Vector/Dirichlet scaling are more flexible and ONNX-exportable but **not order-preserving** - gate on measured top-k delta. **Label smoothing** is deprioritized (needs a custom LightGBM objective, blunts top-2 margins, calibration already excellent).

---

## 3. State-of-the-Art Comparison

| Family / method                    | Best evidence at our scale                     | Top-1 fit to our task | ONNX / Java serve               | Inference        | Recommendation                 |
| ---------------------------------- | ---------------------------------------------- | --------------------- | ------------------------------- | ---------------- | ------------------------------ |
| **Tuned LightGBM (current)**       | near-frontier; tuning > family                 | strong                | yes (today)                     | sub-ms           | keep as spine                  |
| CatBoost (ordered cats)            | ties GBDT; native high-card cats, leakage-safe | strong                | yes (native ONNX)               | sub-ms           | orthogonal identity experiment |
| MLP + embeddings (RealMLP)         | competitive <=500k; ensembles help             | promising             | yes                             | ~1-5ms           | shadow ablation only           |
| TabM (MLP ensemble)                | ~1% RMSE at 20-54x cost (6.5-13M)              | weak/costly           | yes                             | ~ms              | not worth it                   |
| TabR (retrieval)                   | OOM at 6.5M                                    | disqualified          | **no** (corpus at inference)    | n/a              | rule out                       |
| TabPFN-v2 (foundation)             | corpus at inference                            | disqualified          | **no**                          | n/a              | rule out                       |
| Transformer (sequence)             | type ~45-48%; outcome unproven vs booster      | weak for top-1        | yes (encoder) + big parity cost | ~1-5ms + DB join | R&D / portfolio                |
| Entity embeddings                  | ~0.94% on at-bat outcome; tied LR              | weak for top-1        | yes (gather)                    | negligible       | low-EV                         |
| Temperature scaling                | order-preserving                               | recovers accuracy     | yes (foldable)                  | negligible       | **Phase 0**                    |
| Isotonic per-class (current)       | can flip argmax                                | risk to top-1         | yes (Java, today)               | negligible       | measure vs raw                 |
| Focal / class-balanced / logit-adj | macro-recall, mild-imbalance small             | misaligned            | n/a                             | n/a              | drop                           |

---

## 4. Recommended Research Direction

**Primary: Direction A (feature enrichment into the flat LightGBM), preceded by the Phase 0 calibration diagnostic.** Rationale:

1. Every literature sweep independently concludes the biggest available top-1 lever on a latent-limited tabular task is **observable features into the existing well-tuned GBDT**, not a new architecture.
2. A is the **lowest hardware risk** (CPU-only LightGBM; avoids the thermally-fragile GPU path the box has crashed on), **best constraint fit** (reuses the exact isotonic-outside-graph ONNX serving pattern), and **prerequisite work**: it produces the feature-upgraded flat baseline that any B/C challenger must actually beat.
3. Phase 0 is free, needs no retrain or contract change, and may recover accuracy the calibrator is currently discarding - and it standardizes the serving stack so future base-model gains pass through to the served top-k 1:1.

**B (hierarchical) is funded as a cheap, honest challenger** with strong portfolio/postmortem value (a causally-structured, soft-marginalized, single-ONNX-composed decomposition that demonstrates - honestly - whether structure beats a flat GBDT here). It is not expected to beat feature-matched flat on accuracy.

**C (sequence/embeddings) is deferred** to later R&D; it is the lowest-EV, highest-cost, highest-parity-risk option and every honest data point predicts a top-1 wash. Its justification is resume signal, not accuracy.

---

## 5-8. Engineering / Architecture / Data / Training Specification (per direction)

Full per-direction blueprints are in the **three handoff plans (Section 12)**. Cross-cutting specifications:

**Data pipeline (shared).** The unused signals are in-schema on ClickHouse `pitches` (V013), but **NOT yet on the `features` table** `train_post` reads (V005/V010/V006 do not carry them). Every feature-bearing direction needs a **one-time feature-materialization pass** to (a) propagate direct columns and (b) build leakage-safe rolling/lag features under **streaming temporal cutoff** (`ts <= row.game_event_ts`, current pitch excluded for rolling rates). Build **per-year-chunked** (documented fold-boundary OOM footgun). 2026 stays strictly holdout.

**Leakage classification (load-bearing; adversarially verified by the `leakage` skeptic - verdict: PROCEED WITH MANDATORY GUARDS, not fatal):**

| Feature                                                                                            | Class                                                                                                                            | Note                                                                                                                                                                                                                                                                                                                                                 |
| -------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `pitch_zone`                                                                                       | SAFE (post-head)                                                                                                                 | geometry of `plate_x/z` vs `sz_top/bot`; low back-annotation risk                                                                                                                                                                                                                                                                                    |
| `catcher_id` (raw ID)                                                                              | SAFE (pre-pitch)                                                                                                                 | framing; the leak surface is its TE, not the ID (see chase-rate row)                                                                                                                                                                                                                                                                                 |
| `times_through_order`, `times_faced_today`, `at_bat_number_in_game`                                | PRE-PITCH-ONLY                                                                                                                   | safe **iff** as-of-PA-start; must be CONSTANT across all pitches of a `(game_id, at_bat_index)`                                                                                                                                                                                                                                                      |
| `effective_speed_mph`, `release_extension_ft`                                                      | SAFE (post-head)                                                                                                                 | release/velocity geometry fixed the instant the ball leaves the hand                                                                                                                                                                                                                                                                                 |
| `arm_angle_deg`                                                                                    | SAFE **iff per-pitch snapshot** - VERIFY                                                                                         | Baseball Savant publishes arm angle as a pitcher-SEASON average; if V013 ingested that aggregate it embeds FUTURE games = temporal leakage. Mostly NULL pre-2024. Spot-check two same-day pitches differ + a value does not change when later-season rows are added                                                                                  |
| `score_diff_live`                                                                                  | PRE-PITCH-ONLY (already-guarded)                                                                                                 | sourced from `raw_statcast.bat_score_diff`; `test_sql_path_contamination` already guards it                                                                                                                                                                                                                                                          |
| `win_expectancy`                                                                                   | PRE-PITCH-ONLY - VERIFY source                                                                                                   | safe ONLY if the ingested value is the PRE-pitch snapshot; a `post_*`/`delta_*` WE is catastrophic. Follow the `score_diff_live` discipline                                                                                                                                                                                                          |
| `if_alignment`, `of_alignment`                                                                     | PRE-PITCH-ONLY                                                                                                                   | defensive choice set before the pitch                                                                                                                                                                                                                                                                                                                |
| prev-pitch lag (`prev_pitch_type`, `prev_outcome`, `prev_plate_x/z`)                               | SAFE **iff** strict backward shift partitioned by `(game_id, at_bat_index)`, NULL at `pitch_number==1`, no borrow across PA/game | leakage-prone; must clear CI suite                                                                                                                                                                                                                                                                                                                   |
| location-conditioned batter chase rate                                                             | HIGH-RISK; SAFE **iff** tier-3 frame semantics                                                                                   | **highest-value AND highest-leakage feature**: a rolling aggregate of the swing latent (= the target). Build ONLY as a ClickHouse window `RANGE BETWEEN N PRECEDING AND 1 PRECEDING` (excludes current row AND current `game_date`), swing/chase derived from PRIOR pitches only. An off-by-one row/day silently inflates the holdout promotion gate |
| **`wpa_delta`, `delta_home_win_exp`, `delta_run_exp`, `post_*_win_exp`**                           | **LEAKAGE - CONTRACT DENYLIST**                                                                                                  | change in win/run expectancy CAUSED by the pitch = a deterministic function of the outcome class (effectively one-hot-encodes the label)                                                                                                                                                                                                             |
| **`bat_speed`, `swing_length` (2024+ Statcast bat tracking)**                                      | **LEAKAGE - MOST CATASTROPHIC**                                                                                                  | directly MEASURE the swing - they collapse the dominant latent the post head exists to predict. Never in `feature_order`                                                                                                                                                                                                                             |
| **`estimated_woba`/`xwoba*`, `launch_speed/angle`, `hc_x/y`, `hit_distance`, `bb_type`, `events`** | **LEAKAGE - DENYLIST**                                                                                                           | batted-ball / contact-quality outcome fields; exist only if `in_play` and reveal the outcome                                                                                                                                                                                                                                                         |

**Training (shared).** Reuse the v1 envelope: LightGBM multiclass, `learning_rate 0.05, num_leaves 63, min_data_in_leaf 200, feature_fraction 0.8, bagging_fraction 0.8, bagging_freq 5, seed 42, deterministic=true, force_row_wise=true`, `num_boost_round <=2000`, `early_stopping 50`. Same 4-fold rolling-origin CV (train >=2015 / val / test in {2022,2023,2024,2025}). Refit `IsotonicCalibrator` on the fold val-year. **No `random_state` on any split.** Do NOT pass `categorical_feature` (the v1 ordinal treatment is deliberate; explicit categorical pathway is 3-4x slower with no Brier gain).

---

## 9. Evaluation Plan

Every candidate reports, on **both** the 4 CV folds and the 2026 holdout (n=237,396):

1. **top-1 and top-2 accuracy** vs served v1.
2. **Per-class recall table** (all 5 classes) - catches a top-1 uptick that merely collapses `foul <-> in_play` or masks a `swinging_strike` recall drop.
3. **ECE and multiclass Brier** (must-not-regress guardrail).
4. **Raw-booster-argmax vs calibrated-argmax agreement** (the Phase 0 diagnostic; run for every calibrator change).

**Benchmark discipline (critical):** B and C are gated against the **feature-matched flat baseline** (Direction A output) on the 2026 holdout, NEVER against v1 alone and emphatically NEVER against a class-prior/marginal baseline. The published transformer "wins" are illusions over trivial nulls; do not reproduce that trap.

**Ceiling estimate (Task 0 of the program; read-only, rule-13-safe since `default.features` has no 2026; run chunked per-year to avoid the ClickHouse WSL2 OOM that already crashed a full-table scan).** Three probes, cheapest first:

- **(a) Oracle estimator** - add one swing/no-swing column derived from the label, retrain LightGBM on <=2025, evaluate top-1/top-2. Measures `C_oracle` (~0.74/0.93 predicted) directly. It is an analysis artifact, never served (no contract/parity/promotion machinery).
- **(b) Bucket-Bayes lower bound on `C_achievable`** - discretize `x` into `g = (3-inch plate_x bin, 3-inch plate_z bin, count_balls, count_strikes, pitch_type)`, fit argmax-per-bucket on ODD `game_id`s, evaluate on EVEN `game_id`s (the odd/even split removes the finite-sample `E[max]` upward bias). Then add pitcher/batter TE deciles + `catcher_id` to `g` and re-measure - the increment quantifies exactly how little identity moves the ceiling.
- **(c) Full-feature `C_achievable`** - train a calibrated swing model `q_hat(x)` + two conditional heads, compose `P_hat = q_hat*h_swing + (1-q_hat)*h_take`, report `E[max]`. Note this IS Direction B, so **B doubles as the ceiling probe even when it is not an accuracy win.**

Anchor the program's target and the README/postmortem framing to the measured `C_achievable`, not to a hoped-for number.

**Validation / CI leakage-test extensions (must land in the SAME v2 contract PR as the features - the current 4-test suite only guards the columns it explicitly enumerates, so it is vacuous for exactly the new features that matter most):**

1. **Contract denylist fence** (extend `test_pitch_head_pre_post_boundary.py`): a hard denylist asserted at contract-hash time so a leaky column can never even register - `{wpa_delta, delta_home_win_exp, delta_run_exp, post_*_win_exp, estimated_woba*/xwoba*, launch_speed, launch_angle, hit_distance*, bat_speed, swing_length}`.
2. **Future contamination** (`test_no_future_contamination.py`): add the chase-rate + `catcher_te` columns to the guarded set; mutate future `pitch_zone` / out-of-zone-swing SOURCE rows (not just labels) and assert an earlier row's chase rate is byte-identical; add an `arm_angle_deg` canary (append future-dated pitches, assert an earlier pitch's value is unchanged - catches a season-aggregate implementation).
3. **Shuffled target** (`test_shuffled_target.py`): shuffle the label BEFORE chase-rate + `catcher_te` computation; add `catcher_id` to the TE-collapses-to-prior test; assert the swing/chase flag is recomputed from the SHUFFLED description.
4. **Calendar-date trace** (`test_calendar_date_trace.py`): hand-recompute chase rate from strictly-earlier pitches (exclude current row AND current `game_date`); add a `catcher_te` trace; assert `win_expectancy` is the PRE-pitch snapshot (no `post_*`/`delta_*` source); trace `times_through_order/times_faced_today/at_bat_number` against qualifying PRIOR PAs only.
5. **ID consistency** (`test_id_consistency.py`): `catcher_te` constant within `(catcher_id, fold)`; within-PA invariants for the three familiarity counters; `prev_pitch_*` NULL at `pitch_number==1` and equal to the actually-preceding pitch (off-by-one catch), never across boundaries; chase rate invariant to raw row order.
6. **SQL-path mirror** (`test_sql_path_contamination.py`, Testcontainers): mirror tests 2-5 for chase-rate/`catcher_te` since the real build runs in ClickHouse, not the in-mem fixture. Note `target_encoding.py` has NO in-function temporal guard ("caller is responsible for the temporal cutoff"), so `catcher_id` TE inherits the same caller-responsibility leak surface as pitcher/batter TE.

---

## 10. Implementation Roadmap

```
Phase 0  Calibration diagnostic (no retrain, no contract)          ~0.5-1 day
   |     -> raw vs calibrated argmax on 2026 holdout; if isotonic costs
   |        accuracy, fold in temperature scaling. Standardize serving.
   v
Task 0   Oracle ceiling estimate (swing-flag injection)            ~0.5 day
   |
   v
Direction A  ONE v2 contract (all features) -> Java transform +    ~2-3 wk
   |         Python parity fixture land FIRST -> materialize
   |         (leakage-safe, per-year-chunked) -> CI leakage suite ->
   |         train 4 folds -> refit isotonic -> export -> 2026 holdout
   |         -> kill criteria -> SHADOW register (human-gated promote)
   v
Direction B  Hierarchical challenger, built WITH A's features ->    ~1-2 wk
   |         single composed ONNX -> A/B vs flat+features on holdout
   |         (expect ~tie; ship only on positive top-1 + held calibration)
   v
Direction C  Deferred R&D: frozen-encoder -> LightGBM hybrid,       ~3-5 wk (off-box)
             off-box GPU, gated hard vs flat+features. Portfolio, not accuracy.
```

---

## 11. Developer Task Breakdown (summary)

Detailed acceptance criteria are inside each Section-12 plan. High-level, per direction:

- **T-common-1**: Regenerate `contracts/feature_pipeline_post.json` (new `schema_hash`) batching all A features; extend Java `FeaturePipelinePitchPost.transform`; regenerate Python `parity_fixture_post._preprocess` + expected file; **parity < 1e-6 green before any retrain** (rules 2/7).
- **T-common-2**: Feature materialization job (INSERT...SELECT + rolling/lag under streaming cutoff, per-year-chunked); **full CI leakage suite green** (future-contamination, shuffled-target, calendar-date-trace, id-consistency, sql-path, holdout-fence).
- **T-A**: retrain flat LightGBM over 4 folds, refit isotonic, export ONNX (existing `export_post_onnx.py`), score 2026 holdout, apply kill criteria, register SHADOW.
- **T-B**: 3-booster train path + soft marginalization + single composed ONNX graph (`onnx.compose.merge_models`, Gather/Mul/Concat in canonical label order) + composed parity fixture (1e-4) + composed isotonic; A/B vs flat+features.
- **T-C** (deferred): encoder ONNX export + Java sequence-assembler with bit-exact token parity + hybrid meta-booster + cold-start OOV path; off-box GPU training.

---

## 12. The Three Work-Order Plans (handoff template)

### Plan 0 (pre-work, gates all three): Calibration order-preservation diagnostic

```
## Research Plan: Phase-0 calibration diagnostic + temperature-scaling recovery

### Hypothesis
The served pipeline applies per-class one-vs-rest isotonic + L1 renormalize
outside the ONNX graph. This transform is provably NOT order-preserving
(scikit-learn; NA-FIR/SCIR ICML 2025), so it can move the argmax and may be
silently depressing the served 0.591/0.808 vs the raw booster. Temperature
scaling is order-preserving for all k and would recover any lost accuracy at
zero retrain cost. Gap closed: free top-1/top-2 currently discarded by the
calibrator (if any).

### Proposed changes
- NO retrain, NO contract change, NO new features. Uses existing v1 artifacts.
- Script (new, training/.../pitch/eval/): on the 2026 holdout, compute
  raw-booster argmax vs calibrated argmax agreement, and raw-vs-calibrated
  top-1 / top-2 deltas, using PostHeadOnnxPredictor's raw ONNX output and the
  served IsotonicCalibrator.
- If isotonic net-lowers top-1/top-2: fit a single temperature T on the fold
  val-year, verify ECE <= ~0.0025 / Brier <= ~0.103 holds; fold T into the
  ONNX graph (logits / T before softmax) and drop the outside-graph isotonic.
  If one T is too stiff on ECE, evaluate Dirichlet/vector scaling but gate on
  measured top-k delta (they are not order-preserving either).
- Why it targets the holdout gap: recovers accuracy the current serving layer
  may be throwing away, independent of the base model.

### Evidence required
- Argmax agreement %; raw vs calibrated top-1/top-2 on 2026 holdout.
- If TS adopted: TS vs isotonic ECE/Brier on the 4 CV folds + holdout; proof
  top-1/top-2 >= isotonic and >= raw; per-class recall unchanged.
- Artifacts: (only if adopting) updated ONNX with folded temperature +
  metadata note; otherwise a decision record that isotonic is retained.

### Rollback plan
Pure measurement by default; if TS is adopted and any guardrail regresses,
revert to the current isotonic calibrator.json (unchanged v1 serving path).
```

### Plan A (PRIMARY): Feature enrichment into the flat LightGBM

```
## Research Plan: pitch_outcome_post v2a - in-schema feature enrichment

### Hypothesis
v1 is blind to the swing latent except through unconditional identity/rolling
rates, and carries no framing, fatigue/familiarity, tunneling, or
location-conditioned chase signal. The literature is unanimous that observable
features into the existing tuned GBDT are the biggest top-1 lever on a
latent-limited task. Expected: +1.0 to +1.5pp top-1 (range 0 to +2.5),
+0.5 to +1.5pp top-2. Gap closed: sharpen the ~82%-predictable swing decision
and the framing-dependent ball/called_strike boundary with cheap in-schema
observables. Honest framing: a nudge, not a step-change.

### Proposed changes (batched into ONE v2 contract)
- Direct in-schema (leakage-safe): pitch_zone; times_through_order;
  times_faced_today; at_bat_number_in_game; effective_speed_mph;
  release_extension_ft; arm_angle_deg; win_expectancy; score_diff_live.
- catcher_id via target-encoding (5 per-class rates, out-of-fold), mirroring
  the existing pitcher_te/batter_te lookup pattern in the serving path.
- Derived: in-zone flag + radial distance from plate_x/z to zone center.
- Leakage-safe within-at-bat lag deltas: prev pitch type, prev outcome,
  prev plate_x/z + velo/pfx delta, same-vs-different-type flag (backward
  shift within game+pitcher, prior pitches only).
- HIGHEST-VALUE new signal: location-conditioned batter chase / O-swing
  rolling rate (streaming temporal cutoff) - the batter x location interaction
  that unconditional TE cannot express; directly proxies the swing latent.
- FENCE OUT: wpa_delta (outcome-derived leakage). NEVER use launch_*/hc_*/
  hit_distance/bb_type/events (batted-ball outcome fields).
- Files: contracts/feature_pipeline_post.json (schema_hash regen);
  backend .../inference/FeaturePipelinePitchPost.java (transform); training
  .../pitch/parity_fixture_post.py (+ expected fixture); a features-table
  materialization job (INSERT...SELECT + rolling/lag, per-year-chunked);
  train_post.py loader (add columns); reuse export_post_onnx.py unchanged.
- Model family, hyperparameters, calibrator type (IsotonicCalibrator), label
  encoding: UNCHANGED. Serving path: unchanged (wider input vector only).

### Evidence required
- Ablation ladder: add feature-group-by-group, measure each on the 2026
  holdout, so kill decisions are per-group.
- CV fold breakdowns (4 folds) + 2026 holdout top-1 / top-2 vs served v1.
- Per-class recall table (all 5 classes) vs v1.
- ECE / multiclass Brier vs v1 (guardrail).
- Full CI leakage suite green (incl. the two rolling/lag groups).
- Artifacts: artifacts/pitch_outcome_post/v2a/ {model.onnx, calibrator.json
  (isotonic, refit), metadata.json, feature_pipeline.json, mappings, TEs};
  experiment_results row.

### Rollback plan
v2a is a NEW registry version; v1 stays the served champion. Register SHADOW
only. If holdout top-1 AND top-2 lift < +0.3pp, OR ECE > ~0.004 / Brier >
~0.106, OR any per-class recall drops materially, OR the lift is carried
entirely by a feature that fails a CI leakage test -> abandon v2a, stay on v1.
Ablation ladder means a single leaky/regressing group is dropped without
killing the whole batch.
```

### Plan B (CHALLENGER): Hierarchical swing-gate decomposition

```
## Research Plan: pitch_outcome_post v2b - soft-marginalized hierarchy

### Hypothesis
The outcome has an exact 2-level generative structure (swing decision, then
{ball,called_strike} vs {swinging_strike,foul,in_play}) whose gate label is
observed NOISE-FREE at train time. Giving each confusable sub-problem a
dedicated booster MIGHT allocate capacity better than a flat 5-class softmax.
Honest expectation: structural lift over a FEATURE-MATCHED flat model is ~0
(-0.3 to +0.5pp top-1); this is a reparametrization of flat softmax and the
regime (25M rows, mild imbalance, strong learner) is flat-favored. Primary
value is a rigorous portfolio/postmortem result, plus a clean CPU-only,
single-ONNX-composed, per-node-calibrated decomposition.

### Proposed changes
- Three LightGBM boosters: stage-1 binary swing/no-swing (label derived from
  existing `label`); stage-2a binary {ball, called_strike} on no-swing rows;
  stage-2b 3-class {swinging_strike, foul, in_play} on swing rows. Built WITH
  Plan A's feature set (so the A/B is feature-matched).
- Combine by SOFT marginalization: P(y)=P(branch) x P(y|branch). NEVER hard
  argmax-route the gate (error-propagation regression).
- Single composed ONNX graph: convert_lightgbm(zipmap=False) each booster ->
  Gather/Slice gate -> Mul into 2a/2b -> Concat in canonical order
  [ball, called_strike, swinging_strike, foul, in_play] -> [N,5]. Marginal-
  ization is exact (sums to 1), no renorm. Serving side UNCHANGED (one
  model.onnx emitting [N,5] + outside-graph isotonic).
- Calibration: one composed per-class IsotonicCalibrator on the marginalized
  val outputs (reuse IsotonicCalibrator.fit + IsotonicCalibratorJava). Add
  per-node isotonic ONLY if composed ECE regresses (Leathart underconfidence).
- DROP logit adjustment (macro-recall objective, calibration-harmful) and
  focal/class-balanced on the swing leaf (mild imbalance, custom objective,
  re-corrected by isotonic). CatBoost-native-categoricals is a SEPARATE cheap
  identity experiment, not part of this plan.
- Files: new hierarchical train path (hierarchical_model.py is a pitch-TYPE
  template, not drop-in); onnx compose script (main new artifact); composed
  parity fixture (1e-4); registry intake as ONE model_name v2b (rule 9 not
  tripped - it is about pre vs post, not internal structure).

### Evidence required
- A/B vs the Plan A feature-matched flat booster on the 2026 holdout: top-1 /
  top-2 delta (must exceed CV fold noise), per-class recall, ECE / Brier.
- Composed-ONNX parity <= 1e-4 vs the Python 3-booster reference (validate at
  the float32 Mul/Concat marginalization node; verify Concat label ordering).
- CV fold breakdowns for the composed model.
- Artifacts: artifacts/pitch_outcome_post/v2b/ {composed model.onnx,
  calibrator.json, metadata.json, contract}; experiment_results row.

### Rollback plan
v2b is a NEW version; v1 (and v2a if promoted) stay served. Abandon if: does
not beat feature-matched flat by >= +0.3pp top-1 with held ECE/Brier; OR
composed ONNX cannot reach 1e-4 parity without loosening tolerance; OR Plan A
already hits the target (then do not spend the hierarchy engineering). A clean
honest negative result is an acceptable, ship-nothing outcome with postmortem
value.
```

### Plan C (DEFERRED R&D): Sequence transformer + entity embeddings

```
## Research Plan: pitch_outcome_post v2c - sequence/embedding hybrid (R&D)

### Hypothesis
A transformer over the pitcher's recent-pitch window + learned pitcher/
catcher/batter embeddings could add sequencing/tunneling + individual-identity
signal. Honest expectation: top-1 WASH over a feature-upgraded flat booster
(0 to +0.5pp), with any gain landing on calibration, which is already
saturated (ECE ~0.0025). Justification is portfolio/resume signal (transformer
+ clean ONNX export + in-process Java serving + cold-start story), NOT accuracy.
Pursue ONLY if Plan A and Plan B plateau below the target AND the portfolio
value is judged worth the cost.

### Proposed changes
- HYBRID only (never an end-to-end booster replacement): freeze a small
  2-layer TransformerEncoder (d_model 64, seq ~20) over tokenized recent
  pitches + pitcher/catcher embeddings; stack its pooled embedding (~80-112
  float cols) alongside the 41+ tabular features into the LightGBM meta-model;
  existing outside-graph isotonic in Java.
- Reuse training/.../pitch_comparison/ (transformer_model.py,
  transformer_catcher.py, hybrid_model.py, sequence_data.py,
  rookie_prototyping.py) - built for the pitch-TYPE task; re-target to outcome.
- Re-express unmask_fully_padded as an exportable where/clamp before ONNX
  export; encoder parity check (ORT vs torch, atol ~1e-4).
- NEW serving component (the hard part): a Java sequence-assembler that fetches
  the pitcher's last ~20 pitches from ClickHouse and rebuilds the token matrix
  BIT-FOR-BIT vs the Python index (velo (v-90)/10, outcome one-hot, count/3
  and /2, left-pad, pad-mask, masked mean-pool, first-pitch fix). Cold-start
  OOV path via the existing KMeans prototype, reproduced in Java.
- Off-box GPU training (the box OOM'd on the pure-LightGBM post head and has
  thermal/PSU crash history; the transformer cannot train on it).

### Evidence required
- Incremental lift of the hybrid over the Plan A feature-upgraded flat booster
  on the 2026 holdout: top-1 / top-2 (NOT vs v1, NOT vs a class-prior null),
  per-class recall, ECE / Brier.
- Encoder ONNX parity <= 1e-4; Java-vs-Python token-matrix parity fixture green.
- CV fold breakdowns; off-box run reproducibility (seed/determinism).
- Artifacts: artifacts/pitch_outcome_post/v2c/ {encoder.onnx, meta model.onnx,
  calibrator.json, metadata.json, contract, OOV prototype}; experiment_results.

### Rollback plan
v2c is a NEW version; v1/v2a stay served. Abandon if: hybrid does not beat the
feature-matched flat booster by >= +0.5pp top-1 with held ECE/Brier; OR
encoder cannot export within parity; OR the Java sequence-assembler parity
fixture cannot match Python; OR off-box GPU is unavailable. Never ship an
end-to-end transformer replacement of the booster.
```

---

## 13. Risk Register

| #   | Risk                                                                                                                                                                                                                                                                                     | Affects         | Severity | Mitigation                                                                                                                                                                                 |
| --- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------- | -------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| R1  | **Aleatoric ceiling**: swing decision unobserved, ~82% cap; a null-to-small result is a live outcome                                                                                                                                                                                     | A/B/C           | High     | Task 0 oracle ceiling estimate; set target from it; frame the win as honest + modest                                                                                                       |
| R2  | **Leakage** in chase-rate / lag features (the highest-value ones are the leakage-prone rolling/shift computations)                                                                                                                                                                       | A (B/C inherit) | High     | strict streaming temporal cutoff; full CI leakage suite gates the group; ablation isolates a leaky group                                                                                   |
| R3  | **`wpa_delta` / batted-ball fields** sneaking the outcome back in                                                                                                                                                                                                                        | A               | Critical | hard fence-list in the contract + a CI check that no listed leakage column enters `feature_order`                                                                                          |
| R4  | **Calibration regression** vs a tight ECE ~0.0025 while chasing top-1                                                                                                                                                                                                                    | A/B/C           | High     | ECE/Brier must-not-regress gate on every run; per-class recall table; prefer order-preserving calibration (Phase 0)                                                                        |
| R5  | **Feature redundancy**: pitch_zone/geometry duplicate continuous plate_x/z -> flashy features realize ~0 lift                                                                                                                                                                            | A               | Medium   | ablation ladder; expect gain from familiarity + chase + lag, not zone                                                                                                                      |
| R6  | **Attribution confound**: crediting hierarchy/sequence for gains that are really the features                                                                                                                                                                                            | B/C             | High     | A/B ONLY against the feature-matched flat baseline, never v1 or a null                                                                                                                     |
| R7  | **Composed-ONNX parity** at the float32 marginalization node; mis-ordered Concat is a silent correctness bug                                                                                                                                                                             | B               | Medium   | parity fixture at 1e-4; explicit canonical label-order assertion                                                                                                                           |
| R8  | **Train/serve skew** in the Java sequence-assembler (the exact bug class the project already hit)                                                                                                                                                                                        | C               | High     | bit-exact token parity fixture; treat as the load-bearing gate, not the ONNX graph                                                                                                         |
| R9  | **Off-box GPU dependency + thermal/PSU crash** history                                                                                                                                                                                                                                   | C               | High     | cloud GPU only; do not attempt on the box; ADR-0006/0007 data path                                                                                                                         |
| R10 | **Historical NULLs** (`arm_angle` pre-2024, movement/spin pre-2024) cap the physics/whiff-branch signal                                                                                                                                                                                  | A/C             | Medium   | LightGBM handles NaN natively; expect only `swinging_strike`-recall nudges; document                                                                                                       |
| R11 | **Schema-hash HARD-FAIL** if retrain precedes the contract/transform/parity landing                                                                                                                                                                                                      | A/B/C           | Medium   | enforce order: contract PR + Java transform + Python parity (<1e-6) green FIRST                                                                                                            |
| R12 | **Per-class collapse** masquerading as a top-1 win (foul <-> in_play reshuffle)                                                                                                                                                                                                          | A/B/C           | Medium   | per-class recall table mandatory in every evidence pack                                                                                                                                    |
| R13 | **Cold-start / rookie** for embeddings (no row for unseen player)                                                                                                                                                                                                                        | C               | Medium   | prefer TE global-prior backoff; only C needs the reproduced-in-Java KMeans OOV path                                                                                                        |
| R14 | **New serving artifacts under-counted**: `catcher_te` is a NEW canonical snapshot lookup file; `pitch_zone` is categorical; chase-rate/lag need streaming-cutoff STATE reproduced in Java (extend the `PitcherForm`/`PitcherFormRefreshJob` pattern to batters + prev-pitch)             | A               | Medium   | extend `register_gate` check #6 to require the `catcher_te` file; 1e-6 Java parity fixture for the non-passthrough features                                                                |
| R15 | **No repo precedent for B/C export machinery**: `onnx.compose`/`merge_models` appears NOWHERE (B is the first multi-graph composition); `pitch_comparison/*` has NO ONNX export path and `unmask_fully_padded`'s boolean-scatter is not exportable as-is; `catboost` is NOT a dependency | B/C             | Medium   | budget a from-scratch composed-graph parity harness (B); rewrite the scatter as `where/clamp` + new encoder parity harness (C); CatBoost is a new dep + converter path with zero precedent |
| R16 | **C cannot be shadow-routed cheaply**: the stateful Java sequence-assembler + 2nd ORT session + hot-path ClickHouse read must run live even in shadow, against the explicit p99 guardrail (`design.md:272`; champion max ~22ms today)                                                    | C               | High     | keep C R&D-only until the four serial gates (encoder export, assembler parity, p99, off-box determinism) pass                                                                              |

---

## 14. Future Research Opportunities

- **CatBoost with ordered target statistics** for `pitcher_id/batter_id/catcher_id` as native high-cardinality categoricals (leakage-safe by construction, ONNX-exportable) - a cheap orthogonal identity-encoding upgrade; expect small top-2/calibration gains.
- **GBDT + MLP-with-embeddings ensemble** (RealMLP-style), blended and re-calibrated - the most credible deep route per "Better by Default", but budget it as an ablation with modest expectations.
- **New ingest for the two highest-value signals the schema lacks**: `sz_top/sz_bot` (true per-batter zone geometry, better than the coarse `pitch_zone`) and `umpire_id` (Retrosheet join) for the called-strike boundary. Both are contract + ingest work, deferred.
- **Swing-probability sub-model as an explicit feature** (a calibrated `P(swing)` column feeding the outcome head) - a lighter-weight way to inject the dominant latent than a full hierarchy.
- **Normalization-aware isotonic (NA-FIR/SCIR)** if the Phase 0 diagnostic shows isotonic costs accuracy but a single temperature is too stiff on ECE - accuracy-preserving-by-construction with isotonic flexibility, but a single unreplicated 2025 paper with no Java/ONNX implementation (custom build).

---

## 15. Open Questions Requiring Experimentation

1. **What is the empirical ceiling?** Oracle top-1/top-2 with the realized swing flag injected. Anchors the whole program's target. (Task 0.)
2. **Is the current isotonic calibrator costing top-1/top-2?** Raw-vs-calibrated argmax agreement on the 2026 holdout. (Phase 0.)
3. **Is the current pitcher/batter target-encoding fit out-of-fold or naive in-fold?** A latent leakage/optimism check independent of everything else; naive TE would inflate CV without changing the holdout anchor.
4. **How much of Direction A's lift is the location-conditioned chase rate vs everything else?** The ablation ladder answers this; it is the one feature that directly targets the unobserved swing decision.
5. **Does the hierarchy beat feature-matched flat at all on this data?** The honest A/B; a clean negative is a publishable postmortem result.
6. **Does any sequence/embedding signal survive over a feature-upgraded flat booster?** The repo's own pitch-TYPE evidence predicts no; the outcome task has not been tested.

---

## Appendix: constraint compliance summary

- **Rule 13**: every train/val window <= 2025; 2026 touched only via the inverse-fenced `backfill_accuracy` holdout read; `refuse_holdout` wired in any year-accepting driver.
- **Schema-hash (rules 2/7)**: each feature-bearing direction ships a v2 contract; Java transform + Python parity (<1e-6) land before any retrain; registration HARD-FAILS on mismatch.
- **Serving parity (constraint 3)**: label encoding (5-class, canonical order) and `IsotonicCalibrator` type held as invariants; v1 remains the frozen rollback baseline; each challenger ships its own parity fixtures + refit calibrator.
- **Human-gated promotion (rules 5/6/9)**: research emits evidence into `experiment_results` (CV folds + 2026 top-1/top-2 + per-class recall + ECE/Brier); no promotion here.
- **ONNX-only serving**: all directions export to ONNX for in-process ONNX Runtime Java; TabR/TabPFN ruled out on this constraint alone.
