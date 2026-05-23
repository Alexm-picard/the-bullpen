# Design Document

> **Project**: The Bullpen (`thebullpen.net`) — A baseball analytics + ML systems platform
> **Author**: Alex Picard
> **Status**: Pre-implementation; all decisions locked through planning session
> **Last updated**: 2026-05-09

---

## Table of Contents

1. [Project Frame](#1-project-frame)
2. [System Overview](#2-system-overview)
3. [The ML Systems Wrapper](#3-the-ml-systems-wrapper)
   - 3.1 [Model Registry](#31-model-registry)
   - 3.2 [A/B Routing](#32-ab-routing)
   - 3.3 [Drift Detection](#33-drift-detection)
   - 3.4 [Retraining Triggers](#34-retraining-triggers)
4. [The Models](#4-the-models)
   - 4.1 [Pitch Outcome Model](#41-pitch-outcome-model)
   - 4.2 [Batted-Ball / Park-Effect Model](#42-batted-ball--park-effect-model)
   - 4.3 [Forward Simulation](#43-forward-simulation)
   - 4.4 [Eval Methodology](#44-eval-methodology)
5. [Data Pipeline](#5-data-pipeline)
6. [Backend (Java + Spring)](#6-backend-java--spring)
7. [Frontend (React)](#7-frontend-react)
8. [Design System](#8-design-system)
9. [Operations & Deployment](#9-operations--deployment)
10. [Rejected Alternatives](#10-rejected-alternatives)
11. [v1.5 Roadmap](#11-v15-roadmap)

---

## 1. Project Frame

### What this project is

A self-hosted baseball analytics platform with a custom-built ML systems
wrapper. Three trained, calibrated, versioned, drift-monitored ML models
serve real-time predictions during the MLB season. The platform is operated
through at least one full season, with a documented mid-season drift
postmortem as a centerpiece artifact.

### What this project is for

A FAANG-credible portfolio piece in the ML/SD engineering hiring track.
Specifically constructed to demonstrate skills not covered by the prior
StudyForesight project: structured ML on tabular data, ML systems
engineering (registry, A/B, drift, retraining), and operating a self-hosted
production system through real time-series data.

### What this project is not

- A SaaS product or business
- A betting / handicapping tool (rejected as resume-negative and regulatorily exposed)
- A general baseball stats site competing with Baseball Savant
- A research contribution

### Constraints

- **Solo developer.** All design choices favor maintainability over
  sophistication when the two conflict.
- **Free to operate.** Self-hosted on personal hardware; cloud usage limited
  to free tiers (Vercel, Cloudflare incl. R2 for object storage).
- **Time-bounded.** ~8–10 months calendar at 12–15 hours/week sustainable.

---

## 2. System Overview

### Hardware

- **Application host**: Personal desktop (Ryzen 7 7800X3D + RTX 4070 Super,
  Windows 11 host with WSL2 Ubuntu 24.04 LTS for all services)
- **Edge**: Cloudflare (DNS, Tunnel, free tier)
- **Frontend host**: Vercel (free tier)
- **Backups + model artifacts**: Cloudflare R2 (free tier covers Phase-0 traffic; $0 egress; vendor-consolidated with Tunnel + DNS per decision [128] / ADR-0007)

### Software

| Layer               | Technology                          | Notes                                                      |
| ------------------- | ----------------------------------- | ---------------------------------------------------------- |
| Backend language    | **Java 21**                         | Locked. Virtual threads enabled.                           |
| Backend framework   | **Spring Boot 3.x**                 | One JAR, two profiles (`api`, `worker`), two systemd units |
| Training language   | **Python 3.11+**                    | Off the serving path; ONNX export only                     |
| Inference           | **ONNX Runtime Java**               | In-process, no Python sidecar                              |
| Analytical DB       | **ClickHouse**                      | Pitches, drift metrics, prediction logs                    |
| App state DB        | **SQLite**                          | Model registry, A/B config, retraining queue               |
| Frontend            | **React + TypeScript + Vite**       | Pure SPA                                                   |
| Component library   | **Mantine + Tailwind**              | Editorial-data design system                               |
| Process management  | **systemd**                         | Inside WSL2                                                |
| Observability       | **Prometheus + Grafana + Actuator** | Local dashboards                                           |
| External monitoring | **Uptime Robot + Healthchecks.io**  | Uptime + heartbeat                                         |
| Alerting            | **Discord webhook**                 | Acts as durable incident log                               |

### Data sources (final, locked)

1. **MLB Stats API** — live game state and pitch-by-pitch during games.
   Free, no auth, undocumented but stable rate limits (~2 req/sec safe).
2. **pybaseball / Baseball Savant** — historical Statcast bulk data,
   2015–2025. One-time backfill plus nightly increments.
3. **Open-Meteo** — weather data. Forecast endpoint (~30 min before
   first pitch) for live predictions; archive endpoint (~1 hour after game)
   for training data.
4. **Static park dimensions** — wall heights, distances, foul territory
   from MLB published data.

ESPN explicitly rejected as a backup data source (ToS-questionable,
unstable, undocumented).

### High-level architecture

```
                                 Cloudflare
                              ┌──────────────┐
                              │ Tunnel + DNS │
                              └──────┬───────┘
                                     │
                  ┌──────────────────┼──────────────────┐
                  │                  │                  │
                  ▼                  ▼                  ▼
         ┌───────────────┐  ┌───────────────┐  ┌───────────────┐
         │   Vercel      │  │  thebullpen   │  │   thebullpen  │
         │   (frontend)  │  │   /api        │  │   /api/admin  │
         └───────────────┘  └───────┬───────┘  └───────┬───────┘
                                    │                  │
                                    └─────────┬────────┘
                                              │
                            WSL2 / Ubuntu 24.04 LTS / desktop
                                              │
              ┌───────────────────────────────┼───────────────────────────────┐
              │                               │                               │
              ▼                               ▼                               ▼
       ┌─────────────┐                ┌─────────────┐                 ┌─────────────┐
       │ Spring API  │                │   Spring    │                 │ ClickHouse  │
       │  (systemd)  │                │   Worker    │                 │  (Docker)   │
       │             │                │  (systemd)  │                 │             │
       └──────┬──────┘                └──────┬──────┘                 └──────┬──────┘
              │                              │                               │
              └──────────────────┬───────────┴───────────────────────────────┘
                                 │
                                 ▼
                        ┌────────────────┐
                        │    SQLite      │
                        │   (registry,   │
                        │   queue, A/B)  │
                        └────────────────┘
```

---

## 3. The ML Systems Wrapper

Four subsystems form the wrapper. Each one is a real ML platform problem
solved at appropriate scope for one engineer. Together they are the
single largest source of FAANG-grade signal in the project.

### 3.1 Model Registry

**Purpose**: enable safe, auditable, rollback-able changes to models that
are already serving traffic.

**Architecture**: SQLite-backed registry, custom-built in Spring (NOT MLflow).
Custom-built was chosen explicitly because the design choices themselves
are part of the resume signal; using MLflow would reduce the registry to
"an integration."

**Schema** (key tables):

```sql
CREATE TABLE model_versions (
    id INTEGER PRIMARY KEY,
    model_name TEXT NOT NULL,
    version TEXT NOT NULL,
    artifact_path TEXT NOT NULL,
    metadata_path TEXT NOT NULL,
    training_data_hash TEXT NOT NULL,
    training_data_window TEXT NOT NULL,
    feature_schema_hash TEXT NOT NULL,
    eval_metrics TEXT NOT NULL,    -- JSON
    trained_at TIMESTAMP NOT NULL,
    promoted_at TIMESTAMP,
    stage TEXT NOT NULL,           -- candidate / shadow / champion / archived
    created_by TEXT,
    notes TEXT,
    UNIQUE(model_name, version)
);

CREATE TABLE model_routing (
    id INTEGER PRIMARY KEY,
    model_name TEXT NOT NULL,
    champion_version_id INTEGER REFERENCES model_versions(id),
    challenger_version_id INTEGER REFERENCES model_versions(id),
    challenger_traffic_pct REAL NOT NULL DEFAULT 0,
    updated_at TIMESTAMP NOT NULL
);
```

**Four-stage lifecycle**:

1. **Candidate** — registered, eval'd, no live traffic.
2. **Shadow** — receives mirror copies of every prediction request;
   responses logged but never returned to users. Critical for catching
   regressions before promotion.
3. **Champion** — actually serving live traffic.
4. **Archived** — superseded; kept for rollback and lineage.

**Feature schema hashing** (non-optional): on registration, the hash of
the feature schema (column names, dtypes, preprocessing parameters) is
verified against the production feature pipeline. Mismatched models are
refused. This prevents the dominant production-ML failure mode where
features silently change between training and serving.

**Training data versioning**: full Parquet snapshots stored under each
model version's artifact directory. Choice of Option B (full snapshot)
over Option A (hash + windowed pull) was deliberate: MLB historical data
is mutable (corrections happen weeks after games), so metadata-only
versioning is insufficient for bitwise reproducibility. Cumulative storage
~60GB after 3 years; <$1/month off-site.

**Artifact directory layout**:

```
models/
└── pitch_outcome_pre/v17/
    ├── model.onnx
    ├── calibrator.json
    ├── metadata.json
    ├── feature_pipeline.json
    └── training_data.parquet
```

### 3.2 A/B Routing

**Purpose**: safely evaluate candidate models against the champion under
production conditions.

**Default mode: shadow.** Both champion and challenger run on every
request; only champion's response is returned. Shadow is the right default
because (a) the models have no feedback loop into future inputs (a pitch
prediction doesn't change what the next pitch is), so paired comparisons
are statistically dominant, and (b) no user is ever exposed to a candidate
that hasn't passed promotion criteria.

**Real A/B available** for cases where shadow can't answer the question.
Bucketing: Murmur3 hash on `game_id` (or session cookie when no game
context), 1000 buckets, challenger gets `[0, traffic_pct × 10)`.
Stickiness: deterministic, no client-side state.

**Ramping**: manual stepwise — 1% → 5% → 25% → 50%. Implemented as a
single `traffic_pct` slider; the ramp is just bumping the number with
metric checks between bumps.

**Pre-declared promotion criteria** (the discipline that distinguishes
real ML platform engineering from "I built an A/B test"):

Before any experiment, write down:

- Primary metric (e.g., Brier score on shadow predictions)
- Sample size (e.g., 50,000 paired predictions)
- Threshold (e.g., challenger must beat champion by ≥ 0.005 Brier)
- Guardrails (no regression on calibration error, per-class log loss, p99 latency)

Promotion requires a row in `experiment_results` showing criteria were
met. No promotion without a passing experiment record. This prevents
peeking and noise-driven promotions.

**Logging**: every prediction (champion + shadow + challenger) logged to
ClickHouse `prediction_log`, partitioned by month, joined to outcomes
table at query time (NOT updated in place — ClickHouse mutations are
expensive).

```sql
CREATE TABLE prediction_log (
    request_id UUID,
    request_at DateTime64(3),
    model_name String,
    model_version_id UInt32,
    role Enum('champion', 'challenger', 'shadow'),
    feature_hash String,
    features String,
    prediction String,
    latency_ms Float32
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(request_at)
ORDER BY (model_name, request_at);
```

Async batched writes from Spring; queue overflow drops with warning log
(don't backpressure the request path).

### 3.3 Drift Detection

**Purpose**: detect when reality has diverged from the model's training
distribution, distinguishing types of drift and reacting appropriately.

**Three drift types tracked separately** (most projects conflate; we don't):

| Type                 | What it means                     | Detection                                               |
| -------------------- | --------------------------------- | ------------------------------------------------------- |
| **Data drift**       | Features change distribution      | PSI per feature (continuous), chi-squared (categorical) |
| **Prediction drift** | Output distribution changes       | PSI on predictions vs. training holdout                 |
| **Concept drift**    | Input→output relationship changes | Calibration error / Brier on observed outcomes          |

**Cadence**:

- **Daily batch** (after games end, ~2 AM ET): PSI on features, PSI on
  predictions, calibration on yesterday's known-outcome predictions.
- **Weekly batch** (Sunday night): per-segment metrics (handedness, park,
  count state, pitch type) + long-window comparisons (7d / 28d / season-to-date)
  - outcome rate vs. predicted rate by segment.

**Alerting policy** (tighter than the logging policy on purpose, to
prevent alert fatigue):

- **Page**: champion calibration error exceeds 1.5× training calibration
  for 3+ consecutive days.
- **Notice**: any feature's PSI exceeds 0.25 for 7+ days.
- **Logged-only**: everything else, visible on Ops dashboard, reviewed weekly.

Page-worthy alerts fire to Discord webhook. Discord chosen because the
alert log itself becomes durable incident documentation — useful for
postmortems.

**Storage**: ClickHouse `drift_metrics` time-series table.

```sql
CREATE TABLE drift_metrics (
    computed_at DateTime,
    model_name String,
    model_version_id UInt32,
    metric_type Enum('psi_feature', 'psi_prediction', 'brier',
                     'calibration_error', 'segment_brier'),
    feature_or_segment String,
    metric_value Float64,
    sample_size UInt32,
    window_start DateTime,
    window_end DateTime
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(computed_at)
ORDER BY (model_name, model_version_id, metric_type, computed_at);
```

**Synthetic drift tests** (in CI): inject known feature distribution
shifts and known prediction perturbations, verify detector fires within
expected thresholds. The tests prove the detector works before relying
on it in production.

**Mid-season drift postmortem** is the centerpiece resume artifact. The
project explicitly commits to operating the system through the 2026
season (and into 2027 if launch slips), capturing at least one real
drift incident with linked dashboards and actual numbers.

### 3.4 Retraining Triggers

**Purpose**: keep models fresh without papering over interesting drift
signals.

**Hybrid trigger design**:

1. **Scheduled** (the floor): retrain monthly regardless of drift.
2. **Drift-based** (the ceiling): if calibration error exceeds 1.5×
   training calibration for 7+ days, queue retrain. _Note: tighter than
   the alerting threshold (3 days) — the 4-day gap is the human
   investigation window._
3. **Manual**: button on Ops dashboard for human-in-the-loop retrains.

All three write to the same `retraining_queue` table. One Python
retraining job services all entry points. One pipeline, three triggers.

**No auto-promotion.** Retraining is automated; promotion to shadow then
champion remains manual + criteria-gated. The whole point of pre-declared
promotion criteria is that a human checks them.

**Hyperparameter handling**: fixed within a retrain run. HP search is a
separate v1.5 "explore" phase, not part of routine retrains.

**Idempotency**: retraining queue rows have unique constraints; the
worker handles duplicate triggers gracefully; partial failures don't
leave half-trained models in the registry (atomic registration).

---

## 4. The Models

### 4.1 Pitch Outcome Model

**Prediction target**: given pre-pitch state of the world, output a
probability distribution over 5 coarse classes:

- ball / called_strike / swinging_strike / foul / in_play

**Rationale for coarse classes**: BIP outcomes (single/double/triple/HR/out)
depend on post-contact features the pitch model can't see; clean
factorization hands them to the batted-ball model. Forward simulation
for "pitches per PA" works perfectly with coarse output.

**Two heads, two separate models**:

- **Pre-pitch head**: count, state, identity, recent form features only.
  Used at live inference time. Lower accuracy, real product.
- **Post-pitch head**: above + actual pitch type, velocity, location,
  movement. Used for replay analysis and historical eval. Higher accuracy.

Two completely separate model versions in the registry, not one model
with feature masking. Reasoning: cleaner eval per head; better drift
story; honest separate metrics; the platform is built for N models, so
the marginal infrastructure cost is small.

**Feature tiers** (ordered by leakage subtlety):

- **Tier 1 (trivially safe)**: count, outs, inning, base state, score
  diff, pitcher×batter handedness, park, date.
- **Tier 2 (identity, target-encoded)**: pitcher_id, batter_id via
  rolling target encoding with strict pre-game cutoff. ~700 active
  pitchers/season; target encoding chosen over one-hot or embeddings
  because LightGBM handles this cleanly.
- **Tier 3 (recent form, leakage-prone)**: pitcher's last-N pitches,
  pitch count in game, days since last appearance, season-to-date stats
  (cutoff at game date − 1), batter recent form. All computed via
  streaming temporal cutoff.
- **Tier 4 (post-pitch only)**: pitch type, velocity, release point,
  spin, plate location, movement.

**Deferred features** (v1.5): catcher, umpire, sequence (previous N
pitches), batter slumps, weather. Catcher and umpire become important
for the v1.5 ABS challenge model.

**Architecture**: **LightGBM (multinomial)** with native categorical
handling for pitcher_id and batter_id, target encoding for other
high-cardinality features, isotonic calibration per class on a temporal
holdout.

**Logistic regression baseline** trained alongside, registered as a
permanent reference model. Functions: floor for relative claims,
diagnostic for drift, smoke test for feature pipeline.

**v1.5 challenger**: structured-data transformer (NOT an LLM — see
Rejected Alternatives). Trained, registered as candidate, evaluated via
shadow mode against LightGBM champion. Whether it wins or loses, the
comparison is publishable as a distinguishing artifact.

### 4.2 Batted-Ball / Park-Effect Model

**Prediction target**: given Statcast launch parameters and atmospheric
conditions, output 30 probability distributions simultaneously — one
per MLB park — over outcomes (out / single / double / triple / HR).

**Architecture choice: Option B (multi-output)** over Option A
(park-conditional). Rationale:

1. Single inference call yields all 30 parks (1× vs. 30× latency).
2. Per-park output heads naturally accept per-park calibration.
3. Avoids confounding (pitchers don't randomly distribute across parks).
4. Resume framing: "multi-output structured prediction" is stronger.

**Network**: small MLP, ~50K parameters total.

```
Input features (~15)
  → Shared MLP backbone: [Dense(64) → ReLU → Dense(64) → ReLU]
  → 30 parallel output heads: [Dense(N_OUTCOMES)]
  → Per-park softmax → 30 probability distributions
```

Shared backbone learns common physics; per-park heads learn park geometry.
Multi-task structure regularizes the backbone; small parks benefit from
large parks' data through shared layers.

**Training labels: physics-retrodiction (Path A)**, locked. _This was
originally deferred to v1.5 but escalated to v1 after the user pushed
back on naive per-park training._

Pipeline:

1. **Ball-flight simulator** (~200 lines Python): Alan Nathan's published
   drag/Magnus equations, RK4 integration, validated by reproducing 100
   known Statcast trajectories within tolerance before any training run.
2. **Park geometry data**: wall heights, distances, foul territory from
   MLB published sources.
3. **For every BIP**: simulator runs 30 times (once per park), with the
   ball's launch parameters + park atmospheric conditions + park geometry,
   producing 30 probabilistic outcomes that become training labels.
4. **MLP trains** on retrodicted labels — every output head sees every
   batted ball, ~3M effective samples per head vs. ~10K naive.

The MLP learns the _residual_ between physics prediction and observed
reality (wind gusts, bullpen door deflections, fielder positioning, etc.).
Physics handles the deterministic part; ML handles the stochastic part.

**LightGBM Option-A baseline**: park-as-categorical, single model.
Registered for direct comparison. If LightGBM wins, the architecture
story changes. If MLP wins (more likely), the comparison validates the
multi-output design.

**Features (~15)**:

- Statcast: exit velocity, launch angle, spray angle, hit distance
- Batter: handedness, sprint speed
- Game state: baserunners, outs
- Environmental: temperature, wind speed/direction, humidity, altitude

**NOT a feature**: park geometry (the whole point of multi-output is that
output heads learn this implicitly). Pitcher identity, catcher, umpire
also excluded — once contact is made, the pitcher is largely irrelevant.

**Calibration**: 30 isotonic regressions, one per park output head,
fitted on temporal holdout.

**Cross-park sanity tests**: a 110mph / 28° launch angle ball must show
HR probability monotonically related to mean park HR rate at those
parameters. If model says Comerica > Yankee Stadium for HR likelihood
at given parameters, something is wrong.

### 4.3 Forward Simulation

**Purpose**: derive plate-appearance-level statistics (E[pitches per PA],
P(walk), P(K), P(BIP), full PA-length distribution) from per-pitch model
outputs without training a separate model.

**Method**: PA modeled as Markov chain through 12 count states (0-0
through 3-2) + 3 absorbing states (BB, K, BIP). Per-pitch model provides
transition probabilities. Two implementations:

1. **Analytical** (production endpoint): 15×15 transition matrix,
   fundamental matrix `(I − Q)⁻¹` gives expected steps. Microseconds, exact.
2. **Monte Carlo** (diagnostic endpoint): N=10,000 forward rolls. Provides
   full distribution over PA length, not just mean.

Both implemented; convergence test (MC mean → analytical) catches bugs
in either.

**Half-inning extension** deferred to v1.5: continue the chain through
outs to compute "P(scoring this inning)." Useful, low marginal effort,
but not v1.

### 4.4 Eval Methodology

**Split strategy: rolling-origin temporal cross-validation, 4 folds**:

| Fold | Train     | Validation | Test |
| ---- | --------- | ---------- | ---- |
| 1    | 2015–2020 | 2021       | 2022 |
| 2    | 2015–2021 | 2022       | 2023 |
| 3    | 2015–2022 | 2023       | 2024 |
| 4    | 2015–2023 | 2024       | 2025 |

Reported metrics: **mean ± std-dev across the 4 folds**. The variance
itself is a metric — high variance signals model fragility to era changes.

**Within-fold split granularity**: by date, NEVER by game or pitch.
Within-game pitch-level shuffling leaks game effects (umpire, weather,
lineup specifics).

**Primary metrics**:

- Brier score (multi-class) — best single number for calibrated probabilistic models
- Multi-class log loss (cross-entropy)
- Expected Calibration Error (ECE)

**Secondary / segment metrics**:

- Per-class Brier and log loss
- Confusion matrix at argmax
- Per-segment metrics: handedness, park, count state, inning,
  **month-of-season** (catches within-season non-stationarity)

**Reliability diagrams** (per-class, post-calibration) published in
every model_version's eval artifact.

**Eval artifact directory** (per model_version):

```
eval/
├── metrics.json                # primary + secondary metrics, machine-readable
├── reliability_diagrams.png    # per-class calibration plots
├── confusion_matrix.png        # at argmax
├── segment_metrics.csv         # by every reported segment
├── temporal_cv_results.csv     # per-fold metrics
├── feature_importance.csv      # for tree models
├── commit_sha.txt              # git commit that generated artifacts
└── data_hash.txt               # hash of training snapshot
```

Generated automatically by training pipeline. Surfaced on Ops dashboard.
This artifact is what makes "calibrated" a defensible claim.

**Leakage tests in CI** (4 categories, non-negotiable):

1. **Future contamination**: corrupt the future, verify pipeline
   doesn't see it
2. **Shuffled-target**: train on shuffled labels, verify test Brier
   approaches random-guess floor
3. **Calendar-date holdout**: manually trace 10 random pitches, verify
   every feature value uses only data strictly before pitch date
4. **ID-based feature consistency**: same pitcher_id with same pre-pitch
   history yields same target encoding regardless of position in dataset

---

## 5. Data Pipeline

### Three pipelines (LOCKED — they share schemas, not code)

1. **Historical backfill** (one-time): pybaseball pulls 2015–2024 →
   `raw_statcast`. Idempotent. Runs in monthly chunks for memory.
2. **Nightly incremental** (~3 AM ET): yesterday's corrected Statcast
   data via pybaseball; canonical write to `pitches`. Idempotent on date.
3. **Live polling** (during games): MLB Stats API every 10–15s; writes
   to separate `pitches_live` table.

These pipelines have different failure semantics, idempotency models,
and consumers. Unifying them was rejected — combined pipelines mean a
fix to one risks regressions in others.

### Three storage layers (medallion-lite)

```
raw_statcast (immutable, full Statcast schema, partitioned by month)
    ↓
pitches (cleaned, typed, our schema, deduplicated via ReplacingMergeTree)
    ↓
features (feature pipeline output, computed nightly + on-demand)
    ↓
training_data.parquet snapshots (per-retrain, immutable, hashed)
```

Three named layers because: raw layer is your truth (re-derive cleaned
without re-pulling internet); cleaned layer is your stable contract
(downstream consumers don't depend on raw); features layer is your
training input (versioned alongside model registry's feature_schema_hash).

### Live data architecture

`pitches_live` is a separate table (not a flag in `pitches`). Live data
is sparse (no spin/movement immediately, sometimes no exit velocity for
several minutes). Mixing live and canonical writes creates schema
sloppiness. Two tables with a clean nightly handoff.

Spring backend reads `pitches_live` for live game UI; reads `pitches`
for everything else.

### Live polling design (the engineering-dense part)

- **Game state machine** (NOT a fixed timer): schedule lookup → start
  polling at game start → end polling at game end. Doubleheaders,
  postponements, suspended games handled.
- **Rate limiting**: token bucket, ~2 req/sec ceiling
- **Gap recovery**: if laptop offline mid-game, on resume fetch full
  game and dedup against existing data. Don't try to "resume from
  checkpoint."
- **Failure handling**: 5xx → exponential backoff; malformed JSON →
  log + skip; rain delay → no errors, polling continues.

### Weather: pre-game and post-game (locked refinement)

Two distinct pulls, two distinct purposes:

| Pull      | When                       | Source              | Stored in          | Consumed by            |
| --------- | -------------------------- | ------------------- | ------------------ | ---------------------- |
| Pre-game  | ~30 min before first pitch | Open-Meteo forecast | `weather_forecast` | Live inference path    |
| Post-game | ~1 hour after game ends    | Open-Meteo archive  | `weather_observed` | Training pipeline only |

This split prevents serving-time/training-time skew. Forecast accuracy
itself becomes a measurable signal. Honest two-eval reporting: "model
performance on observed weather" vs. "model performance on forecast
weather."

### Idempotency, validation, backups

- **Pitch identity**: `(game_id, at_bat_index, pitch_number)` PK. Dedup
  via ReplacingMergeTree.
- **Per-stage assertions**: row counts within ±5% of published season
  totals; no NULL game_ids; exit_velocity in [40, 130] mph or NULL;
  pitch_type in known enum; <5% NaN per feature; target encodings free
  of future contamination. Plain SQL, fail-loud, alert-integrated.
- **Schema migration**: versioned SQL files + tiny Python tracker
  (~20 lines). Forward-only.
- **Backups**: clickhouse-backup nightly to local, then rclone to
  Cloudflare R2 (revised from Backblaze B2 by decision [128] / ADR-0007;
  same S3-compatible abstraction, vendor consolidation on Cloudflare).
  7 daily / 4 weekly / 12 monthly retention. **Verified restore drill
  before season starts** — backups not restored aren't backups.

### Cut explicitly

- No Airflow / Prefect / Dagster (Spring `@Scheduled` + cron is enough)
- No CDC / streaming (project 3 territory)
- No separate data warehouse (ClickHouse is both warehouse and serving DB)
- No feature store framework (the `features` ClickHouse table IS the feature store)
- No GraphQL (Spring REST endpoints reading from ClickHouse)

---

## 6. Backend (Java + Spring)

### Structure: monolith with profile-based split (Option B)

One JAR, two profiles, two systemd units. Different runtime
characteristics, shared codebase.

```
@Profile("api")    → web controllers, inference loader, async logger
@Profile("worker") → @Scheduled jobs, drift computation, ingest
(no profile)       → @Repository, registry service, domain models
```

Why split: drift computation jobs scan days of prediction logs in
ClickHouse — long-running, would compete with HTTP request thread pool.
Why same JAR: one machine, one developer; two-binary split is
unnecessary deployment overhead.

### Module layout

```
net.thebullpen.baseball/
├── api/         # @RestController (api profile only)
├── inference/   # ONNX loading, calibrators, A/B router, async logger
├── registry/    # Model registry CRUD, promotion logic
├── drift/       # Drift jobs (worker profile)
├── retraining/  # Trigger queue, orchestration
├── ingest/      # Live polling, weather pulls (worker profile)
├── data/        # ClickHouse + SQLite repositories
├── domain/      # Pure data classes (no JPA annotations)
├── simulation/  # Forward simulator
└── config/      # Spring configuration
```

Domain models in `domain/` are pure data (records). JPA entities (if
any) live in `data/` and map to/from domain types. Hexagonal-lite — lets
inference and simulation reason about `Pitch` without coupling to SQL.

### Python ↔ Java contract

File-based, no live RPC. Per model version:

- `model.onnx` — the model
- `calibrator.json` — isotonic calibration parameters
- `metadata.json` — version, training info, eval metrics, feature schema hash
- `feature_pipeline.json` — column order, dtypes, encoding maps
- `training_data.parquet` — the snapshot

Java loads metadata at registration, computes feature_schema_hash itself,
refuses registration if hashes don't match. The contract is the
deploy-gate leakage check.

### Inference path

1. Request arrives at `/predict/pitch` (or `/predict/batted-ball`)
2. Controller validates, builds `PitchContext` (or `BattedBallContext`)
3. `InferenceRouter` reads `model_routing` from SQLite
4. Bucket request via Murmur3 hash on game_id
5. Run inference on assigned model + shadow model in parallel (CompletableFuture)
6. Apply isotonic calibration as post-processing
7. **Async** log predictions to ClickHouse via batched writer
8. Return prediction

**Async logger**: bounded in-memory queue + background flusher (every N
seconds or M rows). Queue overflow → drop with warning log. Logging is
best-effort by design.

### Spring configuration

**Use**:

- `@RestController` with explicit `ResponseEntity<>` for non-trivial responses
- Constructor injection (no `@Autowired` on fields)
- `@Validated` + Bean Validation on request DTOs
- `spring-boot-starter-actuator` (free observability via /actuator/\*)
- Micrometer + Prometheus exporter (also via Actuator)
- Flyway for SQLite migrations
- **Virtual threads (Java 21)**: `spring.threads.virtual.enabled=true` —
  critical for inference path
- Spring Security only on `/admin/*` with HTTP basic auth (single env-var credential)

**Avoid**:

- `@Async` for inference (virtual threads make blocking calls equivalent)
- Spring Cloud, Eureka, Config Server (single-machine deployment)
- Reactive Spring / WebFlux (virtual threads = MVC equivalent and simpler)
- Custom Spring Security filter chains (we have no users)

### Observability

- **Logs**: Logback JsonEncoder → journald (no log files, no rotation cron)
- **Metrics**: Micrometer → Prometheus → local Grafana, 3 dashboards
- **Tracing**: OpenTelemetry Spring Boot integration; trace_id in MDC; no
  remote collector for v1
- **Auth**: HTTP basic on `/admin/*`, none elsewhere

---

## 7. Frontend (React)

### Stack: pure SPA

- **React 18 + TypeScript + Vite** (familiar, no learning tax)
- **TanStack Query** for server state (caching, dedup, background refetch,
  loading/error/success states)
- Plain React + Context for client state (no Redux/Zustand)
- **Polling via TanStack Query** during live games (10–15s interval).
  WebSockets explicitly rejected — coordination overhead, harder
  debugging, no UX win at this polling cadence.

### Five pages (LOCKED)

| Page                | Pattern                           | Purpose                                                                                     |
| ------------------- | --------------------------------- | ------------------------------------------------------------------------------------------- |
| Game / Live view    | Analytical + live                 | Single in-progress game, pitch-by-pitch with model predictions overlay                      |
| Player Lookup       | Analytical                        | Search any pitcher/batter; recent predictions vs. actuals; per-player calibration plot      |
| **Park Explorer**   | **Marquee**                       | **30-stadium HR probability heatmap; sliders for launch parameters**                        |
| **Ops Dashboard**   | **Analytical (recruiter-facing)** | **Model versions, A/B traffic split, drift charts, retraining queue, reliability diagrams** |
| About / Methodology | Editorial                         | What models do, training data, eval methodology, "v2 ideas"                                 |

Conspicuously not in scope: leaderboards, team pages, social features,
comments, fantasy integration, betting, mobile app, dark mode toggle,
i18n.

### Visualizations

- **30-park heatmap** (Park Explorer marquee): D3, custom SVG.
  Pre-rendered stadium outlines as static assets; only color overlays
  dynamic. **Largest single component, ~50–70 hours.** Highest-variance
  in visual quality. Build basic version first, iterate to polished.
- **Reliability diagrams** (Ops): Recharts or Mantine charts.
- **Live pitch overlay** (Game view): D3, custom.

### Performance constraints

- Bundle < 300KB gzipped initial load
- Lazy-load Park Explorer (heaviest page)
- Lighthouse > 80 on all pages
- Mantine pinned version (occasional breaking minor versions)

### Accessibility

- Mantine handles primitives (keyboard nav, focus management, ARIA)
- Custom visualizations need explicit pass: alt text, color-blind-safe
  palettes (Viridis), keyboard navigation on interactive elements
- Budget: ~4 hours for accessibility audit

---

## 8. Design System

### Visual identity (LOCKED)

**Editorial-data**: Observable structurally + Pudding aspirationally +
Athletic-flavored editorial typography on narrative pages.

The product is a tool for thinking with data. Visual ambition lives in
the data presentation, not in chrome around it.

**Pages with deliberate visual ambition**: About, Park Explorer.
**Other pages**: disciplined-analytical, restraint over flair.

Lusion-tier visuals across the project explicitly rejected (~6 additional
months of work, fights analytical content).

### Typography

| Use                | Font               | Notes                                                         |
| ------------------ | ------------------ | ------------------------------------------------------------- |
| Body, UI           | **Inter**          | Tabular figures always on (`font-feature-settings: 'tnum' 1`) |
| Data, numbers      | **JetBrains Mono** | Free, warm, friendly                                          |
| Display, editorial | **Source Serif 4** | Used boldly: 48–64px, weight 600+, `letter-spacing: -0.02em`  |

**Type scale**: 1.25 modular, 16px base → 12, 14, 16, 20, 24, 32, 48, 64.
Line height 1.5 body, 1.2 display.

**Discipline rule**: monospace and serif never appear on the same line.
Sans-serif is connective tissue.

### Color palette

```
Backgrounds:
  bg-base       #FAFAF7   warm off-white (NOT pure white — editorial feel)
  bg-elevated   #FFFFFF   pure white for cards, modals
  bg-subtle     #F2F1ED   page sections, alternating rows
  bg-emphasis   #E8E6E0   borders, dividers (NOT shadows)

Text:
  text-strong   #161513   warm near-black (NOT #000)
  text-default  #2D2B27   body
  text-muted    #6B6862   labels, captions
  text-subtle   #9A968F   timestamps, tertiary

Accent (1, used carefully):
  accent        #B53D2C   deep brick-red — leather-glove, not stop-sign

Data viz:
  Sequential: Viridis or Magma (perceptually uniform, colorblind-safe)
  Diverging:  custom brick-red ↔ teal
  Categorical: hand-picked 5-color palette, similar saturation
```

**Cut**: gradients (SaaS-marketing rhetoric); drop shadows (use 1px
borders); "primary blue" (every other site has it); dark mode v1.

**Token discipline**: define once in Tailwind config, reach for tokens
always. Hex codes in component files = defects. The StudyForesight
hardening sweep had 245 → 93 inline hex colors; this project plans to
avoid the regression by being disciplined from day 1.

### Spacing & density

- **8-point grid**: 4, 8, 12, 16, 24, 32, 48, 64, 96
- **Editorial pages** (About, methodology): max-width 720px, line-length
  ~70 chars, generous vertical rhythm
- **Analytical pages** (Ops, Player): max-width 1200px, side-by-side
  panels, denser
- **Park Explorer**: mixed — generous around heatmap, dense in controls

### Motion

- Transitions on user-driven state changes only (150–300ms)
- No entrance animations on page load
- No page transitions
- Easing: `cubic-bezier(0.4, 0, 0.2, 1)` default
- Framer Motion for non-trivial animations only; CSS `transition` for
  state changes

### Layout patterns (3 locked)

1. **Editorial**: single column, max 720px, centered, generous spacing
2. **Analytical with sidebar**: left rail (~280px) + flexible main grid
3. **Marquee**: full-width hero + grid below

### Polish phase (LOCKED, ~30–50 hours, end of build)

After all 5 pages exist, deliberate cohesion pass:

- Typography refinement
- Spacing audit
- Color audit (catch hex-code defects)
- Motion review
- Accessibility pass
- Park Explorer iteration to lift "fine" → "memorable"

---

## 9. Operations & Deployment

### Hosting topology

```
WSL2 (Ubuntu 24.04 LTS, inside Windows 11)
├── Spring API service        (systemd, profile=api, :8080)
├── Spring Worker service     (systemd, profile=worker, :8081)
├── ClickHouse                (Docker via systemd, :8123 / :9000)
├── Prometheus                (Docker via systemd, :9090)
├── Grafana                   (systemd, :3000)
├── cloudflared               (systemd, tunnel to api.thebullpen.net)
└── Python training env       (no service, runs on demand)
```

**The split**: bare-metal for application code (direct JVM management for
observability and tuning), Docker for stateful services (ClickHouse,
Prometheus benefit from container isolation).

**Network**: Cloudflare Tunnel (free) → desktop:8080. No port forwarding,
no public IP exposure. Vercel hosts frontend at `thebullpen.net`.

### Process management

**systemd patterns** (per service):

- `Restart=on-failure`, `RestartSec=10s`
- `StartLimitBurst=5` over `StartLimitIntervalSec=300s` (no thrash loops)
- Memory caps: API 4G, worker 2G, ClickHouse 8G
- `KillSignal=SIGTERM`, `TimeoutStopSec=30s` (graceful shutdown)
- Boot dependencies: ClickHouse before Spring services
- Logs: stdout/stderr → journald (no file rotation needed)

**Reboot drill required early**: `sudo reboot`, watch everything come
back, fix what doesn't. Untested reboot recovery = unreliable system.

### Monitoring

**Internal** (on desktop, for me):

- Prometheus scrapes Spring `/actuator/prometheus` + node_exporter
- Grafana with 3 dashboards: Application, System, ML Ops

**External** (the uptime claim):

- **Uptime Robot**: HTTP probe `/health` every 5 min (free tier — paid tiers go to 1 min)
- **Healthchecks.io**: heartbeats from worker scheduled jobs
- **Discord webhook** as the alert channel (durable incident log)

### Alerting policy (DOCUMENTED, written in the README)

- **Page**: API down >2min, calibration drift >3 days, retraining job failed
- **Notice**: feature PSI > 0.25 sustained 7d, weekly drift summary,
  nightly job runtime anomalies
- **Logged-only**: everything else

### GPU scheduling (Option A: cron-based)

- systemd timer fires retraining checker every hour during 2–6 AM ET
- Checker reads `retraining_queue`, processes pending rows
- If a job collides with anything else (gaming, etc.), it fails; drift
  detector re-triggers next cycle (self-healing)
- Option B (job queue with GPU lock) deferred to v1.5 if needed

### Backups (LOCKED)

- **Daily**: clickhouse-backup → rclone → R2 (originally B2; switched per [128]), 5 AM ET
- **Weekly**: full snapshot + model artifacts + SQLite, Sunday 5:30 AM
- **Monthly**: same as weekly with longer retention
- **Retention**: 7-4-12 (daily-weekly-monthly)
- **Restore drill**: documented procedure, executed once before season

### Deploy pipeline

- **CI**: GitHub Actions runs tests + build on every push (free, public repo)
- **Deploy**: manual local script `./deploy.sh` (git pull, rebuild,
  systemctl restart). ~30 lines.
- **Frontend**: Vercel auto-deploy on push to main
- **Operational rule**: NO deploys during live games (April–October
  evenings). Discipline, not technical control.

### Cut explicitly

- Kubernetes / Docker Swarm / orchestration (1 machine)
- Terraform / Pulumi (no cloud infrastructure to manage)
- Blue-green / canary deployment (1 machine, A/B at model layer instead)
- Centralized log aggregation (journalctl is enough)
- APM tools (Prometheus + Grafana cover this)

---

## 10. Rejected Alternatives

A "considered and rejected" section is itself a senior-engineering signal.
Below are the major rejected paths from the planning session.

### Sports betting framing

**Rejected** because:

- Regulatory exposure (state-by-state, tout-service edge cases)
- Polarizing resume signal (some FAANG teams discount it heavily)
- The interesting engineering is in the analytics layer, not the betting
  layer; stripping betting loses nothing technically valuable

The product positions as "baseball analytics + prediction" with no
gambling framing.

### LLM-based pitch outcome prediction

**Rejected** with two distinct sub-cases:

1. **Text-based LLM ("Llama-3-Baseball")**: wrong tool. LLMs predict
   tokens; calibrated probabilistic prediction on structured tabular data
   is not their strength. 100× the operational cost for worse predictions
   per published tabular benchmarks.

2. **Transformer architecture on structured data (no language)**:
   genuine ML research direction, but losing as v1 primary. Plan: ship
   LightGBM v1, evaluate transformer in v1.5 via shadow mode against
   LightGBM champion, publish comparison. Best of both worlds.

The presence of this rejection in the README signals engineering
judgment about when LLMs are and aren't the right tool.

### PINN for ball-flight modeling

**Rejected** because the underlying physics is a forward-solvable ODE
(drag-modified projectile motion), not a PDE. PINNs solve problems with
governing PDEs; using one here would be re-justifying a tool from prior
research where it doesn't fit.

**Replaced by physics-retrodiction labels + standard MLP** (see Batted-Ball
section). This uses physics where it's strong (deterministic forward
integration) and ML where it's strong (learning residuals). The right
factorization.

### Random train/test split

**Rejected** as catastrophic for baseball ML. Random splits leak
pitcher/batter histories across train and test. Pure temporal holdout
with rolling-origin CV is the only defensible approach.

### Auto-promotion of retrained models

**Rejected** because it defeats the entire shadow mode + pre-declared
promotion criteria discipline. Retraining is automated; promotion stays
manual + criteria-gated.

### Microservices / multi-binary deployment

**Rejected** for one machine + one developer. Profile-based monolith
gives runtime separation without deployment overhead.

### MLflow for the registry

**Rejected** because building it custom is the resume signal. MLflow
would reduce a flagship subsystem to "an integration."

### WebSockets for live updates

**Rejected** in favor of TanStack Query polling. Coordination overhead
of WebSockets isn't justified by the polling-cadence-equivalent UX.

### Lusion-tier visual ambition across the project

**Rejected** after honest cost-benefit discussion. Marketing-site visual
rhetoric fights analytical product content. Editorial-data identity is
the right answer for this product. Lusion-tier ambition deferred to a
future, separate, creative project.

### Pre-frontend "design phase"

**Rejected** in favor of locking design system + iterating during build

- deliberate polish phase at end. Design done in isolation from real
  components drifts on contact with implementation reality.

### Project 3 (Pi cluster) as failover for Project 2

**Rejected** because it couples projects, guts Project 3's distributed-
systems learning value, and overengineers a non-problem (98% uptime is
fine for a self-hosted analytical product). Each project stands alone.

### ESPN as backup live data source

**Rejected** as ToS-questionable, undocumented, unstable. MLB Stats API
only; if it's down, the service is briefly degraded — that's part of
the honest 98% uptime story.

---

## 11. v1.5 Roadmap

After v1 ships, the deferred items become a clean "what's next" story
that signals iteration intent without committing to additional work
upfront.

| Item                                                          | Origin                        | Estimated effort |
| ------------------------------------------------------------- | ----------------------------- | ---------------- |
| Sequence transformer challenger                               | Architecture rejection of LLM | ~80h             |
| ABS challenge model                                           | Earlier model option deferred | ~50h             |
| Half-inning extension to forward simulation                   | Forward-sim section           | ~10h             |
| Pitcher/batter learned embeddings                             | "Use the GPU more"            | ~30h             |
| Path A physics retrodiction (if Phase 2c fell back to Path B) | Soft cut #5                   | ~25h             |
| Dark mode                                                     | Frontend cut                  | ~20h             |
| GPU job queue with locking                                    | Ops Option B                  | ~6h              |
| Catcher framing / umpire features                             | Pitch model deferred          | ~15h             |
| Sequence features (previous N pitches)                        | Pitch model deferred          | ~30h             |

These represent ~6 months of v1.5 work at the v1 pace; cherry-pick from
this list rather than committing to all.

---

## Document maintenance

This document captures decisions made up to project kickoff. As the
project evolves, decisions will be revised. Update this document
when revisions happen — _and_ update `docs/decisions.md` with a new
chronological entry. The act of writing the update produces ADRs naturally.

If a decision is reversed, leave the original in place and add the
reversal below it with date and reasoning. History is part of the
artifact.
