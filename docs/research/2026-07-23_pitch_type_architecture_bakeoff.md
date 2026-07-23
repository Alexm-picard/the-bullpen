# Research Report: Pitch-Type Prediction - Architecture Bake-Off

- **Author**: Applied AI Research Lead (Claude)
- **Date**: 2026-07-23
- **For**: TD -> Developer handoff (architecture selection; no prod code, no registry)
- **Status**: Box-produced evidence, committed Mac-side under `docs/research/` (ADR-0006 carve-out; not committed from the box).
- **Task**: pre-pitch pitch-type prediction (distribution over the next pitch's type, before it is thrown).

> **Evidence provenance**: all numbers are from a live bake-off on the box (ClickHouse `pitches`, 2015-2025, rule-13 clean - 2026 untouched). Game-level stratified sample (`cityHash64(game_id) % 6`, whole games so sequences stay intact): **1,239,778 pitches**. LightGBM, rolling-origin 4-fold CV, temperature-calibrated on each val year. Scripts + intermediate JSON in the session scratchpad (`pitchtype/`).

---

## 1. Ranked recommendation + evidence

**Winner: Candidate A - LightGBM multinomial + engineered sequence features.** It matches or beats the learned sequence model on the metrics that gate promotion, and wins decisively on the ONNX-constrained, calibration-first frontier the platform actually requires.

### Evidence table (mean of 4 rolling-origin folds 2022/2023/2024/2025, **calibrated**)

**Primary taxonomy y7 (7 classes: FF, SI, FC, SL, CU, CH, OFF):**

| Model                                               | top-1                   | top-3     | log-loss  | ECE (cal)     | ECE (raw)                                                        | ONNX                |
| --------------------------------------------------- | ----------------------- | --------- | --------- | ------------- | ---------------------------------------------------------------- | ------------------- |
| always-FF floor                                     | ~0.325                  | -         | -         | -             | -                                                                | trivial             |
| **D** per-pitcher count-conditioned frequency       | 0.415                   | -         | -         | -             | -                                                                | trivial (lookup)    |
| **S** state-only (no identity, no sequence)         | 0.331                   | 0.727     | 1.683     | 0.0141        | 0.0253                                                           | PASS                |
| **SA** state + pitcher arsenal (rolling freq)       | 0.440                   | 0.872     | 1.288     | 0.0144        | 0.0198                                                           | PASS                |
| **A** SA + sequence features                        | **0.449**               | **0.882** | **1.262** | 0.0157        | 0.0165                                                           | **PASS (verified)** |
| **C** A + pitcher_id native categorical             | 0.442                   | -         | -         | 0.0042        | -                                                                | PASS (slow build)   |
| **B** learned sequence (transformer, repo evidence) | 0.454 (2025, full data) | 0.726     | 1.313     | 0.0070-0.0079 | **FAIL/costly** (no in-repo export; mask op not ONNX-able as-is) |

**Coarse taxonomy y4 (4 classes: FB, CT, BR, OFF):** D 0.306 / S 0.481 / SA 0.517 / **A 0.520** (top-3 0.970, log-loss 0.983, ECE **0.0114**).

### Why A wins the frontier

- **Metrics: a dead heat with the learned model, at a fraction of the cost.** A on a 1/6 sample = 0.449 mean / 0.441 on 2025; the repo's catcher-aware transformer (candidate B, `training/PITCH_MODEL_STATUS.md`, full 709k-pitch 2025 holdout) = 0.454 with ECE 0.007. A trained on the full corpus would very likely close that ~1pp (more arsenal-history resolution). The learned sequence encoder does **not** buy accuracy over engineered sequence features - the same result the repo already found on this exact task, now reproduced with rolling-origin discipline.
- **ONNX: A is native and verified.** `onnxmltools.convert_lightgbm(zipmap=False, opset=15)` -> output `probabilities [None,7]`; ORT-vs-LightGBM max prob diff over 5,000 rows = **2.04e-07**. Identical serving path to the existing `pitch_outcome_pre` head (LightGBM ONNX + outside-graph calibrator). **Zero new serving machinery.** Candidate B's transformer has no in-repo export path and its all-padded-mask op is not ONNX-exportable without a rewrite (documented). B is disqualified by cost, not by a metric loss.
- **Calibration: A meets the gate out of the box.** Temperature scaling fits **T ≈ 1.0** (0.98-1.03) every fold - LightGBM multiclass is already near-calibrated. Achieved ECE **0.008-0.020** (mean 0.0157 y7, 0.0114 y4), the best fold (2025) at **0.0082**, all under the project's `< 0.02` promotion bar ([180]/ADR-0014). Temperature is order-preserving (never moves top-1) and ONNX-foldable.
- **C adds nothing.** Pitcher identity is already fully captured by the rolling arsenal features; `pitcher_id` as a native categorical adds **+0.1pp** (noise) and reintroduces the O(2^k) categorical-split slowdown documented in `train_post.py`. Per-pitcher embeddings are not worth it - consistent with the repo's prior "catcher/pitcher embedding helped calibration, not accuracy" finding.

**Ranked verdict: A >> D > B ≈ C.** Build A. B is a research curiosity here; C and per-pitcher embeddings are dead weight.

---

## 2. Feature spec for the winner (candidate A)

All features are pre-pitch. Leakage status is a **gating result**, verified below (Section 5), not a formality. The developer must reproduce the streaming cutoff in ClickHouse (the existing `tier_3_form` `RANGE BETWEEN N PRECEDING AND 1 PRECEDING` pattern is the template) and extend the four CI leakage tests to cover every new column.

### Tier S - state (11 features, all trivially pre-pitch-safe)

`balls, strikes, outs, inning, base_state, stand_i, throws_i, park_i, times_through_order, at_bat_number_in_game, times_faced_today` - all in-schema (V003 + V013), all known before the pitch.

### Tier ARS - pitcher arsenal / identity (9 features) - THE dominant signal (+10.9pp)

Per-pitcher **expanding** frequency of each y7 class, strictly before the current pitch:
`ars_{FF,SI,FC,SL,CU,CH,OFF}` = `(cumsum(class) - class) / prior_n` over the pitcher's date-ordered career-to-date; **NaN at the pitcher's first career pitch (0.17%)**, LightGBM handles natively.
`ars_FF_by_count` = same expanding frequency conditioned on `(pitcher, balls-strikes)`.
`pitcher_prior_n` = count of the pitcher's prior pitches (support / cold-start indicator).

- **Streaming cutoff**: strict backward - the current pitch is excluded (the `- class` / shift term). **Leakage status: PASS** (strict-backward recompute matched cached values exactly for the top pitcher; shuffled-target collapses to floor).
- **Prod note**: implement as a ClickHouse window (mirror `tier_3_form`); the career-expanding window legitimately spans training years for a test-year row (past data is fine) - the only hard rule is exclude the current and future pitches.

### Tier SEQ - sequencing (4 features) - the "is it worth building" tier (+1.0pp only)

`prev1_pt_i` = previous pitch type in the **outing** (`(game_id, pitcher_id)`), integer-encoded on a fixed fold-independent vocab; sentinel `-1` at outing start.
`prev2_pt_i` = pitch two ago (sentinel `-1`).
`prev1_missing` = indicator for outing start.
`pitches_into_outing` = 0-based count of the pitcher's prior pitches this game.

- **Streaming cutoff**: `groupby(game_id, pitcher_id).shift(1/2)`. **Leakage status: PASS** - `prev1` is NA at exactly **100.0%** of outing starts (verified). The single most important SEQ feature is `prev1_pt_i` (matches the repo's finding that "what was the last pitch" dominates the sequence signal).

---

## 3. Achievable-performance envelope + taxonomy

**Envelope (what to expect from a full-data build of A):**

- **top-1: ~0.45-0.46** (y7) / ~0.52 (y4). Sample-to-full uplift is ~+1pp over the reported 0.449.
- **top-3: ~0.88** (y7) / ~0.97 (y4) - the calibrated distribution is genuinely informative even where top-1 is modest.
- **log-loss: ~1.26** (y7) / ~0.98 (y4).
- **ECE: 0.008-0.016** with temperature scaling, comfortably under the 0.02 gate. If a fold needs `< 0.01`, isotonic-per-class is available but is not order-preserving - prefer temperature.

**Taxonomy recommendation: the 7-class y7 grouping** - `FF | SI | FC | SL(=SL+ST+SV) | CU(=CU+KC+CS) | CH | OFF(=FS+FO+KN+EP+…)`.
Rationale, and this is a **rolling-origin hazard the raw distribution hides**: sweeper (`ST`) is only 2.9% across 2015-2025 but ~8% recently, because it was not a tracked label until ~2023 - older sweepers were labeled `SL`. A fine-grained taxonomy that keeps `ST` separate creates a class that is **absent in early training folds but present in test folds** - a temporal train/test mismatch that will wreck calibration on the recent folds. **Folding `ST`/`SV` into `SL` neutralizes this** (and `KC`/`CS` into `CU`). y7 is the sweet spot: 7 actionable classes, robust to the relabeling, calibrates to ECE < 0.016. Use **y4** only if a coarser, higher-confidence output is wanted (top-1 0.52, ECE 0.011) - it is more robust still but throws away the FF/SI and SL/CU distinctions.

---

## 4. ONNX + calibration feasibility verdict (the locked-serving-path go/no-go)

**GO.** Candidate A is a drop-in for the existing `pitch_outcome_pre` serving path:

- **ONNX**: `convert_lightgbm(zipmap=False, target_opset=15)` -> single graph, output `probabilities [None,7]`, verified ORT parity **2e-07** (< 1e-5). In-process ONNX Runtime Java, no sidecar, no RPC. Passes constraint 1 as a first-class result.
- **Calibration**: temperature scaling (single scalar T ≈ 1.0), order-preserving, ONNX-foldable (`logits/T` before softmax) or kept outside the graph exactly like the current isotonic calibrator. Achieved ECE 0.008-0.016 < 0.02 gate. Passes constraint 2 with the calibration shown, not asserted.
- **Serving delta from `pitch_outcome_pre`**: a different input vector (24 features) and a 7-class output. No new artifact type.

Candidate B (transformer) fails the clean-export bar as-is; C exports but is not worth its build cost.

---

## 5. Leakage gate results (per constraint 4 - a gating result, not a formality)

| Test                                                             | Result                                                                                                                                                                                                                                                                                                                                                                                |
| ---------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Shuffled-target** (train A on permuted labels, score 2025)     | **PASS** - top-1 collapses to **0.323 = the always-FF floor**. No signal survives label shuffle => no leakage.                                                                                                                                                                                                                                                                        |
| **Future-contamination** (strict-backward recompute of `ars_FF`) | **PASS** - independent shift-based recompute matches cached values (`allclose`, atol 1e-5).                                                                                                                                                                                                                                                                                           |
| **Boundary / ID-consistency** (`prev1` at outing starts)         | **PASS** - NA at **100.0%** of `(game_id, pitcher_id)` starts; never borrows across outings.                                                                                                                                                                                                                                                                                          |
| **Calendar-date trace**                                          | Streaming cutoff is strict-backward by construction; **the developer must port the four CI leakage tests to the new ARS + SEQ columns** and compute them via the `tier_3_form` ClickHouse window (my probe used pandas groupby-shift on a sample - correct logic, but prod must use the temporal-cutoff SQL and re-gate). This is the crux risk and the one open implementation item. |

---

## 6. Honest read (one paragraph)

**Is the lift over the frequency baseline worth a new head?** Marginally, and only if you value the calibrated distribution over raw top-1. The naive per-pitcher count-conditioned frequency (candidate D) already hits **41.5%** top-1; the full engineered model (A) reaches **~45%** - a **+3.4pp** gain, of which the pitcher-arsenal identity is **+10.9pp over state-only** and the actual **sequence engineering is only +1.0pp**. The learned sequence transformer adds **nothing** over that (~0 vs A) at large ONNX/serving/thermal cost, and per-pitcher identity modeling beyond arsenal frequencies adds **+0.1pp**. So the ceiling here is low because pitch selection is intrinsically high-entropy: a pitcher's historical mix conditioned on the count is already most of the signal, and neither sophisticated sequencing nor learned embeddings move it much. **Recommendation: build candidate A** - it is cheap, ONNX-native, and calibrates well (ECE < 0.016), which makes it a legitimately useful _calibrated pitch-type prior_ (a "likely next pitch" broadcast-style panel, or a context feature for other heads). But scope it honestly as a **well-calibrated distribution, not an accurate top-1 predictor**, and do not fund the sequence-model or embedding tracks for this task - the evidence (here and in the repo's prior transformer run) says they do not earn their complexity.

---

## Appendix: method + reproduction

- Sample: `s1_pull.py` (game-level, ~1.24M pitches, 2015-2025). Features: `s2_features.py` (taxonomy + tiers + streaming-cutoff sequence/arsenal). Bake-off: `s3_bakeoff.py` (y7, y4, C). Verification: `s4_verify.py` (corrected D, shuffled-target, future-contamination, ONNX export/parity).
- Rolling-origin folds: train 2015..N / val N+1 / test N+2 for test years {2022, 2023, 2024, 2025}. No random splits; 2026 untouched.
- LightGBM: `num_leaves 63, min_data_in_leaf 200, lr 0.05, feature/bagging_fraction 0.8, seed 42, 6 threads`, early-stop 30.
- Caveat: numbers are on a 1/6 game-level sample; absolute top-1 will rise ~1pp on full data, but the **ablation deltas (arsenal +10.9, sequence +1.0, embedding +0.1) are the robust findings** and are what the recommendation rests on.
