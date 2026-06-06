# Deploy Phase 1 toy artifacts to WSL2

> **Scope:** the one-time procedure to get the Phase-1 toy batted-ball model
> serving on the WSL2 prod box. After this, `./deploy.sh` from the WSL2 box
> handles JAR cycles; the artifacts only need to be refreshed when the toy
> model changes.
>
> **Why this exists:** model artifacts are never committed
> (`training/artifacts/**` is gitignored per CLAUDE.md), so a fresh clone or
> a fresh WSL2 install has no `model.onnx` to serve. The Spring backend
> refuses to start without one. This runbook is the bridge.

---

## What you're moving

| File                                           | Source               | Size    | Notes                                  |
| ---------------------------------------------- | -------------------- | ------- | -------------------------------------- |
| `training/artifacts/_toy/v0/model.lgb`         | re-trained on WSL2   | ~165 KB | LightGBM raw, gitignored               |
| `training/artifacts/_toy/v0/model.onnx`        | exported from `.lgb` | ~110 KB | ORT-Java reads this, gitignored        |
| `training/artifacts/_toy/v0/park_hr_rate.json` | computed from CH     | ~1 KB   | Java FeaturePipeline reads, gitignored |
| `training/artifacts/_toy/v0/metadata.json`     | train script output  | <1 KB   | provenance only                        |
| `contracts/feature_pipeline.json`              | already in git       | ~1.5 KB | Java validates schema_hash on boot     |

---

## Prerequisites on the WSL2 box

- Repo cloned at `~/code/the-bullpen` (or your chosen path)
- `uv` installed (`curl -LsSf https://astral.sh/uv/install.sh | sh`)
- `clickhouse` container running and reachable via `localhost:9000` /
  `localhost:8123` (`docker compose -f infra/docker-compose.yml ps`)
- Java 21 toolchain (the `./gradlew` wrapper handles it)
- `libgomp1` system package for LightGBM:
  ```bash
  sudo apt-get install -y libgomp1
  ```

---

## One-shot procedure

Run from the repo root on the WSL2 box.

### 1. Pull main + sync Python deps

```bash
git pull origin main
cd training
uv sync --frozen
```

### 2. Load 2024 historical data

```bash
uv run python -m bullpen_training.ingest.statcast_pull --season 2024
```

Expected: ~26s wall time, ~760K raw rows landing in `raw_statcast` (8 monthly
chunks). The final `pull complete` log line should show `assertions=passed`
(regular-season count within ±5% of 700K).

### 3. Clean into `pitches`

```bash
uv run python -m bullpen_training.ingest.transform_pitches --year 2024
```

Expected: ~1.5s, all 6 assertions pass, `pitches_final_rows ≈ 711898`.

### 4. Train the toy model

```bash
uv run python -m bullpen_training.battedball.train_toy --year 2024
```

Expected: `model.lgb` written to `training/artifacts/_toy/v0/` and a finite AUC
logged. (The toy now uses a temporal holdout, not the old random split; the prior
"≈ 0.987" was a random-split, leakage-optimistic figure - expect a lower, honest
number and do not treat 0.987 as a target.) Re-runs produce a byte-identical
`.lgb` (deterministic LightGBM + fixed seed).

### 5. Export to ONNX + park lookup

```bash
uv run python -m bullpen_training.battedball.export_toy_onnx --year 2024
```

Expected: `model.onnx` + `park_hr_rate.json` written. The log line should
show `schema_hash=91a093867595736dd7a82e4ce93d960a7d8880b7a616f5d50f6ae08596213ec1`
(same as `contracts/feature_pipeline.json`). If they don't match, the
export aborts.

### 6. Regenerate the dev parity fixture (optional, for local verification)

```bash
uv run python -m bullpen_training.battedball.parity_fixture --year 2024
```

Expected: `tests/fixtures/parity_toy_001*.json` written. This _would_
clobber the committed fixture; only commit the result if you've verified
the new fixture has the same schema_hash as `contracts/feature_pipeline.json`
(it always should, since you didn't change the contract).

### 7. Re-cycle the API

```bash
cd ..
./deploy.sh
```

This rebuilds the JAR, swaps the symlink at `/opt/bullpen/app.jar`,
restarts `bullpen-api`, smoke-polls `/actuator/health`. On startup
the API resolves the artifacts under `../training/artifacts/_toy/v0/`
relative to `/opt/bullpen`.

**If the deploy lands artifacts at a non-default path** (e.g., you keep
them outside the repo), pass:

```bash
sudo systemctl edit bullpen-api
# add override:
# [Service]
# Environment="BULLPEN_INFERENCE_TOY_ARTIFACTS_DIR=/var/lib/bullpen/models/_toy/v0"
sudo systemctl restart bullpen-api
```

The Spring property is `bullpen.inference.toy.artifacts-dir`; the systemd
env var maps to it by Spring's relaxed binding rules.

### 8. Smoke

```bash
curl -X POST -H 'Content-Type: application/json' \
  -d '{"launchSpeedMph":105,"launchAngleDeg":28,"releaseSpeedMph":94,"parkId":"NYY","stand":"R"}' \
  https://api.thebullpen.net/v1/predict/batted-ball
```

Expected: `{"probHr":<0..1>,"modelName":"_toy_batted_ball","modelVersion":"v0",...}`
in <500 ms.

Then visit `https://thebullpen.net/parks`, click a card → real prediction
renders. That closes the Phase 1 exit gate.

### 9. Confirm prediction logging

```bash
docker exec bullpen-clickhouse clickhouse-client --query \
  "SELECT count() FROM prediction_log WHERE request_at > now() - INTERVAL 5 MINUTE"
```

Expected: increments per prediction served (within ~1s of the request).

---

## When to re-run this

The training/export steps (2–6) are needed when:

- The toy model is retrained (decision to refresh, not on every deploy)
- `contracts/feature_pipeline.json` schema_hash changes (Phase 2 will)
- A fresh WSL2 install has no artifacts under `training/artifacts/_toy/v0/`

The `./deploy.sh` step alone (7) handles every other deploy.

---

## Risks + cleanups

- **CLAUDE.md ADR-0006 caveat:** this runbook requires Python work on the
  WSL2 box, which violates the dev/prod boundary. Acceptable here because
  the source-of-truth pipeline (statcast*pull, transform_pitches,
  train_toy, export_toy_onnx) is fully committed to git and the WSL2 box
  is only \_running* it, not editing it. Same posture as the May-21
  restore-drill exception that's already documented in
  `docs/phase-status.json`.

- **Long-term:** Phase 3 lands the model registry + S3-compatible artifact
  storage (ADR-0007). At that point this runbook is replaced by: Mac dev
  trains + uploads to R2, WSL2 deploy.sh pulls from R2. Until Phase 3,
  the WSL2-local generation path above is the bridge.

- **Disk usage:** `training/artifacts/` grows as new model versions land.
  Phase 1 has only `_toy/v0/`; nothing to prune yet.
