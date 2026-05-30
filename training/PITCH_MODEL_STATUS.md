# Pitch-Type Model — Status, Debug Log & Cloud Migration Notes

_Last updated: 2026-05-28_

This document records the state of the pitch-type prediction experiments, the
bugs and performance work done, the host-crash investigation that blocked the
final run locally, and what a cloud training environment needs.

---

## 1. What the model is

Goal: predict the **next pitch type** (8 classes: FF, SI, SL, CH, CU, FC, ST,
OTHER) from pre-pitch context + the pitcher's recent sequence.

Best architecture so far (the "combined" model):

- **Catcher-aware transformer** encodes a rolling window of the pitcher's
  recent pitches into a pooled sequence vector, concatenated with learned
  **pitcher** and **catcher** entity embeddings →
  `[pooled | pitcher_emb | catcher_emb]`.
- That embedding is fed into a **LightGBM** meta-model alongside tabular
  features (count/inning/base-state + enriched context + streak features).

### Two runners (kept separate on purpose)

**Best architecture (canonical).** Two transformers (V2 pitcher-emb and
catcher-aware) → LightGBM meta-models, giving Hybrid+Context / Catcher-Hybrid
(base) / **Catcher-Hybrid + Context**. No experimental ideas (below).

- **Per-model (recommended locally — one GPU-heavy transformer at a time, so the
  machine can rest between runs):**
  ```bash
  cd training
  CLICKHOUSE_PORT=9000 uv run python scripts/train_hybrid_context.py   # V2; best accuracy 45.26%
  # ...let it rest...
  CLICKHOUSE_PORT=9000 uv run python scripts/train_catcher.py          # catcher; best ECE 0.0070
  ```
  Each trains one transformer, saves its weights to `--save-dir`
  (`artifacts/pitch_combined_v1`), and merges into one `metadata.json`. Order is
  irrelevant; run either alone. Shared plumbing: `pitch_comparison/combined_common.py`.
- **All-in-one (cloud / healthy host):** `scripts/run_combined_experiment.py`
  trains both back-to-back. Memory-proven (completed a full run).
  ```bash
  cd training && CLICKHOUSE_PORT=9000 uv run python scripts/run_combined_experiment.py
  ```

**Reusing saved models (no retrain):** `pitch_comparison/load_combined.py` —
`load_combined_models("artifacts/pitch_combined_v1")` reconstructs whichever
models have been trained (untrained ones are `None`). CLI sanity check:
`uv run python -m bullpen_training.pitch_comparison.load_combined`.

**Experimental extensions (held for cloud):** `training/scripts/run_final_experiments.py`
(wrapper `run_final_experiments.sh`). Same catcher-aware base, plus **three
research ideas** — kept out of the canonical runner:

1. **Streak / `repeat_counter` features** — `repeat_pitch_type`,
   `prev_pitch_type_int`, `prev_pitch_result_int` (leakage-safe backward shift
   within pitcher+game) in `data_enriched.py`, gated behind
   `prepare_enriched_datasets(..., add_streak=True)` (off by default).
2. **SHAP** (`TreeExplainer`) feature attribution on the LightGBM booster.
3. **Rookie prototype clustering** (`rookie_prototyping.py`) — KMeans
   archetypes from established (≥1000-pitch) pitchers; rookies (<500 career
   pitches) borrow a prototype profile + embedding via streaming, prior-only
   cluster assignment; switches to the default model at 500 pitches.

Data: ClickHouse `pitches` table, expanded columns from migrations
`V012`/`V013` (catcher_id, times_through_order, win_expectancy, biomech, etc.).
Train 2015–2023, val 2024, test 2025. **2026 is holdout-only (CLAUDE.md rule 13).**

---

## 2. Bugs fixed (verified correct)

### `val=nan` early-stopping (the big one)

A pitcher's **first pitch** has an empty history window, so its transformer
`src_key_padding_mask` row is all-True (fully padded). Attention then softmaxes
over an all-`-inf` row → NaN, which poisons the pooled output (`NaN * 0 = NaN`)
and the validation loss. With `val=nan`, the best-checkpoint logic never fires
(`nan < inf` is False) → patience trips → **training early-stopped at epoch 5
instead of 20**, leaving the encoder badly under-trained.

