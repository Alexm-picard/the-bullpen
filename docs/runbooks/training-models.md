# Training the models (full procedure)

> **Scope:** the end-to-end procedure to train **all five registry
> artifacts** from a populated ClickHouse `pitches` table — the three
> serving models (pre-pitch head, post-pitch head, batted-ball MLP) plus
> their two baselines (pitch LR, batted-ball LGBM). Two paths: **all at
> once** (§Path A) and **stage-by-stage** for a box that thermal-throttles
> (§Path B).
>
> **Where this runs:** the **WSL2 desktop only** (ADR-0006). Full training
> needs the complete 2015–2025 ClickHouse dataset and — for the batted-ball
> MLP — the GPU. The MacBook runs `make train-sample` on a stratified
> sample for iteration; it cannot produce the production bytes. Authoring
> still happens on the Mac and ships via `git push`; you _run_ these
> commands on the desktop.
>
> **Holdout discipline (rule 13):** every command below tops out at
> `--max-year 2025`. **2026 is holdout-only** — never pass it to a training
> or feature-build step.

---

## What gets trained

| #   | Model (registry name)       | Command (from `training/`)                       | Artifact dir                              | Gate                                  |
| --- | --------------------------- | ------------------------------------------------ | ----------------------------------------- | ------------------------------------- |
| 0   | — (feature table)           | `features.tier_1_2` then `features.tier_3_form`  | `features` table + `artifacts/encodings/` | rows present per fold                 |
| 1   | `pitch_outcome_pre`         | `pitch.production --model lightgbm`              | `artifacts/pitch_outcome_pre/v1/`         | ECE < 0.02 on test                    |
| 2   | `pitch_outcome_post`        | `pitch.production --model post`                  | `artifacts/pitch_outcome_post/v1/`        | ECE < 0.02 on test                    |
| 3   | `pitch_outcome_lr_baseline` | `pitch.production --model lr`                    | `artifacts/pitch_outcome_lr_baseline/v1/` | co-registered (rule 9)                |
| 4   | `battedball_outcome` (MLP)  | `scripts/run_2c_overnight.sh` (stages 2c.5–2c.7) | `artifacts/battedball_mlp_v1/`            | per-park ECE < 0.05, ρ ≥ 0.8 (dec 52) |
| 5   | `batted_ball_lgbm_baseline` | `scripts/run_2c_overnight.sh` (stage 2c.8)       | `artifacts/batted_ball_lgbm_baseline/v1/` | co-registered (rule 9)                |

Models 1–3 are LightGBM/LR (CPU). Models 4–5 + the 30 isotonic
calibrators are the batted-ball pipeline — model 4 needs the GPU.

---

## Prerequisites (once per machine)

```bash
cd ~/code/the-bullpen/training
uv sync                                                    # install deps

# ClickHouse up + the historical pull done (Phase 1.1/1.2 — pitches table
# populated 2015–2025). Verify:
docker compose -f ../infra/docker-compose.yml up -d
docker exec bullpen-clickhouse clickhouse-client --query \
  "SELECT toYear(game_date) y, count() FROM pitches GROUP BY y ORDER BY y"
# expect ~700k–750k rows/season, 2015–2025.

# GPU (model 4 only). Confirm:
uv run python -c "import torch; print(torch.cuda.is_available())"   # True
```

> If the `pitches` table is empty, training has nothing to read — run the
> Phase 1 ingest first (`scripts/backfill_seasons.sh`), it's upstream of
> everything here.

---

## Path A — all at once

Run from `training/`. This is the literal sequence; there is no single
wrapper script across pitch + batted-ball (by design — the batted-ball
orchestrator is its own gated pipeline). Each line blocks until done.

```bash
cd ~/code/the-bullpen/training

# 0. Feature table (Tier 1+2, then add Tier 3). REBUILD-on-rerun, idempotent.
uv run python -m bullpen_training.features.tier_1_2   --min-year 2015 --max-year 2025
uv run python -m bullpen_training.features.tier_3_form --min-year 2015 --max-year 2025

# 1. Pre-pitch head  → pitch_outcome_pre   (4-fold CV + final bundle, then ONNX)
uv run python -m bullpen_training.pitch.production    --model lightgbm --version v1
uv run python -m bullpen_training.pitch.export_pre_onnx --model-name pitch_outcome_pre --version v1

# 2. Post-pitch head → pitch_outcome_post
uv run python -m bullpen_training.pitch.production    --model post --version v1
uv run python -m bullpen_training.pitch.export_post_onnx --model-name pitch_outcome_post --version v1

# 3. Pitch LR baseline → pitch_outcome_lr_baseline   (no ONNX — sklearn pipeline)
uv run python -m bullpen_training.pitch.production    --model lr --version v1

# 4+5. Batted-ball pipeline → battedball_mlp_v1 + batted_ball_lgbm_baseline
#      (retrodict → MLP → 30 calibrators → sanity gate → LGBM → comparison)
nohup bash scripts/run_2c_overnight.sh > logs/2c-overnight/orchestrator.log 2>&1 &
```

