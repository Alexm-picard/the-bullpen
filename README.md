# The Bullpen

A self-hosted baseball-analytics platform built primarily as a serving
wrapper around three calibrated models. Operates through at least one MLB
season for a real drift postmortem.

- **Live site**: https://thebullpen.net/
- **Ops dashboard**: https://thebullpen.net/ops
- **About + methodology**: https://thebullpen.net/about
- **Repo**: https://github.com/Alexm-picard/the-bullpen

> **What's live vs. showcase (v1):** the per-game detail view (`/games/:id`) and the
> player lookup pull real data from the Spring backend. The home slate, park explorer,
> Ops dashboard, and About page currently render from committed fixtures — they exercise
> the full design system and component layer while the live MLB poller and the Ops
> aggregation endpoints land (see _Known limitations_ and the roadmap). The wiring is a
> localized page-level swap, not a rewrite.

## What's interesting about it

- A **custom ML systems wrapper** — model registry, A/B router, drift
  detection, retraining triggers — written from scratch in Java rather
  than pulled in via MLflow. The wrapper is the project; the models are
  the excuse.
- **ONNX Runtime in-process** in Java + Spring Boot 3 — no Python
  sidecar, no live RPC. Training is Python; serving is JVM.
- **Editorial-data design system** (Inter / JetBrains Mono / Source
  Serif 4 on a warm-paper substrate). Not yet-another-SaaS chrome.
- **Per-model eval artifact** with rolling-origin cross-validation,
  reliability diagrams, calibration metrics — always co-registered with
  a logistic-regression baseline to bound the neural model's lift.
- Mid-season **drift postmortems** when a model degrades — automated
  trigger, human promotion gate (decision [44] / rule 6).
- **Measured test coverage**, not a vibe: 379 backend tests (JaCoCo report in
  CI), 399 frontend tests (vitest v8 — ~79 % lines / ~67 % branches at this
  writing), plus the training suite with its four required temporal-leakage
  tests. Coverage is published as a non-gating baseline today and ratchets up.
  Every commit also gates on lint, hex-codes, bundle-budget, static a11y, and a
  Schemathesis API-contract check.

## How to try it

The simplest path is the live site above. Local dev:

```bash
# Stateful services (ClickHouse, Prometheus, Grafana)
docker compose -f infra/docker-compose.yml up -d

# Backend (api profile on 8080, worker on 8081)
cd backend && ./gradlew bootRun --args='--spring.profiles.active=api'

# Frontend (Vite on 5173, calls Spring via CORS)
cd ../frontend && npm install && npm run dev

# Training pipeline (Python 3.11+ via uv)
cd ../training && uv sync && uv run pytest
```

## Training the models

Five registry artifacts — three serving models (pre-pitch head, post-pitch
head, batted-ball MLP) plus their two baselines (pitch LR, batted-ball
LGBM). Training runs on the self-hosted desktop only (ADR-0006: it needs
the full 2015–2025 ClickHouse dataset and the GPU); the Mac runs a sampled
iteration loop. **2026 is holdout-only** (rule 13).

**All at once** — from `training/`, the full sequence (feature table →
pitch heads + baselines → batted-ball pipeline):

```bash
# 0. Feature table
uv run python -m bullpen_training.features.tier_1_2   --min-year 2015 --max-year 2025
uv run python -m bullpen_training.features.tier_3_form --min-year 2015 --max-year 2025
# 1–3. Pitch heads + LR baseline (+ ONNX export for the LightGBM heads)
uv run python -m bullpen_training.pitch.production --model lightgbm   # → pitch_outcome_pre
uv run python -m bullpen_training.pitch.production --model post       # → pitch_outcome_post
uv run python -m bullpen_training.pitch.production --model lr         # → LR baseline
# 4–5. Batted-ball MLP + LGBM baseline (retrodict → MLP → calibrators → gate → LGBM → compare)
bash scripts/run_2c_overnight.sh
```

**In sections** — every step above is independent and idempotent, so on a
box that thermal-throttles you run one, let it cool, run the next; the
batted-ball orchestrator is itself sectionable stage-by-stage. The full
procedure — prerequisites, per-stage heat/time table, cooldown cut-points,
gates, and registration — lives in
[`docs/runbooks/training-models.md`](docs/runbooks/training-models.md)
(batted-ball detail in
[`2c-overnight-pipeline.md`](docs/runbooks/2c-overnight-pipeline.md)).

## Design + decisions

Most "obvious" alternatives have been rejected with written rationale —
check before re-litigating:

- [System design](docs/design.md) — every locked technical choice with
  context.
- [Numbered decisions log](docs/decisions.md) — chronological append-only
  flat log.
- [Phased build plan](docs/plan.md) — Phase 0 → Phase 5, soft-cut
  priority list, two-week review cadence.
- [`CLAUDE.md`](CLAUDE.md) — non-negotiable discipline rules.
- ADRs (long-form, top ~15 % of decisions): [`docs/adr/`](docs/adr/)

### Architecture (sketch)

