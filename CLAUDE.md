# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project state

**Implemented and operating** — well past the planning stage. Backend, training, and
frontend all carry real, tested code (≈180 Java / ≈181 Python / ≈141 TS·TSX files, 129
commits as of 2026-05-30). The three planning docs under `docs/` remain the authoritative
source of truth for **design rationale and locked decisions** — _not_ for current build
status, which lives in `docs/phase-status.json` (read by `/status`):

- `docs/design.md` — system design, architecture, every locked technical choice with rationale
- `docs/plan.md` — phased build plan (Phase 0 → Phase 6), exit criteria per phase, soft-cut priority list
- `docs/decisions.md` — chronological numbered log of every locked decision with one-line rationale

**Read all three before doing anything substantive.** They were produced by a long planning
session and most "obvious" alternatives have already been considered and rejected
(see `docs/design.md` §10 "Rejected Alternatives").

### Phase progress (source of truth: `docs/phase-status.json`)

- **Phase 0 — Foundation**: done (WSL2 host, systemd units, Cloudflare Tunnel, CI, `deploy.sh`)
- **Phase 1 — Vertical slice**: done (one prediction end-to-end in the browser)
- **Phase 2 — Real models**: in progress (pitch pre/post heads + LR baseline done; batted-ball MLP / physics / retrodiction landing in 2c)
- **Phase 3 — ML systems wrapper**: done (registry, A/B router, drift jobs, retraining queue, async prediction logger)
- **Phase 4 — Frontend build-out**: done (player lookup, park explorer, game-live, ops dashboard, about)
- **Phase 5 — Polish + operate**: in progress (hardening sweeps, perf/a11y/bundle, public launch)
- **Phase 6 — Hiring readiness**: in progress (README, drift postmortem, OSS targets)

### Current reality vs. headline claims — keep this honest when editing docs

- **Live data is now mixed, no longer narrow.** Live against the backend: `/games/:id`, the
  player lookup + `/players/:id` profile, the `/parks` HR-probability-by-park heatmap, the home
  page's tonight slate, and the Ops dashboard's Model Fleet / latency / retrain queue / ops log
  (live via `/v1/ops/*`, with a fixture fallback when those return empty or the backend is
  offline - so Ops is live-wired, not "fixtures"). Still pure fixtures from
  `frontend/src/data/*-fixtures.ts`: the `/parks` factor table, the `/about` methodology page,
  and the Ops drift-snapshot skeleton (no drift endpoint yet).
