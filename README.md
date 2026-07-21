# The Bullpen

A self-hosted baseball-analytics platform built primarily as an ML-systems +
serving wrapper (registry, shadow-mode A/B routing, drift jobs, retraining queue) around three
calibrated models, two of which reach a browser today: a batted-ball champion serving live (a
per-park calibrated physics estimate, honest about its reality gap - see the cross-park limitation
below), and the post-pitch champion, whose logged predictions are replayed against realized
outcomes on the game page (decision [177]). The pre-pitch head now has its own next-pitch panel
there; its declared primary was re-aimed from Brier edge to absolute calibration (ECE < 0.02,
passing at 0.0036 - decision [180] / ADR-0014, amending the [154] / ADR-0011 hold), and the panel
renders a clean "model not yet promoted" state until the human-gated promotion lands (rule 6). Built to
operate through at least one MLB season for a real drift postmortem (the in-season postmortem is
pending; a synthetic induced-drift drill stands in for now).

- **Live site**: https://thebullpen.net/
- **Ops dashboard**: https://thebullpen.net/ops
- **About + methodology**: https://thebullpen.net/about
- **Repo**: https://github.com/Alexm-picard/the-bullpen

> **What's live vs. showcase (v1):** most of the app pulls real data from the Spring
> backend - the per-game detail view (`/games/:id`), the player lookup + `/players/:id`
> profile, the `/parks` HR-by-park heatmap, the home tonight slate, and the Ops dashboard's
> Model Fleet / latency / retrain queue / ops log (live via `/v1/ops/*`, with a committed-fixture
> fallback when those are empty or offline). Still pure showcase from `*-fixtures.ts`: the
> `/parks` factor table, the About methodology page, the `/players` landing's Featured Reports +
> Model Standouts, the `/players/:id` slug showcase matchup, and the Ops infra ribbon. The Ops
> drift snapshot is live-wired to `/v1/ops/drift` and renders an honest em-dash skeleton until
> `drift_metrics` fills in.
> See _Known limitations_ for the honest caveats.

## What's interesting about it

- A **custom ML systems wrapper** - model registry, A/B router (shadow-mode),
  drift detection, retraining queue + triggers (dispatch wired for the served
  batted-ball head and **proven end-to-end on real data**: one queued trigger
  retrained the served model on ~1.2M batted balls and registered the candidate
  in 96.8 unattended minutes; the other registry names stay explicit
  UnsupportedModel) - written from scratch in Java rather than pulled in via
  MLflow. The wrapper is the project; the models are the excuse.
- **ONNX Runtime in-process** in Java + Spring Boot 3 - no Python
  sidecar, no live RPC. Training is Python; serving is JVM.
- **Broadcast-graphics design system** (Barlow Condensed / Inter /
  JetBrains Mono, scorebug + lower-third telecast chrome). The product is
  advance-scouting analytics, so it presents as a live-broadcast package,
  not yet-another-SaaS chrome (decision [160], superseding the earlier
  scouting-report identity [133]).
- **Per-model eval artifact** with rolling-origin cross-validation,
  reliability diagrams, calibration metrics - always co-registered with
  a logistic-regression baseline to bound the neural model's lift.
- A **drift -> postmortem** chain (automated trigger, human promotion gate -
  decision [44] / rule 6), validated end-to-end by a synthetic induced-drift
  drill; the first real in-season postmortem is pending.
- **Measured test coverage**, not a vibe: 700+ backend test methods, 600+ Python
  tests (including the four required temporal-leakage tests, checked by fault
  injection - shuffled-target, future-contamination, calendar-date trace,
  id-consistency), and 400+ frontend tests. Line/branch coverage is published
  every CI run and gated on a regression floor (backend JaCoCo under the Docker
  ITs; frontend vitest v8; training coverage.py) - read the current numbers from
  the latest run rather than figures pasted here, which drift. The enforced floors
  live in `backend/build.gradle.kts`, `frontend/vite.config.ts`, and the
  `.github/workflows/training.yml` workflow (the higher `pyproject.toml` figure is
  aspirational / warning-only, not the gate).
  Each surface also gates on lint, hex-codes, bundle-budget, a real axe-core a11y
  gate (color-contrast included since the D4 pass), and a Schemathesis
  API-contract check. These are path-filtered workflows: the frontend gates fire
  on `frontend/**`, the contract fuzz on `backend/**` + `contracts/**`.

