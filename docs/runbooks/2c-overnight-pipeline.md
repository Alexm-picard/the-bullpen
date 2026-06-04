# Phase 2c overnight production pipeline

> **Scope:** the one-shot procedure to take Phase 2c from
> code-complete (where it landed during local Mac dev) to production-
> registered. Runs all six 2c stages — retrodict backfill → MLP
> training → calibrator fit → sanity gate → LGBM baseline →
> comparison — end-to-end on the desktop per ADR-0006.
>
> **Why this exists:** code authoring is on the MacBook; full-data
> training touches GPU + the 1.5 M-row retrodict backfill. The Mac
> dev box doesn't have CUDA and only ran the 987-BIP smoke pipeline.
> The hard production gates (Spearman ρ ≥ 0.8 cross-park sanity,
> per-park ECE < 0.05, MLP-vs-LGBM Brier comparison) only fire here.

---

## Prerequisites on the desktop

- Repo at `~/code/the-bullpen` synced to `origin/main` past commit
  `9d52acd` (the `feat(phase-2c): 2c.9 …` commit that closes 2c
  code-complete).
- `uv` installed.
- ClickHouse container up via `docker compose -f infra/docker-compose.yml up -d`
  (the retrodict + dataset loaders shell out to
  `docker exec bullpen-clickhouse clickhouse-client`).
- Phase 1.1 ingest must have run — `pitches` and `bbip` tables must
  be populated for 2015-2025. Verify with:
  ```bash
  docker exec bullpen-clickhouse clickhouse-client --query \
      "SELECT toYear(game_date) AS y, count() FROM pitches WHERE description='in_play' GROUP BY y ORDER BY y"
  ```
  Expect 100K-160K BIPs per season 2015-2025.
- The 2c.4 V011 migration must be applied — Flyway runs it on Spring
  boot, or apply manually:
  ```bash
  docker exec -i bullpen-clickhouse clickhouse-client \
      < backend/src/main/resources/db/clickhouse/V011__bbip_retrodicted_labels.sql
  ```
- **`weather_observed` must be backfilled first** (decision [88] landed —
  2c.4 now joins per-game weather). Apply `V016__weather_observed.sql` and
  run the weather backfill **before** this pipeline, or 2c.4 fails fast on
  the `--min-weather-coverage 0.9` guard. See
  [`weather-backfill.md`](weather-backfill.md). Bypass for an un-backfilled
  smoke season with `MIN_WEATHER_COVERAGE=0`.
- GPU passthrough working (CUDA + `torch.cuda.is_available()` returns
  True). Confirmed in Phase 0; verify with `uv run --project training
python -c "import torch; print(torch.cuda.is_available())"`.
- **2c.4 now runs the GPU-B fused integrate+classify kernel on the GPU via
  `numba.cuda`** (not just torch). Verify the numba CUDA path too:
  `uv run --project training python -c "from numba import cuda; print(cuda.is_available())"`.
  If it prints `False`, `--device auto` silently falls back to the njit/prange
  CPU path (~hours, not minutes) — pass `DEVICE=cuda` to fail loud instead.
  The kernel runs **float32** (decision: GPU-B precision): re-validate the
  decision [131] calibration gate on the GPU output before trusting a full
  relabel (the 2c.7 cross-park sanity gate is the in-pipeline guard).
- **The frozen `observed_norm` gate anchor must exist** (decision [140]). The
  2c.7 sanity gate now scores against `data/observed_norm_factors.json`, not the
  published file, and **fails loud if the anchor is missing while a model exists**.
  Emit it once (needs only `pitches`, so it can run before the relabel):
  ```bash
  uv run python scripts/compare_park_factors.py \
      --emit-anchor data/observed_norm_factors.json
  ```
  Then bring the file back to the Mac to commit it (ADR-0006 — no commits from the
  prod box). If it's already committed and present in the checkout, skip this.
- ~10 GB free disk for logs + artifacts (the LightGBM `model.txt`
  alone is ~3 MB on the full 1.5 M-row dataset).

---

## Procedure

