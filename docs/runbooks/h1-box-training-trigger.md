# H1 - Box training trigger: pitch heads full-data run

> **Scope:** operator hand-off for running the full-data training of
> `pitch_outcome_pre`, `pitch_outcome_post`, and `pitch_outcome_lr_baseline`
> on the 12 GB GPU desktop. This is the piece that cannot happen on the MacBook -
> the full 2015-2025 ClickHouse dataset and the GPU are required.
>
> **Where:** the WSL2 desktop only (ADR-0006). No code editing on the box; all
> fixes must be authored on the Mac and deployed via `git push` + `./deploy.sh`.
> The operator only runs the training commands described here.
>
> **When:** the 2-6 AM ET GPU window (decision [19]). Live services continue
> during training (training is a separate process), but the box is under heavy
> memory pressure (see the OOM note below). Run the trigger before midnight ET
> so the long steps complete inside the window and the box cools before 6 AM.
>
> **Holdout discipline (CLAUDE.md rule 13):** every command below tops out at
> `--max-year 2025`. Never pass `2026` to any training or feature-build step.
>
> **References:** `docs/runbooks/training-models.md` (full multi-model procedure),
> `docs/runbooks/2c-overnight-pipeline.md` (batted-ball pipeline detail).

---

## Memory constraint context (read before you run)

The live champion + API + ClickHouse consume roughly 5 GB of the box's 12 GB,
leaving around 6 GB headroom. Three OOM incidents were fixed as of 2026-06-07:

| Fix | Description | Status |
| --- | --- | --- |
| DEV-1 | `tier_3_form.build_fold_full` now chunks the encode window by calendar year instead of materialising the full fold span in pandas | merged |
| CV-MEM-1 | `build_lgb_dataset` eager-construct + per-fold frees + deferred test_df load | merged |
| CV-MEM lever-2 | LR fit: free frames before fit, `StandardScaler(copy=False)`, float32 design matrix (preprocessing only; lbfgs internally upcasts) | merged |
| Path-1 | LR production fit `subsample_train_rows` caps at 3 M rows (fixed-seed; metadata-recorded; CV + test metrics stay full-data) | merged |

**Escape hatch - lever 3 (decouple training to a non-live box):** if a 4th OOM
occurs, the correct systemic response is to pull the fold-4 Parquet off the box
and train on a separate machine rather than another per-site fix. That is a
`/decide` conversation, not a quiet change.

**Watchdog note:** `guarded_train.sh` (CV-MEM-2 persistence gate) was designed
but has not been committed to the repo yet - it is an untracked-state gap. Verify
whether it exists at `~/code/the-bullpen/training/scripts/guarded_train.sh` before
relying on it. If missing, monitor memory manually during the run with
`watch -n 5 free -h` in a second terminal.

---

## Prerequisites (once per box or after a git pull)

```bash
cd ~/code/the-bullpen

# 1. Confirm you are on the correct SHA (same as Mac push)
git log --oneline -5

# 2. Confirm Python deps are current
cd training
uv sync

# 3. ClickHouse up and populated 2015-2025
docker compose -f ../infra/docker-compose.yml up -d
docker exec bullpen-clickhouse clickhouse-client --password thebullpen \
  --query "SELECT toYear(game_date) y, count() FROM pitches GROUP BY y ORDER BY y"
# Expect ~700k-750k rows per season, 2015-2025. If empty, run the Phase 1
# ingest (scripts/backfill_seasons.sh) first - training has nothing to read.

# 4. GPU reachable
uv run python -c "import torch; print(torch.cuda.is_available())"   # must print True

# 5. Layer-1 snapshot FIRST (hard rule - never touch live data without a snapshot)
infra/backup/clickhouse-snapshot.sh
```

---

## Step 0 - Feature table rebuild

Run this only if the feature table does not exist or the `contracts/feature_pipeline*.json`
schema hash changed since the last build. Rebuilding is idempotent (ClickHouse
`ReplacingMergeTree` - re-runs dedupe on merge), but it takes 40-60 min and is
the hottest non-GPU step.

```bash
cd ~/code/the-bullpen/training

CLICKHOUSE_PORT=9000 uv run python -m bullpen_training.features.tier_3_form \
  --min-year 2015 --max-year 2025
```

After the build, run the leakage gate fail-loud (critical: the 2026-06-07 build
silently skipped it via `CLICKHOUSE_PORT=8123`):

```bash
CLICKHOUSE_PORT=9000 BULLPEN_REQUIRE_CH=1 uv run python -m pytest tests/leakage -x
```

`CLICKHOUSE_PORT` must be **9000** (native). `8123` is the HTTP/MCP port and
`from_env` rejects it. `BULLPEN_REQUIRE_CH=1` turns an unreachable ClickHouse
into a hard fail - never a skip.

---

## Step 1 - Pre-pitch head (`pitch_outcome_pre`)

**Only run this if a new artifact is needed.** If the pre-head artifact is
already banked and validated at `artifacts/pitch_outcome_pre/v1/`, skip to step 2.

```bash
cd ~/code/the-bullpen/training

# 4-fold CV + final bundle (this is the memory-heavy step)
uv run python -m bullpen_training.pitch.production \
  --model lightgbm --version v1

# ONNX export
uv run python -m bullpen_training.pitch.export_pre_onnx \
  --model-name pitch_outcome_pre --version v1
```

Expected peak: fold 4 (2015-2023 train) is the largest fold. With CV-MEM-1
applied, the `Dataset.construct()` is deferred and frames are freed per-fold.
Watch for RSS above 8 GB sustained - if it climbs past 10 GB and does not
recover within two minutes, the process is OOM-bound; kill it and open a
lever-3 conversation.