## Screenshots

The live site ([thebullpen.net](https://thebullpen.net)):

|                                Tonight's slate                                 |                                         Ops dashboard                                         |
| :----------------------------------------------------------------------------: | :-------------------------------------------------------------------------------------------: |
|   [![Tonight's slate](docs/screenshots/home.png)](docs/screenshots/home.png)   |            [![Ops dashboard](docs/screenshots/ops.png)](docs/screenshots/ops.png)             |
|                                 **Live game**                                  |                                     **Park HR explorer**                                      |
|      [![Live game](docs/screenshots/game.png)](docs/screenshots/game.png)      |   [![Park HR explorer](docs/screenshots/parks-full.jpeg)](docs/screenshots/parks-full.jpeg)   |
|                               **Player search**                                |                                      **Player profile**                                       |
| [![Player search](docs/screenshots/players.png)](docs/screenshots/players.png) | [![Player profile](docs/screenshots/player-ohtani.jpeg)](docs/screenshots/player-ohtani.jpeg) |

## How to try it

The simplest path is the live site above. To run it locally:

```bash
# 1. Stateful services (ClickHouse, Prometheus, Grafana). The ClickHouse container is
#    FAIL-CLOSED since decision [161] - it refuses to start without a password - so seed the
#    env FIRST, then bring the services up via the Makefile target (it renders the ClickHouse
#    users file and passes --env-file). A bare `docker compose up` fails on the required :? vars.
cp infra/.env.example infra/.env      # then set BULLPEN_CLICKHOUSE_PASSWORD + GRAFANA_ADMIN_PASSWORD
make services-up                      # render-users.sh + docker compose --env-file infra/.env up -d

# 2. Backend (api profile on 8080, worker on 8081). The Gradle wrapper lives in backend/, not root.
cd backend && ./gradlew bootRun --args='--spring.profiles.active=api'

# 3. Frontend (Vite on 5173, calls Spring via CORS)
cd ../frontend && npm install && npm run dev

# 4. Training - run the test suite (OMP_NUM_THREADS=1 avoids a macOS libomp segfault)
cd ../training && uv sync && OMP_NUM_THREADS=1 uv run python -m pytest
```

**What works with an empty stack.** The backend boots against an empty ClickHouse with no
registered ONNX model: the predict endpoints return a documented `503` (no live champion, not a
crash), and every read surface (`/v1/ops/*`, players, parks, games) falls back to committed
fixtures - so the SPA and the Ops dashboard render end-to-end before you have any data or models.
Real predictions need the historical Statcast pull plus a trained-and-registered model, which is
the self-hosted box workflow (ADR-0006), not the local quickstart.

## Training the models

Six registry artifacts - three outcome models (pre-pitch head, post-pitch head,
batted-ball MLP; the batted-ball head serves live, the post-pitch champion is
surfaced retrospectively on the game page, the pre-pitch head has a next-pitch
panel awaiting its human-gated promotion) plus three baselines (pitch LR,
batted-ball LGBM per park, batted-ball LR). Training runs on the self-hosted desktop only (ADR-0006: it needs
the full 2015–2025 ClickHouse dataset and the GPU); the Mac runs a sampled
iteration loop. **2026 is holdout-only** (rule 13).

**All at once** - from `training/`, the full sequence (feature table →
pitch heads + baselines → batted-ball pipeline):

```bash
# 0. Feature table - SINGLE STAGE: tier_3_form is a standalone full build that subsumes tier_1_2
uv run python -m bullpen_training.features.tier_3_form --min-year 2015 --max-year 2025
# 1–3. Pitch heads + LR baseline (ONNX export is a separate step per head - see the runbook)
uv run python -m bullpen_training.pitch.production --model lightgbm   # → pitch_outcome_pre
uv run python -m bullpen_training.pitch.production --model post       # → pitch_outcome_post
uv run python -m bullpen_training.pitch.production --model lr         # → LR baseline
# 4–5. Batted-ball MLP + LGBM baseline (retrodict → MLP → calibrators → gate → LGBM → compare)
bash scripts/run_2c_overnight.sh
```

**In sections** - every step above is independent and idempotent, so on a
box that thermal-throttles you run one, let it cool, run the next; the
batted-ball orchestrator is itself sectionable stage-by-stage. The full
procedure - prerequisites, per-stage heat/time table, cooldown cut-points,
gates, and registration - lives in
[`docs/runbooks/training-models.md`](docs/runbooks/training-models.md)
(batted-ball detail in
[`2c-overnight-pipeline.md`](docs/runbooks/2c-overnight-pipeline.md)).

## Design + decisions

Most "obvious" alternatives have been rejected with written rationale -
check before re-litigating:

- [System design](docs/design.md) - every locked technical choice with
  context.
- [Numbered decisions log](docs/decisions.md) - chronological append-only
  flat log.
- [Phased build plan](docs/plan.md) - Phase 0 → Phase 6, soft-cut
  priority list, two-week review cadence.
- [`CLAUDE.md`](CLAUDE.md) - non-negotiable discipline rules.
- ADRs (long-form, top ~15 % of decisions): [`docs/adr/`](docs/adr/)
- [API reference](docs/api/README.md) + the committed
  [OpenAPI snapshot](docs/api/openapi.json) (38 paths / 41 operations; the live spec at
  [`/v3/api-docs`](https://api.thebullpen.net/v3/api-docs) is Schemathesis-fuzzed
  every CI run).

### Architecture

Two views of the ML-systems wrapper. Both are drawn from the code, and both are
deliberately labeled where a lane is gated, partial, or drop-tolerant - a
diagram that implies more than the system does is the same defect as a stale
README claim.

```mermaid
flowchart TD
    SRC["Statcast · MLB Stats API · Weather"]
    TR["Training (Python, off the serving path)<br/>rolling-origin CV -> ONNX + metadata + Parquet"]
    CH[("ClickHouse<br/>pitches · drift · prediction_log")]
    REG[("SQLite registry<br/>versions · A/B routing")]
    API["Spring Boot 3 · api + worker<br/>ONNX Runtime in-process"]
    FE["React + Mantine + TanStack<br/>Vercel SPA"]

    SRC --> CH
    TR -->|"contracts/ · ONNX + feature_pipeline.json"| REG
    TR --> CH
    CH --> API
    REG --> API
    API --> FE
```

### A predict request

```mermaid
flowchart LR
  B["Browser (SPA)"] --> CF["Cloudflare Tunnel"]
  CF --> F1["CorrelationIdFilter<br/>MDC + echo header"]
  F1 --> F2["RateLimitFilter<br/>per-IP token bucket, in-memory"]
  F2 --> C["PredictAllParksController<br/>POST /v1/predict/batted-ball/all-parks"]
  C --> O["PredictionOrchestrator"]
  O --> R["InferenceRouter<br/>+ RoutingService (30s cache)"]
  R --> BK["Bucketer<br/>murmur3(gameId, modelName)"]
  BK -->|CHAMPION| M["ModelLoader -> LoadedAllParksModel<br/>ONNX Runtime, in-process"]
  BK -.->|"CHALLENGER / SHADOW<br/>(virtual-thread executor)"| M
  M --> CAL["Per-park isotonic calibrators"]
  CAL --> RESP["JSON response"]
  O -.->|"async, non-blocking offer"| AL["AsyncPredictionLogger<br/>bounded queue, drops counted"]
  AL -.-> PLW["PredictionLogWriter"]
  PLW -.-> CH[("ClickHouse<br/>prediction_log")]
  R --> SQ[("SQLite<br/>model_routing")]

  classDef gated stroke-dasharray: 4 3
  class AL,PLW,CH gated
```

The pitch lane (`POST /v1/predict/pitch?head=pre|post`) is the same shape
through `PitchPredictionService`, which is a deliberately separate copy of that
skeleton rather than a shared orchestrator. `POST /v1/simulate/**` is neither
routed nor logged: it pins one artifact on purpose as an unrouted diagnostic
(decision [176]). Prediction logging is best-effort by design - the queue drops
under saturation rather than adding latency to the response, and the writer bean
does not exist at all when ClickHouse is disabled.

### The model lifecycle

```mermaid
flowchart TD
  T["Python training<br/>LightGBM / MLP + LR baseline"] --> EV["Rolling-origin CV<br/>4 folds, never random splits"]
  EV --> EVD["Promotion evidence JSON<br/>pre-declared criteria (rule 5)"]
  T --> SNAP["Snapshot: model.onnx + metadata.json<br/>+ feature_pipeline.json + parquet"]
  SNAP --> REG["POST /v1/admin/registry/.../register<br/>ROLE_ADMIN"]
  REG --> HASH{"Feature schema hash<br/>matches /contracts? (rule 7)"}
  HASH -->|no| FAIL["HARD FAIL"]
  HASH -->|yes| SHADOW["Registry stage: SHADOW<br/>(SQLite)"]
  EVD --> GATE
  SHADOW --> GATE{"Promotion gate<br/>evidence row + guardrails"}
  GATE -->|"operator POST, human-gated (rule 6)"| CHAMP["CHAMPION - serves users"]
  CHAMP --> PL[("prediction_log")]
  PL --> DJ["Nightly drift jobs<br/>PSI feature / PSI prediction / calibration"]
  DJ --> DM[("drift_metrics")]
  DM --> AL2["DriftAlertEvaluator -> Discord"]
  DM --> DT{"DriftTrigger<br/>CALIBRATION_ERROR only"}
  DT --> Q["Retraining queue (SQLite)"]
  Q --> DISP["Dispatch adapter"]
  DISP -->|"battedball_outcome"| T
  DISP -.->|"other 5 names"| UNS["UnsupportedModel<br/>(explicit, not silent)"]

  classDef stop fill:none,stroke-dasharray: 4 3
  class UNS,AL2 stop
```

Two honest details the diagram encodes rather than hides. **Feature PSI is
terminal at the alert**: only calibration error can enqueue a retrain, so a PSI
spike pages a human and stops there. And **dispatch is one model deep** -
`battedball_outcome` retrains end-to-end (proven once, unattended, in 96.8
minutes), while the other five registry names raise an explicit
`UnsupportedModel` rather than pretending to be wired. Promotion is never
automated: the only path to CHAMPION is an authenticated operator call.

## Data sources + licensing

The code in this repository is released under the [MIT License](LICENSE). That
covers the code only - it grants no rights to the underlying MLB data, whose
terms are separate (see below).

Pitch-level data is downloaded from
[Baseball Savant](https://baseballsavant.mlb.com/) via the
[`pybaseball`](https://pypi.org/project/pybaseball/) client. Roster and
game schedule come from the MLB Stats API. Weather joins from a free
meteorology source.

**This project's published outputs (predictions, model artifacts, this
site) are derived analytics for personal research / portfolio purposes.
Underlying play-by-play data is not redistributed.**

## Known limitations

- **Live vs. showcase is now mixed** (v1). Hitting the backend live: `/games/:id`,
  the player lookup + `/players/:id` profile (recent predictions + reliability
  diagram), the `/parks` HR-probability-by-park heatmap, the home page's tonight
  slate, and the Ops dashboard's Model Fleet / latency / retrain queue / ops log
  (live via `/v1/ops/*`, falling back to committed fixtures only when those return
  empty or the backend is offline). Still pure showcase from
  `frontend/src/data/*-fixtures.ts`: the `/parks` factor table, the About
  methodology page, the `/players` Featured Reports + Model Standouts, the
  `/players/:id` slug showcase matchup, and the Ops infra ribbon. The Ops drift
  snapshot is NOT in that list: it is live-wired to `GET /v1/ops/drift`, its
  watched-surface rows render em-dashes until real `drift_metrics` values land,
  and drill-tagged rows are labeled so a synthetic PSI spike is never shown as
  organic.
- **Cross-park batted-ball fidelity is a known limitation.**
  `/v1/predict/batted-ball/all-parks` is served by the registered batted-ball
  champion across the 30 parks. The ball-flight physics validation passes (bias
  -0.14 ft, 93 % of fixtures within tolerance) - but that is **still-air carry
  reconstruction**, not real-weather cross-park fidelity. The cross-park HR-ordering
  sanity gate does **not** pass yet: predicted per-park HR rates correlate only
  Spearman rho 0.333 with the observed park-factor ordering (the physics labels
  themselves only reach ~0.30, so the model faithfully reproduces weak labels - decision [141]) (interim gate target
  observed-normalized rho >= 0.65 per decision [140]; and it is now a non-blocking
  diagnostic, not a registration blocker - registration gates on per-park outcome
  ECE instead, [141]). The
  away-park counterfactual currently uses ADR-0010's **still-air interim**
  (destination seasonal temp/density + altitude, **no wind**); the real per-date
  weather + wind backfill (`park_daily_weather`) that should lift the ordering is
  staged but not yet shipped. Treat per-park batted-ball numbers as directional, not
  calibrated, until that gate is green - see
  [`docs/cross-park-fidelity-plan.md`](docs/cross-park-fidelity-plan.md). The `/parks` heatmap and
  the About page surface this physics-estimate framing (and the rho gap) directly to users rather
  than presenting raw P(HR) as fact (decision [163]).
- **Automated retraining is wired AND proven on real data** (BOX HAND-OFF #1,
  2026-07-15). A manually-enqueued trigger drove the full production path
  unattended: claim -> 11 per-year ClickHouse loads (~1.2M batted balls;
  train 2015-2024, calibration on held-out 2025, the 2026 holdout untouched)
  -> GPU training of the served model + carry head -> single-file ONNX export
  -> per-park isotonic calibration -> registered as `battedball_outcome` v3
  CANDIDATE, in **96.8 minutes with zero interventions**. Calibration quality
  on val-2025 (n=123,345): mean per-park ECE 0.00587 -> **0.00058**
  post-calibration (10x), 30/30 parks improved. The rule-7 schema-hash gate
  passed at registration and the candidate was NOT promoted - promotion stays
  human-gated (rule 6). Getting there took 16 attempts across 6 distinct root
  causes, each fixed in code with regression tests and exercised in the
  winning run - the full ledger is written up as an ops story in
  [`docs/postmortems/2026-07-15_c31-retrain-saga.md`](docs/postmortems/2026-07-15_c31-retrain-saga.md).
  Remaining on this lane: the unattended systemd-timer path (the manual
  ceremony is the proven one). On the drift side, all three signals are real
  and ClickHouse-backed: PSI-on-predictions, calibration-vs-observed-outcome,
  and per-_feature_ PSI (a real fetcher reads `prediction_log.features`
  against a training-time baseline the E-1 backfill CLI emits; a champion
  promoted without one trips a loud `DriftBaselineMissing` alert rather than
  silently skipping) - and the categorical-PSI fix was proven discriminating
  on live organic traffic 2026-07-10.
- Live game polling worker (MLB Stats API client + parser + per-game scheduled
  poll on the worker profile, feeding `pitches_live` and the `prediction_log`
  truth-join) is **built, merged, and unit-tested**, and is enabled in prod
  behind the `BULLPEN_INGEST_LIVE_ENABLED` runtime flag. First-feed operating
  evidence against the real MLB feed is the remaining gate, tracked in
  [#1](https://github.com/Alexm-picard/the-bullpen/issues/1)
  ([runbook](docs/runbooks/live-data-setup.md)).
- The `prediction_log` truth-join to `pitches_live` by `(game_id,
at_bat_index, pitch_number)` is implemented and feeds the nightly calibration
  - per-segment drift jobs; per-player history / calibration views populate as
    live shadow predictions accrue.
- Playwright e2e covers the live pages (both the populated and the empty
  pitch-log states) and runs a real axe-core accessibility gate in CI; static
  linters (hex codes, bundle budget) run alongside. Lighthouse performance
  budgets are the remaining CI add.

## What's next (v1.5)

- Cross-park batted-ball fidelity: get the per-park HR-ordering diagnostic
  green (observed-normalized Spearman rho >= 0.65, interim per decision [140]) -
  see `docs/cross-park-fidelity-plan.md`
- First-feed operating evidence for the live poller against the real MLB feed
  ([#1](https://github.com/Alexm-picard/the-bullpen/issues/1) ·
  [runbook](docs/runbooks/live-data-setup.md))
- Hyperparameter search in the retraining job (fixed-HP today per
  decision [81])
- Per-game weather pull replacing the per-park annual default
  atmosphere (Phase 2c.4)

## Operating evidence

- **Drift postmortems** land under
  [`docs/postmortems/`](docs/postmortems/) when a model degrades and the
  human review writes one up. The flagship Phase-6 artifact is the
  honestly-labeled synthetic **induced-drift drill** (decision [175]) -
  [`2026-07-16_induced-drift-drill.md`](docs/postmortems/2026-07-16_induced-drift-drill.md)
  - which injected a known shift, watched detection fire, and walked the real
    detect → PAGE/NOTICE → human-gated response chain end-to-end. Explicitly
    synthetic; it proves the detector has teeth before the first real in-season
    event (a confirmed natural event supersedes it, [169]). An earlier pre-season
    drill
    [`drill-2026-05-30-induced-battedball-drift.md`](docs/postmortems/drill-2026-05-30-induced-battedball-drift.md)
    (PSI 0.912, ECE 0.188) and the first-organic-PSI triage
    [`2026-07-16_first-organic-psi-triage.md`](docs/postmortems/2026-07-16_first-organic-psi-triage.md)
    round out the drift ledger.
- **Restore + reboot drill reports** under
  [`docs/drills/`](docs/drills/) (rule 8).
- **Hardening sweeps** (Phase 5.5) - running observations in
  [`docs/hardening/observations.md`](docs/hardening/observations.md),
  triaged into dated sweep docs with measured before/after per item. First
  one:
  [`2026-05-30_sweep.md`](docs/hardening/2026-05-30_sweep.md) (11 items -
  CI red→green, 2 Schemathesis-found 500s→400, TS strict 67→0, raw-SQL
  leak 1→0, perf baselines, the drift-chain validation).
- **Hiring readiness** (Phase 6) - deliverables tracked in
  [`docs/hiring/`](docs/hiring/): 60-second verbal pitch, lessons-
  learned doc, OSS contribution targets, recruiter-time-test.

## Repository layout

```
thebullpen/
├── backend/        Java 21 + Spring Boot 3 (Gradle Kotlin DSL)
├── training/       Python 3.11 (uv) - model training, eval, ONNX export
├── frontend/       React 19 + TypeScript + Vite + Mantine 9 + Tailwind 4
├── contracts/      Canonical Python↔Java file contract
├── infra/          docker-compose, Prometheus + Grafana, backup scripts
├── docs/           design.md, plan.md, decisions.md, adr/, drills/, etc.
├── .githooks/      pre-commit (schema_hash discipline)
└── deploy.sh       real WSL2 deploy (clean-tree guard, atomic symlink swap,
                    health smoke + rollback, release tag) - prefer deploy-safely
```

## Contact

GitHub: [@Alexm-picard](https://github.com/Alexm-picard)
