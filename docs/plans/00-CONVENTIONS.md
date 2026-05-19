# 00-CONVENTIONS — Shared coding conventions

> Read alongside [`00-MASTER.md`](00-MASTER.md). When a leaf plan is silent on style, conventions here are the default.

---

## Repo layout (target end state)

```
thebullpen/
├── CLAUDE.md
├── README.md                          # public-facing; written in Phase 5
├── deploy.sh                          # ~30-line manual deploy
├── docs/
│   ├── design.md
│   ├── plan.md
│   ├── decisions.md
│   └── plans/                         # this tree
├── backend/                           # one Maven/Gradle module
│   ├── pom.xml (or build.gradle.kts)
│   └── src/main/java/net/thebullpen/baseball/
│       ├── api/                       # @RestController (api profile)
│       ├── inference/                 # ONNX, calibrators, A/B router, async logger
│       ├── registry/                  # Model registry CRUD, promotion
│       ├── drift/                     # @Scheduled drift jobs (worker profile)
│       ├── retraining/                # Trigger queue + orchestration
│       ├── ingest/                    # Live polling, weather (worker profile)
│       ├── data/                      # ClickHouse + SQLite repositories
│       ├── domain/                    # Pure records; no JPA / no Spring
│       ├── simulation/                # Forward simulator
│       └── config/                    # Spring configuration
├── ml/                                # Python training; off the serving path
│   ├── pyproject.toml
│   ├── thebullpen/
│   │   ├── features/                  # Feature pipeline (mirrors Java thinking)
│   │   ├── pitch/                     # Pitch outcome models
│   │   ├── battedball/                # Batted-ball MLP + physics simulator
│   │   ├── eval/                      # Rolling-origin CV harness, leakage tests
│   │   └── registry_client/           # Reads SQLite to register models
│   └── tests/
├── frontend/                          # Vite SPA
│   ├── package.json
│   ├── src/
│   │   ├── pages/                     # 5 pages (Game / Player / Park / Ops / About)
│   │   ├── components/
│   │   ├── api/                       # TanStack Query hooks
│   │   ├── design/                    # tokens, theme, mantine config
│   │   └── viz/                       # D3 components
│   └── tests/
├── infra/
│   ├── systemd/                       # *.service, *.timer files
│   ├── docker/                        # docker-compose.yml for ClickHouse, etc.
│   ├── prometheus/                    # prometheus.yml
│   └── grafana/                       # provisioned dashboards
└── ops/
    ├── backup.sh
    ├── restore-drill.md
    └── runbooks/                      # one .md per alert type
```

This layout is the target; create directories as the relevant leaf plan needs them. Don't bootstrap empty trees.

---

## Java (Spring Boot)

**Style**:
- Java 21 only. No Kotlin (decision [23]).
- `record` for DTOs, domain models, immutable values. `class` only when behavior + state.
- Constructor injection. Never `@Autowired` on fields.
- `@RestController` returns `ResponseEntity<T>` for non-trivial responses; `T` directly for trivial 200-only paths.
- `@Validated` on controllers + Bean Validation (`jakarta.validation`) on request DTOs.
- Package-private for everything not deliberately exposed; `public` is opt-in.
- No `@Async` for inference paths — virtual threads make blocking calls equivalent (decision [24]).
- `@Profile("api")` and `@Profile("worker")` annotations on every bean that should not run in both.

**Naming**:
- Packages: `net.thebullpen.baseball.<module>` (lowercase, single word per segment).
- Classes: `PitchPredictionService`, `ModelRegistryRepository` — full English, no abbreviations.
- Records: `PitchContext`, `BattedBallContext`, `PredictionResponse` — no `Dto` suffix.
- DB constants live in `data/sql/` SQL files, not in Java string literals.

**Build**:
- Maven (default) — Spring Boot template, predictable ATS keyword. Gradle Kotlin DSL acceptable but Maven is the resume-default.
- Spring Boot 3.x; Spring Framework 6.x; lock minor versions in `pom.xml`.
- No Spring Cloud, Eureka, Config Server, WebFlux. Decision [24].

**Tests**: see [`00-TESTING-STRATEGY.md`](00-TESTING-STRATEGY.md).

---

## Python (ML training)

**Style**:
- Python 3.11+. Type hints on every public function.
- `ruff` for lint + format (replaces black + isort + flake8). Configured to be strict.
- `mypy --strict` on `ml/thebullpen/`. Tests can be looser.
- `pyproject.toml` with `uv` for dependency management; `requirements.txt` derived from `uv pip compile`.
- `dataclasses` for typed records. `pydantic` only at trust boundaries (e.g., loading metadata.json) — not as a general DTO library.
- Logging: `structlog` configured to emit JSON; never `print` in production paths.