Fix: `unmask_fully_padded()` in `transformer_model.py`, applied in all four
`encode()` methods. Fully-padded rows are given one visible position for
attention, then pooled over the **original** mask so they contribute zero (the
entity embedding carries those rows). Verified: val loss is finite and
decreases monotonically (1.3238 → 1.2762 over 20 epochs). **Do not revert.**

### Final result (properly trained, 20 epochs — 2026-05-28)

`run_combined_experiment.py` completed a full end-to-end run (both transformers
trained 20 epochs with finite val loss, both extractions, all 3 boosters) on the
2025 holdout:

| Model                                 | Acc        | Top2       | LogL   | ECE        |
| ------------------------------------- | ---------- | ---------- | ------ | ---------- |
| **Hybrid + Context** (V2 pitcher emb) | **0.4526** | **0.7264** | 1.3125 | 0.0079     |
| Catcher-Hybrid (base)                 | 0.4523     | 0.7250     | 1.3171 | 0.0072     |
| Catcher-Hybrid + Context              | 0.4523     | 0.7257     | 1.3141 | **0.0070** |

Takeaways:

- **Hybrid + Context is best on accuracy (45.26%).** Adding the catcher embedding
  does **not** improve accuracy (−0.03pp, within noise) but **improves
  calibration** (ECE 0.0079 → 0.0070). So catcher identity refines _confidence_
  more than _which pitch_.
- Top context features by gain: `at_bat_number_in_game`, `times_faced_today`,
  `times_through_order`, `pitcher_avg_arm_angle`, `win_expectancy` — workload /
  familiarity / fatigue signals dominate.
- This supersedes the earlier provisional (5-epoch buggy) result.

---

## 3. Performance work (verified correct)

- **Precomputed token matrix** — `sequence_data.py` / `sequence_data_v2.py` now
  build the full token matrix once (vectorized); `__getitem__` is a numpy
  gather instead of a per-token Python loop. Verified byte-identical to the old
  `build_token`. This flipped training from data-bound (GPU ~27%) to GPU-bound
  (~85%).
- **DataLoader workers** — parallel workers for the re-iterated train/val
  loaders (config knobs). **Single-pass extraction/prediction loaders are
  pinned to `num_workers=0`** via `loader_kwargs(force_sync=True)` — forking
  workers off the large parent process caused a WSL OOM on the constrained
  local box. Keep this; it's safe everywhere and workers barely help a single
  pass.
- **Lean memory restructure** in `run_final_experiments.py` — frees the
  transformer/index after extraction; precomputes rookie inputs then frees
  `full_df`; pulls tabular arrays then frees the split frames; builds hstacks
  one split at a time (freeing each embedding as copied); the context-only
  booster is a column slice of the streak matrix (no second hstack). Peak RSS
  ~15GB → ~9GB. Per-phase `_mem()` RSS logging is on.

---

## 4. Host-crash investigation (intermittent — a full run later succeeded)

> **UPDATE 2026-05-28:** `run_combined_experiment.py` **completed a full
> end-to-end run** on the dedicated outlet — both transformers trained 20 epochs
> on the GPU, both extractions, all 3 boosters, no crash. **This proves GPU
> compute load is NOT a deterministic trigger** (the GPU ran flat-out through
> training + extraction and the machine stayed up). The crashes are therefore
> **intermittent**, consistent with marginal power delivery / a flaky connection
> rather than "GPU load always kills it." The dedicated-outlet move likely
> helped; a degrading PSU (or loose power connector) that only drops out on
> _some_ transient coincidences remains the best explanation. Still worth
> bench-testing/replacing the PSU and checking cable seating — but routine
> training is viable locally between episodes. Original investigation below.

The earlier runs hard-crashed the **entire desktop under load**, around the
embedding-extraction / booster phase.

Evidence gathered (Windows Event Log via WSL interop):