All commands run from the `training/` directory.

### 1. Sanity check the runbook before kicking off

```bash
cd ~/code/the-bullpen/training
bash scripts/run_2c_overnight.sh --dry-run
```

Prints the six commands it'll run with the resolved arguments. Confirm
the seasons + val season + device match expectations.

### 2. Kick off the full pipeline

```bash
nohup bash scripts/run_2c_overnight.sh \
    > logs/2c-overnight/orchestrator.log 2>&1 &
echo $! > logs/2c-overnight/orchestrator.pid
```

Detaches so it survives SSH disconnects. The orchestrator stops on
any non-zero stage exit, so if it crashes overnight you'll see
exactly which stage failed via the per-stage log file.

Expected wall-time on the desktop's 16 cores + GPU: **~8-12 h total**.
Breakdown (rough estimates from Mac smoke numbers + Linux speedups):

| Stage              | Mac (single-process) | Desktop (16 cores + GPU) |
| ------------------ | -------------------- | ------------------------ |
| 2c.4 retrodict     | ~5.8 h (1.5 M BIPs)  | minutes (GPU-B fused)\*  |
| 2c.4 retrodict val | ~0.5 h               | ~1-2 min (GPU-B)\*       |
| 2c.5 MLP train     | not run (GPU needed) | ~30 min (50 epochs)      |
| 2c.6 calibrators   | ~1 s smoke           | ~2 min                   |
| 2c.7 sanity gate   | <1 s                 | <1 s                     |
| 2c.8 LGBM train    | ~4 s smoke           | ~15-30 min               |
| 2c.9 comparison    | <30 s                | ~1 min                   |

\* 2c.4 timings are post-GPU-B (fused integrate+classify, float32, on `DEVICE=auto`
→ CUDA). The historical ~1-2 h figure was the pre-GPU-B Numba-CPU path; if numba
CUDA isn't available the run falls back to that path, so confirm the prereq above.
With 2c.4 off the critical path, total wall-time is now dominated by 2c.5/2c.8.

### 3. Monitor progress

```bash
# Tail the live orchestrator log
tail -f logs/2c-overnight/orchestrator.log

# Or check the per-stage logs as they appear
ls -lt logs/2c-overnight/
```

### 4. Verify on completion

```bash
ls -lh artifacts/battedball_mlp_v1/        # model.pt model.onnx metadata.json calibrator.json
ls -lh artifacts/batted_ball_lgbm_baseline/v1/  # model.txt metadata.json calibrator.json
ls -lh data/eval/                          # batted_ball_comparison_v1.{json,html}
ls data/eval/reliability_diagrams_per_park/ | wc -l   # should be 30
```

Read the comparison report:

```bash
open data/eval/batted_ball_comparison_v1.html   # or scp it back to Mac
```

Read the sanity-gate log:

```bash
cat logs/2c-overnight/2c.7-sanity-gate.log
```

Decision [141] split the old 2c.7 gate into two stages:

- **2c.7a — outcome-calibration gate (BLOCKING).** This is the stage that
  blocks registration. It asserts per-park ECE post-cal mean **< 0.05** AND
  aggregate test ECE **< 0.02** from `artifacts/battedball_mlp_v1/calibration_metrics.json`
  (written by 2c.6). If **2c.7a** FAILS, do NOT register in 3a — the model
  misses the calibration bar. (A "metrics missing" failure means 2c.6 didn't
  emit `calibration_metrics.json` — re-run it.)
- **2c.7b — cross-park sanity DIAGNOSTIC (NON-blocking, advisory).** Runs via
  `run_soft`, so it reports cross-park ρ vs the frozen `observed_norm` anchor
  but does **not** halt the run — v1 makes no cross-park park-factor claim
  ([141]). Read its ρ + per-park offenders in `logs/2c-overnight/2c.7b-cross-park-diagnostic.log`
  (and `data/cross_park_sanity_report.json`, `reference_*` fields vs
  observed_norm, schema_version 2). An `ADVISORY FAIL (non-blocking)` line here
  is expected at the current ~0.33 fidelity and is fine; a low ρ is a documented
  known limitation, not a blocker.