Total desktop wall-time: steps 0–3 are roughly **1–2 h** (LightGBM on the
full feature table dominates); step 4+5 is the long one at **~8–12 h** (see
[`2c-overnight-pipeline.md`](2c-overnight-pipeline.md) for the per-stage
breakdown). If you have the thermal headroom to leave it running
overnight, Path A is the simplest.

---

## Path B — stage-by-stage (thermal-throttling box)

Each numbered step above is independent and idempotent — re-running a step
overwrites its own artifacts and reads the previous step's output from disk
/ ClickHouse. So you can run one step, **let the box cool**, then run the
next. Natural cut-points (heaviest first, so you know where to expect the
fans):

| Run when cool | Command                                                 | Heat / time                          | Safe to stop after? |
| ------------- | ------------------------------------------------------- | ------------------------------------ | ------------------- |
| **B0a**       | `features.tier_1_2 --min-year 2015 --max-year 2025`     | medium, ~20–40 min                   | yes                 |
| **B0b**       | `features.tier_3_form --min-year 2015 --max-year 2025`  | **hot** (windowed scans), ~40–60 min | yes                 |
| **B1**        | `pitch.production --model lightgbm` + `export_pre_onnx` | medium, ~20–40 min                   | yes                 |
| **B2**        | `pitch.production --model post` + `export_post_onnx`    | medium, ~20–40 min                   | yes                 |
| **B3**        | `pitch.production --model lr`                           | light, ~5–10 min                     | yes                 |
| **B4**        | batted-ball — itself sectionable, see below             | **hottest** (GPU)                    | per sub-stage       |

**The batted-ball step (B4) is itself a 6-stage pipeline** and the biggest
heat source. To run _its_ stages with cooldowns, edit
`scripts/run_2c_overnight.sh` and comment out the earlier `run "..."` lines
to resume from a later stage — the orchestrator's stages are
`2c.4-retrodict` → `2c.4-retrodict-val` → `2c.5-mlp-train` →
`2c.6-calibrators` → `2c.7-sanity-gate` → `2c.8-lgbm-train` →
`2c.9-comparison`, every writer idempotent (ClickHouse `ReplacingMergeTree`
for retrodict, artifact-dir overwrite for the rest). So a typical
cool-between-stages cadence is: run `2c.4-retrodict` (the ~1–2 h hot one),
cool, uncomment from `2c.5`, run the GPU MLP, cool, uncomment from `2c.8`
for the LGBM baseline. Full detail + the env knobs (`MLP_EPOCHS`, `DEVICE`,
etc.) in [`2c-overnight-pipeline.md`](2c-overnight-pipeline.md).

> **Tip for a throttling box:** `DEVICE=cuda nice -n 10 ...` keeps the run
> off your interactive cores; a single-season smoke
> (`SEASON_FROM=2024 SEASON_TO=2024 VAL_SEASON=2024 MLP_EPOCHS=10`) lets you
> validate the whole orchestration in ~30 min before committing to the
> full thermal load.

---

## Common options

Every `pitch.production` / `features.*` / `export_*` command takes:

- `--version v1` — artifact version (default `v1`).
- `--log-format json` — structured logs (default `console`). Use `json`
  when piping to a file you'll grep later.
- `pitch.production --skip-cv` — skips the 4-fold CV and trains only the
  final bundle. **Iteration only** — a `--skip-cv` bundle has `n_folds=0`
  in its metadata and is **not promotion-eligible** (no `experiment_results`
  row for `promote-model` to gate on).

LightGBM determinism is pinned (`deterministic=True`, `force_row_wise=True`)
so re-runs are bit-identical _on the same machine_; cross-machine identity
is not guaranteed (documented LightGBM caveat).

---

## Verify + register

Training only writes artifacts to disk. To get a model serving:

1. **Check the gate.** Pitch heads: ECE < 0.02 on the test split (in
   `artifacts/<model>/v1/eval/`). Batted-ball: the 2c.7 sanity gate must
   PASS (Spearman ρ ≥ 0.8 cross-park, decision [52]) and per-park ECE < 0.05
   / aggregate < 0.02 — see [`2c-register-and-close.md`](2c-register-and-close.md) §verify.
2. **Register SHADOW** via the `register-model` skill / admin API. Rule 7
   refuses any model whose feature-schema hash doesn't match the production
   `feature_pipeline*.json` contract. Co-register the baseline for the same
   role (rule 9).
3. **Promote** only through the `promote-model` gate (rule 6 — human-gated,
   never automatic), against a passing `experiment_results` row.

Batted-ball registration has its own close-out procedure in
[`2c-register-and-close.md`](2c-register-and-close.md).

---

## When this runbook should change

- **New season ingest** — bump `--max-year` / `VAL_SEASON` to the new last
  _non-holdout_ season and re-run from step 0. Never let 2026 (or the
  current holdout season, rule 13) into a `--max-year` or `VAL_SEASON`.
- **Feature contract change** — if a `contracts/feature_pipeline*.json`
  schema hash changes, rebuild the feature table (step 0) first, then
  retrain; registration will hard-fail (rule 7) otherwise.
- **A single top-level orchestrator gets written** — there's deliberately
  no `train_all.sh` today (the batted-ball pipeline is its own gated
  artifact). If one lands, this runbook's Path A becomes its `--help`.