| Check       | Finding                                                                                                                        |
| ----------- | ------------------------------------------------------------------------------------------------------------------------------ |
| Crash event | `Kernel-Power 41`, **BugcheckCode 0**, no BSOD, **no crash dump** → abrupt power loss / instant hard-off, not a software fault |
| Frequency   | **6+ Kernel-Power 41 crashes in ~1 week**, several predating this project's heavy runs                                         |
| Temps       | **~81 °C** under load — well within spec, not thermal                                                                          |
| Memory      | WSL capped (12GB applied, `MemTotal 11.7GB`); host had **~19GB free** at crash → host RAM starvation impossible                |
| WHEA        | No hardware-error events (but instant power-off can't log)                                                                     |

Mitigations tried, in order:

1. Made the run lean (~9GB peak) — still crashed.
2. Raised then **lowered the WSL cap** (`C:\Users\mpica\.wslconfig`, backup
   `.wslconfig.bak`): 15→20→**12GB**, so WSL can never starve the host — still crashed.
3. Capped GPU at... (could not set from WSL; default 220W, draws ~152W under load).
4. **Moved PC off a daisy-chained surge strip** (shared with a mini-fridge,
   fan, monitors, TV) to a **dedicated wall outlet** — **still crashed.**

### Conclusion

Memory and thermal are ruled out. The crashes are **intermittent power events**
(Kernel-Power 41 / bugcheck 0 / no dump), not GPU-compute-deterministic — a full
GPU training+extraction run later completed fine on the dedicated outlet (see the
UPDATE above). Best explanation: a **marginal/degrading PSU or a flaky power
connection** that only drops out on certain transient coincidences; the
daisy-chained surge strip (shared with a mini-fridge compressor) was likely a
contributing trigger and moving to a dedicated outlet helped. Components are
well-matched (800–850W PSU, Ryzen 7 7800X3D, RTX 4070 SUPER) so wattage isn't
the issue — unit health / cabling is. Recommend bench-testing the PSU and
reseating power connectors; training is viable locally in the meantime, with
cloud as the robust fallback.

---

## 5. Cloud migration — what's needed

Training this model on the local desktop is blocked by the hardware fault above.
To run on a cloud GPU instance:

**Compute**

- 1× modern NVIDIA GPU (the model is small; an L4 / A10 / T4 / 4090-class is
  plenty). CUDA + recent PyTorch.
- ≥16GB system RAM (peak RSS ~9–15GB depending on config). ≥8 vCPU for the
  LightGBM boosters.

**Data** — the run reads from a ClickHouse `pitches` table via
`docker exec bullpen-clickhouse clickhouse-client` (see `data_enriched.py`
`_run_clickhouse`). On cloud you must either:

- stand up ClickHouse with the 2015–2025 data loaded (migrations `V012`/`V013`
  applied), **or**
- adapt the loader to read a Parquet/snapshot export of the `pitches` table.
  (Cleanest: export the needed columns to Parquet and point the loader at it —
  removes the Docker/ClickHouse dependency for a pure training box.)

**Config** — `ExperimentConfig` now ships cloud-appropriate defaults
(8 dataloader workers, pinned memory, `lgbm_num_threads=0` = all cores,
batch 4096). Tune `dataloader_workers` / `lgbm_num_threads` to the instance.

**Run**

```bash
cd training
CLICKHOUSE_PORT=9000 bash scripts/run_final_experiments.sh
# results -> training/data/eval/pitch_final/{final_experiments.json, *.png}
```

Watch the `[mem]` RSS lines if the instance is RAM-limited.

**Holdout discipline** — 2026 data is test-only; never include it in any
training or validation split (CLAUDE.md rule 13).

---

## 6. Local config note

The local-machine throttles (lower workers, capped threads, 12GB `.wslconfig`)
were reverted to healthy/cloud defaults on 2026-05-28. The `.wslconfig` on the
Windows side remains at 12GB as a safety ceiling for the local box; it does not
affect cloud. If you ever run locally again _after the PSU is fixed_, the
defaults here are fine on a healthy machine.