**Naming**:
- snake_case modules and functions; PascalCase classes.
- Top-level package: `thebullpen` (so `from thebullpen.features import ...`).
- Model identifiers in registry: `pitch_outcome_pre`, `pitch_outcome_post`, `batted_ball`, `pitch_outcome_lr_baseline`. Lowercase, underscore-separated, stable across versions.

**Reproducibility**:
- Every training run pins a `git commit`, a `data_hash`, and a `random_seed`. All three written into `metadata.json` and `eval/commit_sha.txt`.
- No notebooks in `ml/thebullpen/`. Notebooks for exploration only, kept under `ml/notebooks/` and not imported by training code.

---

## TypeScript (frontend)

**Style**:
- TypeScript 5.x, `strict: true`, `noUncheckedIndexedAccess: true`.
- Functional components only. Hooks > class components.
- TanStack Query for server state (decision [95]). Plain React + Context for client state. **No Redux. No Zustand.**
- Mantine for primitives; Tailwind for layout/utility (decision [109]). When they conflict, prefer Mantine for behavior, Tailwind for spacing/positioning.
- File naming: `kebab-case.tsx` for components and pages; `PascalCase` for the exported component.

**Naming**:
- Pages: `src/pages/park-explorer.tsx`, `src/pages/ops-dashboard.tsx`, etc.
- Components: `src/components/<feature>/<thing>.tsx`.
- API hooks: `src/api/use-<resource>.ts` returning a typed TanStack hook.
- Type imports separated: `import type { Foo } from '...'`.

**Design tokens**:
- All design tokens live in `src/design/tokens.ts` (and a Tailwind config that consumes the same file).
- **Hex codes inside component files are defects.** Phase 5.2 audits this. (Discipline rule 1.)

---

## Database conventions

**SQLite (registry)**:
- Migrations in `backend/src/main/resources/db/migration/sqlite/V<NNN>__<name>.sql`. Flyway-style naming.
- Forward-only. No `down` migrations.
- Table names: `model_versions`, `model_routing`, `experiment_results`, `retraining_queue` — snake_case, plural.
- Column types prefer `TEXT`, `INTEGER`, `REAL`, `TIMESTAMP`. Avoid `BLOB` (use file paths).
- Every table has `created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP`. Every mutable table has `updated_at`.

**ClickHouse (analytical)**:
- Migrations in `backend/src/main/resources/db/migration/clickhouse/V<NNN>__<name>.sql`.
- All time-series tables partitioned by month (`PARTITION BY toYYYYMM(<timestamp_col>)`).
- All time-series tables ordered by `(<grouping_col>, <timestamp_col>)`.
- `prediction_log`, `drift_metrics`: TTL policies set in their respective leaf plans (see `00-RISK-REGISTER.md` G7).
- Pitch identity: `(game_id, at_bat_index, pitch_number)`. ReplacingMergeTree for dedup (decision [92]).

---

## Git / commits / branches

- `main` is always deployable. Topic branches off `main`, merged via squash.
- Branch names: `phase-<N>/<leaf-id>-<slug>` — e.g., `phase-2/2a.5-lightgbm-train`.
- Commit message body: imperative mood, mention which leaf plan(s) the commit belongs to in a trailer:

  ```
  Wire ONNX runtime loader and add parity test

  Loads model.onnx via OrtEnvironment, runs a parity check against
  Python's onnxruntime output on a fixed test fixture.

  Plan: phase-1-vertical-slice/1.4-onnx-export-and-java-load.md
  ```
- Conventional Commits not required, but allowed. Don't enforce.
- **Never `--amend` after push**. Never force-push to `main`.

---

## ADR template

Locked decisions live in [`../decisions.md`](../decisions.md). New decisions append:

```
[N] YYYY-MM-DD — **DECISION** — RATIONALE.
```

Reversals never delete the original:

```
[N] YYYY-MM-DD — **Reverse decision [M] (description)** — REASON.
```

When `design.md` or `plan.md` change to reflect a decision, **update them in the same commit** as the `decisions.md` entry. Discipline rule 11 (CLAUDE.md decision logging).

---

## Anti-patterns (do not do these)

- ❌ Adding a new database, queue, or framework "just for X" — ClickHouse + SQLite is the schema. Justify additions in a new ADR.
- ❌ Wiring Python into the inference path — ONNX file contract is the boundary (decision [27]).
- ❌ Adding `@Async`, `@EnableAsync`, or `Executors` for inference fan-out — virtual threads handle this.
- ❌ Reaching for WebSockets — TanStack Query polling is the answer (decision [96]).
- ❌ Writing leaf plans for v1.5 work. v1.5 stays in `design.md` §11 until v1 ships.
- ❌ Using `Optional<T>` as a method parameter. Optional is for return types only.
- ❌ Returning `null` from public Java APIs without `@Nullable` annotation.
- ❌ Catching and swallowing exceptions silently. Log + rethrow or log + alert.