- **Live game poller is enabled ingest-only; user-visible pitch predictions are held by design.**
  The full producer chain (MLB Stats API client + parser + per-game poll + `pitches_live` writer +
  the `prediction_log` truth-join) is merged, unit-tested, and enabled in prod behind
  `BULLPEN_INGEST_LIVE_ENABLED` (flipped 2026-06-11, decision [157]). It serves NO user-visible
  pitch prediction _by design_ ([154]/ADR-0011): no pitch head has cleared an honest promotion gate,
  and promoting one on a failed primary would be a threshold bypass. Predictions light up when the
  POST head (the strong candidate; sample-stage gate PASSED, Brier margin ~0.021) clears its
  full-box re-run (hand-off H2). Open evidence artifact: a committed real-feed operating trace
  (issue #1, `docs/runbooks/live-data-setup.md`).
- **Coverage is measured everywhere; backend and training now gate, frontend does not.** Backend
  JaCoCo (in `backend/build.gradle.kts` and `backend.yml`) gates on a regression floor (LINE >= 72%,
  BRANCH >= 58%, a few points under the 2026-06-15 CI baseline of 77.85% / 65.67%), enforced only
  when the Docker ITs run (`-Dbullpen.it.docker=true`, i.e. CI) so local `./gradlew build` is
  unaffected. Training coverage (~46%) is gated as a 40% regression floor, with 75% an aspirational
  warning-only target (`training.yml`). Frontend vitest v8 (`frontend.yml` `npm run test:coverage`)
  still publishes a line/branch baseline without gating. The README's earlier unbacked "~95%" has
  been corrected to the measured figures. Rule still holds: do **not** cite a coverage percentage you
  cannot reproduce from CI.

## What this project is

The Bullpen (`thebullpen.net`) — a self-hosted baseball analytics platform with a custom
ML systems wrapper (registry, A/B routing, drift detection, retraining triggers) around three
calibrated models: a batted-ball champion serving live, plus two pitch-outcome heads in shadow
pending an honest promotion gate (no pitch champion promoted yet - [154]/ADR-0011). Solo
developer, ~8–10 months calendar at 12–15h/week. Operated through at least one MLB season for a
real drift postmortem.

It is **not** a SaaS product, not a betting tool, not a research contribution. Framing
matters — see `design.md` §1.

## Locked technology choices (do not re-litigate without strong cause)

| Layer         | Choice                                                                                                                                                                                                             |
| ------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Backend       | **Java 21 + Spring Boot 3.x**, virtual threads, Spring MVC (not WebFlux). Strict Java, no Kotlin.                                                                                                                  |
| Deployment    | One JAR, two profiles (`api`, `worker`), two systemd units                                                                                                                                                         |
| Inference     | **ONNX Runtime Java** in-process. No Python sidecar, no live RPC.                                                                                                                                                  |
| Training      | Python 3.11+, off the serving path. Python ↔ Java contract is file-based (ONNX + JSON metadata + `feature_pipeline.json` + Parquet snapshot).                                                                      |
| Analytical DB | **ClickHouse** (Docker) — pitches, drift metrics, prediction logs                                                                                                                                                  |
| App state DB  | **SQLite** + Flyway — model registry, A/B config, retraining queue                                                                                                                                                 |
| Frontend      | **React 19 + TypeScript + Vite**, pure SPA (React 19 in-repo; the "18" floor in early decisions was upgraded). **TanStack Query** for server state, plain React Context for client state. Polling, not WebSockets. |
| UI            | **Mantine 9 + Tailwind 4**. Editorial-data identity (Inter / JetBrains Mono / Source Serif 4).                                                                                                                     |
| Hosting       | Self-hosted in WSL2 (Ubuntu 24.04 LTS) on personal desktop. Cloudflare Tunnel for public access. Frontend on Vercel.                                                                                               |
| Process mgmt  | systemd (bare-metal for app, Docker for stateful services)                                                                                                                                                         |
| Observability | Prometheus + Grafana + Actuator (internal); Uptime Robot + Healthchecks.io + Discord webhook (external)                                                                                                            |
| Models        | LightGBM (pitch outcome, multinomial); multi-output MLP with shared backbone + 30 per-park heads (batted-ball); LR baseline always co-registered                                                                   |
| Eval          | Rolling-origin temporal CV, 4 folds 2015–2025. **Never** random splits. Within-fold split granularity is by date — never by game or pitch.                                                                         |

Already-rejected alternatives (with reasoning in `design.md` §10): LLM for pitch outcome,
PINN for ball-flight, MLflow, microservices, WebSockets, Next.js/SSR, Airflow, ESPN as
data source, dark mode v1, auto-promotion of retrained models, sports betting framing.

## Non-negotiable discipline rules (from `plan.md`)

These exist because past failure modes are known. Don't relax without explicit user
approval:

1. **Build the demoable spine first, thicken it later.** No horizontal building. Phase 1's vertical slice (one prediction visible end-to-end in browser) is the credibility floor.
2. **No design tokens drift.** Hex codes in component files are defects — reach for tokens.
3. **No deploys during live games** (evenings April–October).
4. **No cuts to**: Phase 0 foundation, eval artifacts, the model registry, the Ops dashboard, Phase 6 hiring-readiness work (README, drift postmortem, OSS PR).
5. **No promotion of a model without pre-declared promotion criteria** (primary metric, sample size, threshold, guardrails) and a passing row in `experiment_results`.
6. **No auto-promotion of retrained models** — retraining is automated, promotion stays human-gated.
7. **Feature schema hashing is enforced at registration** — refuse models whose schema hash doesn't match the production feature pipeline.
8. **Restore drill and reboot drill must run before season starts.** Untested backups / untested recovery don't count.
9. **Two heads = two separate models** in the registry (pre-pitch / post-pitch). Not one model with feature masking.
10. **All rolling/form features computed via streaming temporal cutoff.** Leakage tests in CI are non-negotiable: future contamination, shuffled-target, calendar-date trace, ID consistency.
11. **Local dev on macOS, prod on the self-hosted Linux desktop. No code edits on the prod box.** SSH / remote-control into prod is read-only (logs, Grafana, ClickHouse queries); writes happen via `git push` + `./deploy.sh` only. See ADR-0006.
12. **All object storage via S3-compatible client with `S3_ENDPOINT_URL` as the only environment-specific knob.** Prod = Cloudflare R2 (vendor-consolidated with Tunnel + DNS, per decision [128]); offline dev = MinIO on the portable drive. No `file://` paths in storage code, no second abstraction. See ADR-0007.
13. **2026 season data is holdout-only — never use for training or validation.** The 2026 Statcast pull exists exclusively for post-training, post-validation accuracy testing against unseen data. Models train and validate on 2015–2025 seasons only. Any script or pipeline that accepts season ranges must not include 2026 in a training or validation split.

## Decision logging discipline — two layers

Decisions live in two complementary places:

1. **`docs/decisions.md`** — chronological append-only numbered log. Every locked decision lands here as a one-line entry: `[N] DATE — DECISION — RATIONALE`. Fast, low-ceremony. The `block-retro-decisions` git hook enforces append-only.

2. **`docs/adr/NNNN-{kebab-case-title}.md`** — full Architecture Decision Records for substantial decisions that need depth. Sections: Context, Decision, Consequences, Alternatives Considered, Revision History. Template at `docs/adr/TEMPLATE.md`. Roughly the top ~15% of decisions warrant an ADR — locked tech choices, architecture splits, anything where future-you needs to remember _why_ not just _what_.

When you lock a substantial decision: write the ADR first, then the `decisions.md` entry references it. Example: `[12] 2026-09-14 — Use ONNX Runtime Java for in-process inference — see ADR-0007`.

**Reversals**:

- In `decisions.md`: add a new numbered entry referencing the original (`[N] DATE — Reverse decision [M] (...) — REASON`). Never delete the original.
- In an ADR: update Status to `Superseded by ADR-NNNN`, add a Revision History entry explaining what changed. The new ADR should reference what it replaces. ADRs _can_ be edited in place via Revision History (the git hook covers `decisions.md`, not `docs/adr/`).

When `docs/design.md` or `docs/plan.md` change in response to a decision, update them in the same commit as the `decisions.md` entry (and the ADR, if one).

## Soft-cut priority order (if behind schedule)

If a phase exit criterion looks at risk at the 2-week review, cut in this order — never
cut in a different order without explicit reasoning:

1. Drop pitch post-pitch head, keep pre-pitch only (~20h)
2. Drop A/B real-routing, keep shadow only (~10h)
3. Drop automated drift retraining, keep manual (~5h)
4. Drop Game/Live view (~12h)
5. Drop physics retrodiction for batted-ball, fall back to per-park naive subsets (~25h, **weakens model significantly — document honestly**)

## Where to put things

Per `design.md` §6, the Spring module layout (in place under
`backend/src/main/java/net/thebullpen/baseball/`) is:

```
net.thebullpen.baseball/
├── api/         # @RestController (api profile only)
├── inference/   # ONNX loading, calibrators, A/B router, async logger
├── registry/    # Model registry CRUD, promotion logic
├── drift/       # Drift jobs (worker profile)
├── retraining/  # Trigger queue, orchestration
├── ingest/      # Live polling, weather pulls (worker profile)
├── data/        # ClickHouse + SQLite repositories
├── domain/      # Pure data classes (records, no JPA annotations)
├── simulation/  # Forward simulator
└── config/      # Spring configuration
```

Domain models in `domain/` stay pure. JPA entities (if any) live in `data/` and map
to/from domain types. This hexagonal-lite split lets `inference/` and `simulation/`
reason about `Pitch` without coupling to SQL.

## Repository layout (monorepo)

```
thebullpen/
├── backend/            # Java 21 + Spring Boot 3.x (Gradle Kotlin DSL)
│   └── src/main/...    # api/, inference/, registry/, drift/, retraining/,
│                       # ingest/, data/, domain/, simulation/, config/
├── training/           # Python 3.11+ (uv-managed) — model training, eval, ONNX export
│   ├── artifacts/      # Produced ONNX + metadata + Parquet snapshots
│   ├── eval/           # Rolling-origin CV harness, results
│   └── tests/leakage/  # Four CI-required leakage tests
├── frontend/           # React 18 + TypeScript + Vite + Mantine + Tailwind
├── contracts/          # Canonical Python↔Java file contracts (feature_pipeline.json
│                       # schema, ONNX format notes, JSON metadata schema). Both
│                       # /backend and /training depend on this directory.
├── docs/               # Planning docs (design, plan, decisions), drill reports, deploy logs
└── deploy.sh           # Manual deploy script (Phase 0)
```

The `/contracts` directory is the single source of truth for the file-based Python↔Java
boundary. Schema-hash check at registration (rule 7) reads from here.

## Build / test / run commands

Most commands assume the working directory is the project root unless noted.

### Backend (Java)

- Build: `./gradlew -p backend build`
- Test: `./gradlew -p backend test`
- Format: `./gradlew -p backend spotlessApply`
- Static analysis: `./gradlew -p backend spotbugsMain errorproneMain`
- Run API profile: `./gradlew -p backend bootRun --args='--spring.profiles.active=api'`
- Run worker profile: `./gradlew -p backend bootRun --args='--spring.profiles.active=worker'`
- Migrate SQLite registry: `./gradlew -p backend flywayMigrate`

### Training (Python)

- Install deps: `uv sync` (run inside `training/`)
- Add a dep: `uv add <pkg>` (never edit `pyproject.toml` by hand)
- Format + lint: `uv run ruff format training && uv run ruff check --fix training`
- Type check: `uv run pyright training`
- Tests: `uv run pytest training`
- Leakage tests only: `uv run pytest training/tests/leakage -x`
- Run rolling-origin CV: `uv run python -m bullpen_training.eval.promotion.driver --model <name>` (run from `training/`; the promotion-evidence driver wraps the `bullpen_training.eval.cv_harness.run` 4-fold harness and co-runs the baseline. There is no standalone `rolling_cv` CLI.)

### Frontend (React)

- Install: `cd frontend && npm install`
- Dev server: `cd frontend && npm run dev`
- Type check: `cd frontend && npx tsc --noEmit`
- Lint: `cd frontend && npm run lint`
- Unit tests: `cd frontend && npm test`
- E2E (Playwright): `cd frontend && npx playwright test`
- Build for Vercel: `cd frontend && npm run build`

### Deploy

- Deploy to WSL2 host: `./deploy.sh` (prefer the `deploy-safely` skill which adds the live-game-window check)
- Frontend auto-deploys to Vercel on push to `main`

### Local services (Docker)

- ClickHouse + Grafana + Prometheus: `docker compose -f infra/docker-compose.yml up -d`
- Stop: `docker compose -f infra/docker-compose.yml down` (denied by default in settings.json — pass through manually if needed)

(Scaffolding is in place; keep these commands current as it evolves. Hooks auto-format
Java/Python/TS on every edit; you do not need to run formatters manually for normal editing.)

## Conventions and tooling

### Languages and tools

- **Java**: Gradle (Kotlin DSL), Spotless with google-java-format, Error Prone, SpotBugs
- **Python**: uv for env/deps, ruff for lint+format, pyright for types
- **Frontend**: ESLint + Prettier, Vitest for unit, Playwright for E2E
- **Git**: trunk-based on `main`, Conventional Commits (`feat:`, `fix:`, `docs:`, `refactor:`, `chore:`), tag releases with `v{YYYY.MM.DD-HHMM}`

### Writing style

- **Do not use em-dashes (`—`).** Use a plain hyphen `-`, a colon, parentheses, or rephrase into two sentences. This applies to everything written here: prose replies, commit messages, `docs/decisions.md` entries, ADRs, docs, and code comments. (Bonus: keeps hashed contract/JSON files ASCII-clean, avoiding cross-language canonicalization surprises.)

### Testing posture (important)

**Prefer real dependencies over mocks.** ML systems are exactly where mock/prod
divergence bites. Default:

- ClickHouse: Testcontainers
- SQLite: temp file or `:memory:`
- ONNX: real ONNX Runtime Java session loading a small fixture model

Mocks are only acceptable at hard external boundaries (Discord webhook, MLB Stats API
HTTP client).

### Avoid in this project

- **Lombok** — Java 21 records + sealed types + pattern matching cover Lombok's use cases without compile-time agents
- **JPA / Hibernate** — wrong abstraction for ClickHouse; use `JdbcTemplate` (or jOOQ if introduced)
- **WebFlux / Reactive** — virtual threads + Spring MVC handle concurrency
- **Heavy Mockito** — see testing posture above
- **Hex codes in component files** — Mantine theme tokens or Tailwind theme colors only (plan.md token-discipline rule)
- **`useEffect` for server state** — TanStack Query only
- **WebSockets** — polling only (locked in decisions.md)

## Gotchas

- **Keep the repo on the Linux filesystem in WSL2** (`/home/<you>/code/thebullpen`), not under `/mnt/c/...`. The Windows↔Linux boundary I/O kills Gradle and uv performance.
- **ClickHouse Docker memory limits**: WSL2's default memory pool can starve ClickHouse. Set a `.wslconfig` memory cap or pre-allocate via `docker compose` resource limits.
- **Frontend deploys via Vercel on `main` push** — staging happens via Vercel preview URLs on feature branches if you ever use them, otherwise prod follows main directly.
- **Cloudflare Tunnel is the only public ingress** — there is no other path into the app. If the tunnel is down, the app is down regardless of host health.
- **`docs/` is where planning lives**, not the repo root. CLAUDE.md and skills reference `docs/design.md`, `docs/plan.md`, `docs/decisions.md`. Phase progress is tracked in `docs/phase-status.json` (machine-readable, hand-edited as work completes — `/status` reads from it). Substantial decisions get full ADRs at `docs/adr/NNNN-*.md` (template at `docs/adr/TEMPLATE.md`); operational hardening artifacts live at `docs/hardening/{date}_sweep.md`; postmortems at `docs/postmortems/{date}_{name}.md`; runbooks at `docs/runbooks/`.
- **`.githooks/` is the canonical hooks location.** Run `./.githooks/install.sh` once after clone (sets `git config core.hooksPath .githooks`). The `pre-commit` hook enforces `contracts/feature_pipeline.json` schema_hash + pipeline_version discipline.
- **Backlog lives in GitHub Issues.** See `.github/labels.md` for the label conventions (type/severity/area/phase/status). Decisions do NOT go in Issues — they live in `docs/decisions.md`. Drills and postmortems also stay in `docs/`.
- **Backups have two layers.** Daily automated snapshot (Layer 1) via `infra/backup/clickhouse-snapshot.sh` + systemd timer. Manual USB sync (Layer 2) via `infra/backup/usb-backup.sh` for hardware contingency. See `infra/backup/README.md`.

## Hard "never" rules (additional to the discipline rules above)

- **Never touch live ClickHouse without a backup snapshot first.** The `block-destructive-ch` hook enforces this on `DROP`/`TRUNCATE`/`ALTER`, but the rule applies to manual operations too. Recovery from a destructive op without a snapshot is unrecoverable.
- **Never commit a trained model artifact** — only metadata. Models live outside git (local-only or S3-compatible storage). The registry stores the path, not the bytes. `.gitignore` covers `training/artifacts/**/*.onnx`, `*.pt`, `*.parquet`.
- **Never modify `docs/decisions.md` retroactively.** Append-only. The `block-retro-decisions` git hook blocks interior edits and line removals.
- **Never use `random_state` on data splits.** Splits must be temporal (rolling-origin). The `ml-leakage-auditor` agent reinforces this; never override.

## Glossary (project-specific terminology)

- **Pre-pitch head** — the model that predicts pitch outcome from features available _before_ the pitch is thrown (count, runners, batter/pitcher history, etc.). A separate registry entry from the post-pitch head (rule 9).
- **Post-pitch head** — the model that uses early-flight features (release-side data, initial trajectory) to refine the outcome prediction. Separate registry entry.
- **Promotion criteria** — the _pre-declared_ set of (primary metric, sample size, threshold, guardrails) that must be satisfied before a model moves from SHADOW to LIVE. Stored on the model's registry row (rule 5).
- **Shadow routing** — predictions are made by the model and logged to ClickHouse `prediction_log` but are _not_ user-visible. Default state for any newly registered model.
- **Rolling-origin CV** — the temporal cross-validation pattern: each fold trains on all earlier dates and validates on a later contiguous window. No date overlap, no random splits. 4 folds 2015–2025.
- **Streaming temporal cutoff** — feature computation that, for each row, only considers data with `ts <= row.game_event_ts`. Prevents future contamination.
- **Feature schema hash** — deterministic hash of `/contracts/feature_pipeline.json` (column order, dtypes, transformations). Mismatch at model registration = HARD FAIL (rule 7).
- **A/B router** — the in-process Java component that decides, per request, which model serves the user-facing prediction and which models run in shadow alongside. Logs every decision.
- **Drift metrics** — population stability index (PSI) and calibration drift (ECE delta vs training baseline) computed per feature and per model output, written to ClickHouse on a worker-profile schedule.
- **Retraining queue** — a SQLite table consumed by the worker profile. Triggers are automated (drift, schedule) but downstream promotion remains human-gated (rule 6).
- **Experiment results row** — a SQLite row capturing the outcome of a rolling-CV evaluation, referenced by `promote-model` as the evidence gate.

## Working with the user

- This is a portfolio project framed for FAANG ML/SD engineering hiring. Architecture choices that are "good enough" for production but exceptional as resume signal are the right call.
- The user has done deep planning. When in doubt, the planning docs already address it — search them before asking.
- Honest progress reviews every two weeks (see `docs/plan.md`). Cuts made early are surgical; cuts made late are amputations.
- **Decisions are conversational.** No decision gets locked into `docs/decisions.md` until we've gone back-and-forth and explicitly agreed. Claude proposes options, the user pushes back, we converge, _then_ the `decision-recorder` agent writes the numbered entry. No silent "I'll just pick X and tell you" — that pattern is how bad locked-in choices happen. Use the `/decide` slash command or the `lock-decision` skill to run this loop.

## Available subagents and skills (project-specific)

Subagents under `.claude/agents/`:

- `ml-leakage-auditor` — audits feature/training code for temporal leakage
- `registry-guard` — enforces registry/router/promotion discipline rules
- `java-reviewer` — Java/Spring 3 review with project exclusions
- `python-training-reviewer` — Python ML review with rolling-origin discipline
- `frontend-reviewer` — React/Mantine/Tailwind review with token discipline
- `schema-migration-author` — writes SQLite Flyway + ClickHouse DDL + repo changes in lockstep
- `drill-runner` — runs the restore and reboot drills with evidence capture
- `decision-recorder` — drafts `decisions.md` entries after explicit agreement
- `ui-design-loop` — multi-model UI synthesis (Claude + Stitch) → spec → React + Playwright verify

Skills under `.claude/skills/`:

- `register-model` — full model intake procedure
- `promote-model` — SHADOW → LIVE promotion gate
- `lock-decision` — conversational decision flow
- `add-schema-change` — coordinated DB schema evolution
- `run-rolling-cv` — standard rolling-origin CV harness
- `deploy-safely` — `./deploy.sh` wrapper with safety checks

Slash commands under `.claude/commands/`:

- `/decide <description>` — kick off lock-decision
- `/promote <model_id>` — kick off promote-model
- `/drill restore|reboot` — kick off drill-runner
- `/review-ml` — parallel ml-leakage-auditor + python-training-reviewer
- `/review-java` — parallel java-reviewer + registry-guard (if registry touched)
- `/design <screen-name>` — kick off ui-design-loop
- `/status` — phase progress from `docs/phase-status.json` with on-disk evidence cross-check
- `/ci-add <what to check>` — add a CI job or workflow following conventions

Skill `ci-add` is also under `.claude/skills/` for richer invocations.

## Imports

These ADRs are auto-loaded into Claude Code's context whenever this `CLAUDE.md`
is read. The two new operational-discipline ADRs (rules 11 and 12 above) are
imported in full because their content is load-bearing on day-to-day decisions
about _where_ code is edited and _how_ storage is accessed — both are easy to
violate silently without the constant reminder. The other ADRs (0001–0005)
remain linked but not imported; they document locked tech choices that rarely
recur in conversation.

@docs/adr/0006-dev-prod-boundary.md
@docs/adr/0007-s3-compatible-storage.md