```
  Statcast / MLB Stats API / Weather
                 │
                 ▼
        ┌────────────────┐         ┌─────────────────────┐
        │   ClickHouse   │◀────────│   Training (Python) │
        │ pitches+drift  │         │  rolling-CV → ONNX  │
        └────────┬───────┘         └────────┬────────────┘
                 │                          │
                 ▼                          ▼
        ┌────────────────┐         ┌─────────────────────┐
        │ Spring 3 + JVM │◀────────│   Registry (SQLite) │
        │ ONNX inference │         │  versions · A/B     │
        └────────┬───────┘         └─────────────────────┘
                 │
                 ▼
        React + Mantine + TanStack (this site)
```

A rendered SVG version of the diagram lives on the
[About page](https://thebullpen.net/about).

## Data sources + licensing

Pitch-level data is downloaded from
[Baseball Savant](https://baseballsavant.mlb.com/) via the
[`pybaseball`](https://pypi.org/project/pybaseball/) client. Roster and
game schedule come from the MLB Stats API. Weather joins from a free
meteorology source.

**This project's published outputs (predictions, model artifacts, this
site) are derived analytics for personal research / portfolio purposes.
Underlying play-by-play data is not redistributed.**

## Known limitations

- **Most pages render from committed fixtures, not live data** (v1). Only
  `/games/:id` and the player lookup hit the backend today; the home slate,
  park explorer, Ops dashboard, and About page are design-system showcases
  wired to `frontend/src/data/*-fixtures.ts`. Going live is gated on the MLB
  poller (below) plus the Ops aggregation endpoints, and is a page-level swap.
- The 30-park batted-ball MLP that natively emits 30 outputs in one ONNX
  call is Phase 2c.5 work — until it lands, `/v1/predict/batted-ball
/all-parks` loops the toy inference 30 times (~300 μs total, still well
  under the page render budget).
- Live game polling worker (MLB Stats API client + per-game scheduled
  poll on the worker profile) is wired contractually but not running —
  the controller surface + state machine are in place and tested; the
  producer side (client + poller + the `prediction_log` truth-join) is
  planned in
  [`docs/runbooks/live-data-setup.md`](docs/runbooks/live-data-setup.md)
  and tracked in [#1](https://github.com/Alexm-picard/the-bullpen/issues/1).
- `prediction_log` truth-join to `pitches` by `(game_id, at_bat_index,
pitch_number)` needs the indexed `pitch_id` column to land before the
  per-player history / calibration views populate fully.
- E2E / Playwright + Lighthouse / axe-core CI all defer to Phase 5.x.
  Static linters (hex codes, bundle budget, a11y heuristics) fill the
  gap until then.

## What's next (v1.5)

- 30-park MLP natively serving all-parks predictions
- Truth-join landing for full calibration + agreement views
- MLB Stats API poller wired to `GameStateMachine`
  ([#1](https://github.com/Alexm-picard/the-bullpen/issues/1) ·
  [runbook](docs/runbooks/live-data-setup.md))
- Admin override page wrapping the existing `POST /v1/admin/routing`
  slider behind HTTP Basic
- Hyperparameter search in the retraining job (fixed-HP today per
  decision [81])
- Per-game weather pull replacing the per-park annual default
  atmosphere (Phase 2c.4)

## Operating evidence

- **Drift postmortems** land under
  [`docs/postmortems/`](docs/postmortems/) when a model degrades and the
  human review writes one up. First one is a pre-season **induced-drift
  drill** —
  [`drill-2026-05-30-induced-battedball-drift.md`](docs/postmortems/drill-2026-05-30-induced-battedball-drift.md)
  — that injected a 1σ feature shift + over-confidence and walked the real
  detect → PAGE/NOTICE → human-gated retrain chain end-to-end (PSI 0.912,
  ECE 0.188). Explicitly synthetic; proves the detector has teeth before
  the first real in-season event.
- **Restore + reboot drill reports** under
  [`docs/drills/`](docs/drills/) (rule 8).
- **Hardening sweeps** (Phase 5.5) — running observations in
  [`docs/hardening/observations.md`](docs/hardening/observations.md),
  triaged into dated sweep docs with measured before/after per item. First
  one:
  [`2026-05-30_sweep.md`](docs/hardening/2026-05-30_sweep.md) (11 items —
  CI red→green, 2 Schemathesis-found 500s→400, TS strict 67→0, raw-SQL
  leak 1→0, perf baselines, the drift-chain validation).
- **Hiring readiness** (Phase 6) — deliverables tracked in
  [`docs/hiring/`](docs/hiring/): 60-second verbal pitch, lessons-
  learned doc, OSS contribution targets, recruiter-time-test.

## Repository layout

```
thebullpen/
├── backend/        Java 21 + Spring Boot 3 (Gradle Kotlin DSL)
├── training/       Python 3.11 (uv) — model training, eval, ONNX export
├── frontend/       React 19 + TypeScript + Vite + Mantine 9 + Tailwind 4
├── contracts/      Canonical Python↔Java file contract
├── infra/          docker-compose, Prometheus + Grafana, backup scripts
├── docs/           design.md, plan.md, decisions.md, adr/, drills/, etc.
├── .githooks/      pre-commit (schema_hash discipline)
└── deploy.sh       Phase 0 deploy stub — prefer the deploy-safely skill
```

## Contact

GitHub: [@Alexm-picard](https://github.com/Alexm-picard)
