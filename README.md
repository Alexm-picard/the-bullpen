# The Bullpen

A self-hosted baseball-analytics platform built primarily as a serving
wrapper around three calibrated models. Operates through at least one MLB
season for a real drift postmortem.

- **Live frontend**: https://thebullpen.net/
- **Live Ops dashboard**: https://thebullpen.net/ops
- **About + methodology**: https://thebullpen.net/about
- **Repo**: https://github.com/Alexm-picard/the-bullpen

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
- ~95 % test coverage at this writing: backend 376 tests, frontend 93;
  every commit gates on lint, hex-codes, bundle-budget, static a11y.

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

- The 30-park batted-ball MLP that natively emits 30 outputs in one ONNX
  call is Phase 2c.5 work — until it lands, `/v1/predict/batted-ball
/all-parks` loops the toy inference 30 times (~300 μs total, still well
  under the page render budget).
- Live game polling worker (MLB Stats API client + per-game scheduled
  poll on the worker profile) is wired contractually but not running —
  the controller surface + state machine are in place and tested; the
  poller is a one-class addition.
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
- Admin override page wrapping the existing `POST /v1/admin/routing`
  slider behind HTTP Basic
- Hyperparameter search in the retraining job (fixed-HP today per
  decision [81])
- Per-game weather pull replacing the per-park annual default
  atmosphere (Phase 2c.4)

## Operating evidence

- Drift postmortems land under
  [`docs/postmortems/`](docs/postmortems/) when a model degrades and the
  human review writes one up. Empty today; first lands mid-season.
- Restore + reboot drill reports under
  [`docs/drills/`](docs/drills/) (rule 8).

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
