---
name: run-rolling-cv
description: Standard rolling-origin temporal cross-validation harness for The Bullpen models. Trigger when the user says "run CV", "evaluate this model", "rolling CV", or before any model promotion. Enforces CLAUDE.md discipline rule 10.
---

# run-rolling-cv

The only acceptable evaluation harness for this project. Random splits are forbidden (rule 10).

## Fold layout (locked in design.md)

- **4 folds spanning 2015–2025**
- Within each fold: train ends before val begins. No date overlap.
- Splits are by **calendar date**, never by game id, never by pitch id.
- LR baseline runs on every fold alongside the candidate model.

| Fold | Train range             | Val range               |
| ---- | ----------------------- | ----------------------- |
| 1    | 2015-01-01 → 2017-12-31 | 2018-01-01 → 2019-12-31 |
| 2    | 2015-01-01 → 2019-12-31 | 2020-01-01 → 2021-12-31 |
| 3    | 2015-01-01 → 2021-12-31 | 2022-01-01 → 2023-12-31 |
| 4    | 2015-01-01 → 2023-12-31 | 2024-01-01 → 2025-12-31 |

(2020 partial-season caveat: keep it; document that fold 2 val is noisier. Don't drop the season.)

## Required leakage tests (run before reporting any metric)

Per rule 10, all four must pass:

1. `test_future_contamination` — no feature value derived from data after the row's `game_event_ts`
2. `test_shuffled_target` — AUC on shuffled targets is in [0.48, 0.52]; otherwise leakage is encoded
3. `test_calendar_date_trace` — every feature's `as_of` <= `game_event_ts`
4. `test_id_consistency` — pitch/game ids consistent across folds, no overlap between train and val sets

## Procedure

1. **Pre-flight** — invoke `ml-leakage-auditor` on the training code. If it returns FAIL, do not run CV.
2. **Run the 4 folds** via the promotion-evidence driver (`uv run python -m bullpen_training.eval.promotion.driver --model <name>`, run from `training/`), which calls the 4-fold `bullpen_training.eval.cv_harness.run` harness. (There is no `rolling_cv.py` - do not create one; `cv_harness.run` + the driver are the harness.) Always co-run the co-registered baseline.
3. **Collect metrics per fold**:
   - Primary: log-loss (multinomial for pitch outcome), Brier for batted ball
   - Secondary: calibration (ECE, reliability diagram), AUC
   - Guardrails: p99 inference latency (measured against ONNX-exported model, not source framework)
4. **Aggregate** — report mean and per-fold breakdown. Never report only the mean.
5. **Compare against LR baseline** — must beat baseline on primary metric on at least 3/4 folds to be promotion-eligible
6. **Write results** to:
   - `/training/eval/results/{run_id}/fold_<N>.json` (raw)
   - `experiment_results` table in SQLite via the registry helper (this is the row `promote-model` will look for)
7. **Generate calibration plots** and save under `/training/eval/results/{run_id}/calibration_*.png`

## Output to user

```
ROLLING CV COMPLETE:
  run_id: <id>
  experiment_results_id: <id>
  candidate: log-loss = X (per fold: ...)
  baseline:  log-loss = Y (per fold: ...)
  beats baseline on N/4 folds
  calibration ECE: <value> (target <= 0.02 for promotion)
  p99 latency: <ms>
LEAKAGE TESTS: all passed (4/4)
PROMOTION ELIGIBLE: yes / no — reason
```

## Hard rule

If any leakage test fails, the run is **invalid**. Do not report metrics. Fix the leak first.