---

## Knobs

Environment variable overrides (all optional):

| Var                   | Default             | Meaning                                                                                                                                                                         |
| --------------------- | ------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `SEASON_FROM`         | `2015`              | First training season (inclusive).                                                                                                                                              |
| `SEASON_TO`           | `2024`              | Last training season (inclusive).                                                                                                                                               |
| `VAL_SEASON`          | `2025`              | Single season held out for val + calibrator fit + sanity gate + comparison.                                                                                                     |
| `N_MC`                | `10`                | 2c.4 Monte Carlo samples per BIP. `10` matches the leaf spec.                                                                                                                   |
| `MLP_EPOCHS`          | `50`                | 2c.5 epochs (cosine LR over this number).                                                                                                                                       |
| `MLP_BATCH_SIZE`      | `256`               |                                                                                                                                                                                 |
| `MLP_LR`              | `1e-3`              |                                                                                                                                                                                 |
| `DEVICE`              | `auto`              | `cuda` / `cpu` / `auto`. Drives **both** the 2c.4 GPU-B fused kernel and 2c.5 MLP training. Force `cuda` to fail loud on no-GPU (avoids a silent CPU fallback for the relabel). |
| `LGBM_BOOST_ROUND`    | `2000`              | 2c.8 boost rounds.                                                                                                                                                              |
| `LGBM_EARLY_STOPPING` | `50`                |                                                                                                                                                                                 |
| `LOG_DIR`             | `logs/2c-overnight` |                                                                                                                                                                                 |
| `ART_DIR`             | `artifacts`         |                                                                                                                                                                                 |
| `DATA_DIR`            | `data`              |                                                                                                                                                                                 |

Smoke run on a single season (validates the orchestration, ~30 min wall time):

```bash
SEASON_FROM=2024 SEASON_TO=2024 VAL_SEASON=2024 MLP_EPOCHS=10 \
    bash scripts/run_2c_overnight.sh
```

---

## Idempotency + re-running

Every writer is idempotent:

- 2c.4 uses ClickHouse `ReplacingMergeTree` on the natural key. Re-runs
  dedupe on merge; latest `ingested_at` wins.
- 2c.5 / 2c.6 / 2c.8 overwrite their artifact directory in place.
- 2c.9 overwrites `data/eval/batted_ball_comparison_v1.{json,html}`.

So if a stage fails overnight, fix the cause and re-run the whole
script — the completed stages just rewrite the same artifacts. Skip
ahead by commenting out earlier `run "..."` lines if you want to
restart from a specific stage.

---

## What this pipeline does NOT do

- **Register the trained models in the registry.** That's the close-out
  runbook [`2c-register-and-close.md`](2c-register-and-close.md) — once
  the sanity gate passes here, it verifies the gates and registers the
  MLP + LGBM baseline (SHADOW) via the 3a admin API. The pipeline only
  writes to local disk.
- **Update the live serving path.** Models stay candidates / shadow
  until promoted via 3b.
- **Reverse decision [45] if LGBM wins.** If the 2c.9 comparison
  report sets `prefer_for_production: 'lgbm'`, that's the
  conditional-on-result acceptance criterion in the 2c.9 leaf — open
  `decisions.md` and write the ADR.

---

## When this runbook should change

- Decision [88] (per-game weather pull, observed leg) **has landed**: 2c.4
  joins `weather_observed` and the retrodict labels recalibrate against real
  game-time wind. The weather backfill is now a prerequisite (see
  [`weather-backfill.md`](weather-backfill.md)). The remaining open piece is
  the pre-game **forecast** leg + a shared Java/Python wind parser.
- If the schema_hash in `contracts/feature_pipeline_post.json`
  changes, 2c.5 fails at registration time (rule 7) — update the
  contract first, then re-run.
- New season ingest (e.g. 2026) — bump `VAL_SEASON=2026` (or
  `SEASON_TO=2025 VAL_SEASON=2026`) and re-run.