Verification:

```bash
ls -lh artifacts/pitch_outcome_pre/v1/
# expect: model.onnx  metadata.json  eval/
cat artifacts/pitch_outcome_pre/v1/metadata.json | python3 -m json.tool | grep ece
# ece must be < 0.02 on the test split
```

---

## Step 2 - LR baseline (`pitch_outcome_lr_baseline`)

The LR production fit is capped at 3 M rows (Path-1 fix, `subsample_train_rows`,
fixed-seed, metadata-recorded). CV and test-eval stay full-data. lbfgs internally
upcasts float32 to float64, so the actual peak is roughly 60-70 % of a naive
float64 estimate - not a full 50 % reduction.

```bash
cd ~/code/the-bullpen/training

uv run python -m bullpen_training.pitch.production \
  --model lr --version v1
```

No ONNX export for this step (sklearn pipeline, not ONNX). Verify:

```bash
ls -lh artifacts/pitch_outcome_lr_baseline/v1/
cat artifacts/pitch_outcome_lr_baseline/v1/metadata.json | \
  python3 -m json.tool | grep -E "lr_train_subsample|ece"
# lr_train_subsample_rows should be present (Path-1 provenance field)
```

---

## Step 3 - Post-pitch head (`pitch_outcome_post`)

```bash
cd ~/code/the-bullpen/training

uv run python -m bullpen_training.pitch.production \
  --model post --version v1

uv run python -m bullpen_training.pitch.export_post_onnx \
  --model-name pitch_outcome_post --version v1
```

The post head is a LightGBM model (not the LR path), but it trains on a larger
feature set (Tier 4 physics). If it OOMs, the `model_factory` signature
restructure (lever 2) or decouple-to-non-live-box (lever 3) is the path - not
another floor-adjustment.

Verification:

```bash
ls -lh artifacts/pitch_outcome_post/v1/
cat artifacts/pitch_outcome_post/v1/metadata.json | python3 -m json.tool | grep ece
# ece must be < 0.02 on the test split
```

---

## Stage-by-stage heat/cooldown (thermal-throttling box)

Each step above is independent and idempotent - re-running a step overwrites
its own artifacts and reads the previous step's output from disk/ClickHouse.
Natural cool-down cut-points:

| Run when cool | Step | Heat / time | Safe to stop after? |
| --- | --- | --- | --- |
| Step 0 | Feature table rebuild | hot (windowed scans), 40-60 min; year-chunked | yes |
| Step 1 | Pre-head (lightgbm + export) | hot, 20-40 min | yes |
| Step 2 | LR baseline | medium (lbfgs, 3M-row cap), 5-15 min | yes |
| Step 3 | Post-head (lightgbm + export) | hot, 20-40 min | yes |

Tips: `nice -n 10 uv run python ...` keeps the training process off interactive
cores. A smoke run on a single season validates orchestration without committing
to the full thermal load:

```bash
# Single-season smoke (2024 only, validates orchestration, ~30 min)
uv run python -m bullpen_training.pitch.production \
  --model lightgbm --version v1-smoke \
  --min-year 2024 --max-year 2024
```

A smoke artifact has `n_folds=1` in its metadata and is NOT promotion-eligible
(the `promote-model` gate requires a passing `experiment_results` row from a
4-fold run). Use smoke only to verify the orchestration path, not for production.

---

## After training - verify and bring back to Mac

Training only writes artifacts to disk on the box. To get a model serving:

1. **Check eval gates.** ECE < 0.02 on the test split is required for the pitch
   heads (`artifacts/pitch_outcome_{pre,post}/v1/eval/`). The LR baseline is
   co-registered without an independent ECE gate (rule 9 - baseline co-register).

2. **Bring the artifact metadata back to the Mac** (ADR-0006: no commits from the
   box). `scp` or `rclone copy` the metadata JSON files back:

   ```bash
   # From the Mac, pull metadata back for cross-check (not the ONNX bytes)
   scp <box-user>@<box-host>:~/code/the-bullpen/training/artifacts/pitch_outcome_pre/v1/metadata.json \
     training/artifacts/pitch_outcome_pre/v1/metadata.json
   ```

3. **Register SHADOW** via the `register-model` skill / admin API. Rule 7
   hard-fails any model whose feature-schema hash does not match the production
   `feature_pipeline*.json` contract. Co-register the LR baseline for the same
   role (rule 9).

4. **Promote** only through the `promote-model` gate (rule 6 - human-gated,
   never automatic), against a passing `experiment_results` row.

---

## Rollback / abort

If any step fails or memory pressure looks unrecoverable:

- Kill the training process (Ctrl-C or `kill <PID>`). The step is idempotent -
  re-run it once the pressure has cleared.
- If the box needs a restart after a crash: confirm both service units are still
  `active` with `systemctl is-active bullpen-api bullpen-worker`. If either is
  down, `sudo systemctl restart bullpen-api bullpen-worker` and cross-check
  against `docs/runbooks/desktop-environment.md`.
- No code changes on the box. All fixes go through the Mac + `git push` + deploy.

---

## When this runbook should change

- New season ingest: bump `--max-year` to the new last non-holdout season. Never
  let 2026 (or the current holdout season, rule 13) into `--max-year`.
- A 4th OOM occurs: open a `/decide` for lever-3 (decouple training to a
  non-live box) before writing another per-site memory fix.
- Feature contract changes: rebuild the feature table (step 0) first, then
  retrain. Registration will hard-fail (rule 7) if the schema hash mismatches.
- `guarded_train.sh` lands in the repo: add it to the step 0/1 invocations and
  note the watchdog parameters (soft 1500 MB/2-consecutive, hard 500 MB/immediate).
