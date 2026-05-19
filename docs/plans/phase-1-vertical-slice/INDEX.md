# Phase 1 — Vertical Slice · INDEX

> ONE prediction visible end-to-end, in the browser, deployed.
> Weeks 4–7 · ~50–65 hours. See [`../../plan.md`](../../plan.md) §Phase 1.
>
> **Phase exit criterion**: Visit `thebullpen.net/parks`, click a batted ball, see real prediction in <500 ms end-to-end.
>
> **MVP cuts**: NONE. This phase IS the credibility floor.

---

## Cross-cutting docs to read alongside any leaf in this phase

- [`../00-MASTER.md`](../00-MASTER.md)
- [`../00-CONVENTIONS.md`](../00-CONVENTIONS.md)
- [`../00-TESTING-STRATEGY.md`](../00-TESTING-STRATEGY.md) — parity-test pattern
- [`../00-RISK-REGISTER.md`](../00-RISK-REGISTER.md) — G1, G4, G11, G12 surface in this phase
- [`../../design.md`](../../design.md) §4.2, §5

---

## Why this phase exists

The dominant failure mode for portfolio projects is horizontal building. This phase exists to prove the *full* path (Statcast → ClickHouse → Python training → ONNX → Java serving → React click → prediction) works. Everything in Phase 1 will be replaced or upgraded later — that's fine. The point is end-to-end, not quality.

---

## Leaf plans

### 1.1 — `1.1-statcast-historical-pull-2024.md`
pybaseball pulls 2024 season only into ClickHouse `raw_statcast`. Idempotent on date range. Streaming inserts in monthly chunks (Risk Register G12).
- **Decisions referenced**: [86], [83].
- **Closes / addresses**: G12 (memory-safe backfill pattern proven).
- **Acceptance**: `SELECT count(*) FROM raw_statcast WHERE season = 2024` returns ~700K rows ±5%.

### 1.2 — `1.2-pitches-cleaning-and-dedup.md`
ClickHouse `pitches` table with cleaned schema. ReplacingMergeTree dedup keyed on `(game_id, at_bat_index, pitch_number)`. Per-stage SQL assertions (decision [90]) baked into the load step.
- **Decisions referenced**: [84], [92], [85], [90].
- **Acceptance**: `SELECT count(*) FROM pitches FINAL` matches `raw_statcast` modulo dedup. Assertion failures are loud (test injects a duplicate, asserts the loader catches it).

### 1.3 — `1.3-toy-batted-ball-model.md`
LightGBM, 5 features (exit velocity, launch angle, spray angle, temperature, park as categorical), single-output (HR probability — single binary label). NOT calibrated, NOT properly evaluated. Pure plumbing.
- **Decisions referenced**: [26], [36].
- **Acceptance**: `model.lgb` exists; predicting on a held-out 1k sample produces non-trivial AUC (>0.7 — sanity, not a real metric).

### 1.4 — `1.4-onnx-export-and-java-load.md`
Convert LightGBM → ONNX (via `onnxmltools` or `lightgbm`'s native ONNX export). Java loads via ONNX Runtime Java. **Parity test**: same input fixture → identical prediction (within 1e-6) in Python and Java.
- **Decisions referenced**: [27], [28].
- **Closes / addresses**: G4 (preprocessing boundary — initial decision: numerics in ONNX; categoricals in Java preprocessing).
- **Acceptance**: `test_python_java_parity` passes both locally and in CI.

### 1.5 — `1.5-predict-batted-ball-endpoint.md`
Spring `POST /v1/predict/batted-ball`. Bean Validation on request body. ONNX model loaded once at startup; warm-up on `/actuator/health/readiness` (Risk Register G11). Latency metric emitted.
- **Decisions referenced**: [29], [30].
- **Closes / addresses**: G11 (warm-up implemented for the first time).
- **Acceptance**: `curl POST /v1/predict/batted-ball` returns prediction JSON in <500 ms; latency histogram visible in Grafana.

### 1.6 — `1.6-park-explorer-toy-page.md`
React page at `/parks`. Hardcoded list of ~10 historical batted balls (pulled at build time as a static JSON). Click a ball → fetch prediction → render. Loading / error states. No design polish — that's Phase 4.
- **Decisions referenced**: [93], [95].
- **Acceptance**: clicking renders a real prediction; vertical-slice Playwright e2e passes (the only e2e test we have at this point).

### 1.7 — `1.7-primitive-prediction-logging.md`
Async batched write to ClickHouse `prediction_log` (basic version). Bounded queue, drop-on-overflow, drop counter wired to Micrometer. Schema is a strict subset of the eventual Phase 3b schema; no `role` column yet (default 'champion').
- **Decisions referenced**: [30].
- **Closes / addresses**: partial G2 (cross-DB integrity — at least visible in metrics now).
- **Acceptance**: predictions appear in `prediction_log` within 5s of being made; killing the API mid-burst loses ≤10 predictions.

---

## Phase 1 exit gate

```bash
# From a clean clone:
git pull && ./deploy.sh

# Then in a browser:
# 1. Visit thebullpen.net/parks
# 2. Click any batted ball
# 3. Observe prediction render
# 4. Check Grafana — prediction_total counter incremented
# 5. Check ClickHouse — `SELECT count(*) FROM prediction_log` increments
```

If all 5 pass: Phase 1 done. Move to Phase 2 (real models).
