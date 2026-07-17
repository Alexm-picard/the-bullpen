# 00-RISK-REGISTER — Gaps, ambiguities, and open questions

> Living document. Surfaced during the planning session's gap analysis.
> Each entry has an ID (`G<N>` for genuine gaps, `I<N>` for implementation-time gaps, `C<N>` for contradictions), a severity, an owning phase, and a status.
>
> **Severity**: 🔴 high (blocks correctness), 🟡 medium (blocks quality), 🟢 low (cosmetic / cleanup).
>
> **Status**: 🟢 closed (decision recorded), 🟡 deferred (acceptable for now), 🔵 open (still needs resolution).
>
> When a leaf plan closes a register entry, link the leaf plan from the entry's resolution row.

---

## Genuine architectural gaps

### G1 🔴 Feature-pipeline parity (Python ⇄ Java)

The same feature transformation logic must run in Python (training) and Java (serving). `feature_pipeline.json` is named as the contract but its expressiveness isn't defined: which transformations are encodable? How does Java reproduce target encodings exactly? Is preprocessing inside the ONNX graph, in Java, or split?

- **Why high-severity**: silent feature skew is the #1 production-ML failure mode the project explicitly defends against (decision [67]).
- **Owning phase**: Phase 2a (where the contract is exercised end-to-end for the first time).
- **Status**: 🔵 open.
- **Resolution proposal**: Pin a concrete `feature_pipeline.json` schema. Two-tier strategy — (a) bake numerics + simple categoricals into ONNX via `onnxruntime-extensions` or sklearn → ONNX conversion, (b) for target encodings, store `{pitcher_id: encoded_value}` lookup tables in `feature_pipeline.json`, evaluate in Java. CI parity test (G1.test): a fixed pitch fixture must produce identical features in Python and Java.

### G2 🟡 Cross-DB referential integrity

`model_versions.id` lives in SQLite; `prediction_log.model_version_id` lives in ClickHouse. No FK possible across stores.

- **Owning phase**: Phase 3a (registry).
- **Status**: 🔵 open.
- **Resolution proposal**: Treat `model_version_id` in ClickHouse as a soft reference. Reconciliation job (worker, weekly) flags any `prediction_log.model_version_id` not present in SQLite — alerts via Discord. Never delete from `model_versions` (archive only).

### G3 🟡 Feature-schema-hash bootstrap

The rule "refuse models whose schema hash doesn't match the production feature pipeline" presumes a production pipeline exists. The first model defines it.

- **Owning phase**: Phase 3a (registry).
- **Status**: 🔵 open.
- **Resolution proposal**: First registration sets the hash. Subsequent registrations must match. A CLI escape hatch (`--bootstrap-feature-schema`) is allowed only when no models exist for the given `model_name`. After that, only an explicit `--reset-feature-schema` (intended for breaking changes that warrant a new model name) can change it, and that path archives all prior versions.

### G4 🟡 ONNX preprocessing boundary

Where do encoders / target-encoding lookups execute — inside ONNX, in Java preprocessing, or split? Implementations diverge dramatically.

- **Owning phase**: Phase 1.4 (first ONNX export); refined in Phase 2a.
- **Status**: 🔵 open.
- **Resolution proposal**: Bake numeric scalers and one-hot encoders into ONNX via sklearn → ONNX. Keep target-encoding lookups in Java (read from `feature_pipeline.json`); they're trivially fast and easier to debug than ONNX maps.

### G5 🟢 Game state machine spec

Live polling is "a state machine, not a fixed timer" (decision [89]) but states/transitions aren't enumerated.

- **Owning phase**: Phase 4d / new ingest leaf in Phase 3 (live polling implementation).
- **Status**: 🔵 open.
- **Resolution proposal**: States = `{scheduled, warmup, in_progress, mid_inning, rain_delay, suspended, postponed, completed, postgame}`. Transitions driven by MLB Stats API `gameData.status.detailedState`. Polling interval per state: in_progress → 10–15s, warmup → 60s, others → off. Doubleheaders: re-poll schedule at 1pm and 5pm ET.

### G6 🔴 Time-zone correctness

"Evenings April–October", "2 AM ET batch", "yesterday's predictions" — every cadence/date crosses TZ. No global rule for storing/querying timestamps.

- **Owning phase**: Cross-cutting; locked early to avoid retrofit pain.
- **Status**: 🔵 open.
- **Resolution proposal**: Store everything in ClickHouse and SQLite as UTC (`DateTime64(3, 'UTC')`). Convert to America/New_York at presentation only. Cron schedules use ET via systemd `Timer=*-*-* 06:00:00 America/New_York` (or via `TZ=` environment variable). All log lines emit ISO-8601 UTC.

### G7 🟡 Disk-space ceiling

Cumulative Parquet snapshots ~60GB after 3 years on a personal-desktop SSD. No retention policy described.

- **Owning phase**: Phase 3a (registry).
- **Status**: 🔵 open.
- **Resolution proposal**: Local: keep last 5 versions per model_name; older snapshots offloaded to Cloudflare R2 only (delete local). Registry stores the R2 URI for old versions. Restoring an archived model fetches from R2 on demand. (Storage target switched B2 → R2 per decision [128] / ADR-0007.)

### G8 🟡 ClickHouse retention (TTL)

`prediction_log` partitioned by month but no TTL — will grow unbounded. `drift_metrics` similarly.

- **Owning phase**: Phase 3b for `prediction_log`, Phase 3c for `drift_metrics`.
- **Status**: 🔵 open.
- **Resolution proposal**: `prediction_log` TTL = 18 months (covers a full season + offseason + drift postmortem reach-back). `drift_metrics` TTL = 36 months (cheaper, useful for long-term comparisons). Set via `TTL toYYYYMM(timestamp_col) + INTERVAL N MONTH`.

### G9 🟢 API versioning strategy

`/predict/pitch` vs `/v1/predict/pitch` not specified.

- **Owning phase**: Phase 0.3 (Spring skeleton).
- **Status**: 🔵 open.
- **Resolution proposal**: All public endpoints prefixed `/v1/`. Frontend uses an `API_BASE = '/v1'` constant. v1.5+ models stay on `/v1/` unless a breaking schema change is needed. **No version negotiation** — bump to `/v2/` on breaking change.

### G10 🟡 Auth scope on Ops dashboard

`/admin/*` is HTTP-basic-gated, but the Ops page is described as "recruiter-facing". Are recruiters expected to log in?

- **Owning phase**: Phase 4e (Ops dashboard).
- **Status**: 🔵 open.
- **Resolution proposal**: Split into read-only and write paths. Read path (drift charts, model registry browser, A/B status, reliability diagrams) → public, served from `/v1/ops/*`, no auth. Write path (promotion API, retraining trigger, traffic_pct slider) → `/v1/admin/*`, HTTP-basic. Recruiters see everything; only the user can mutate.

### G11 🟢 Cold-start / inference warm-up

First request after deploy hits an unwarmed ONNX session.

- **Owning phase**: Phase 1.5 / Phase 2a serving leaf.
- **Status**: 🔵 open.
- **Resolution proposal**: On API startup, fire 3 synthetic predictions per active model to warm the JIT and ONNX graph caches. Block `/health` ready until warm-up completes (use `actuator/health/readiness`).

### G12 🔴 Single-machine memory ceiling for backfill

Backfilling 2015–2024 (~6M pitches) won't fit in memory. No streaming/chunking strategy mentioned.

- **Owning phase**: Phase 1.1 (will likely defer the full backfill to Phase 2a, but problem appears immediately for any multi-year load).
- **Status**: 🔵 open.
- **Resolution proposal**: pybaseball pulls in monthly chunks (`statcast(start, end)` 1-month windows). Each chunk written to ClickHouse via streaming INSERT. Never load >1 month into memory. Total wall time ~6–10 hours for full 2015–2024 backfill — run overnight.

---

## Implementation-time gaps (not blockers, must close before relevant phase)

### I1 🟡 SLI / SLO numbers

"98% uptime" stated; per-endpoint p95 latency, error-rate budgets missing.

- **Owning phase**: Phase 0.8 / Phase 0.9 (when monitoring is provisioned).
- **Status**: 🔵 open.
- **Resolution proposal**: Locked SLOs:
  - Availability: 98% monthly (allows ~14 hours downtime/month — generous for self-hosted).
  - `POST /v1/predict/*` p95 latency: <100ms (excluding warm-up).
  - Error rate: <1% non-2xx over 5-min window.
  - Live-polling lag: <30s from MLB Stats API to ClickHouse.

### I2 🟢 Rate limiting on public endpoints

Cloudflare in front, but no app-side rate limit for misbehaving clients.

- **Owning phase**: Phase 0.3 / Phase 4 (when public endpoints exist).
- **Status**: 🔵 open.
- **Resolution proposal**: Bucket4j or Resilience4j RateLimiter, 60 req/min per IP for `/v1/predict/*`. Cloudflare Tunnel preserves real client IP via `Cf-Connecting-Ip` header; trust it (Cloudflare is the only ingress).

### I3 🟢 CORS policy

Vercel frontend → Cloudflare Tunnel backend; preflight, allowed origins undefined.

- **Owning phase**: Phase 0.6 (when frontend first calls backend).
- **Status**: 🔵 open.
- **Resolution proposal**: Spring `CorsConfiguration` allows `https://thebullpen.net` and `https://*.vercel.app` (preview deploys). Methods: GET, POST. No credentials (no cookies — auth is HTTP basic on `/admin/*` only).

### I4 🟡 Secrets management

HTTP basic credential, ClickHouse password, Discord webhook URL, Cloudflare token (Tunnel + R2 S3 credential pair — R2 replaces B2 per [128]/ADR-0007) — no defined home.

- **Owning phase**: Phase 0.3.
- **Status**: 🔵 open.
- **Resolution proposal**: systemd `EnvironmentFile=/etc/thebullpen/secrets.env` (root:root, mode 0600). Documented in [`00-DEPLOYMENT-STRATEGY.md`](00-DEPLOYMENT-STRATEGY.md). Never in git. Rotation runbook in `docs/runbooks/secret-rotation.md`.

### I5 🟢 Testing framework choices

JUnit 5 / Testcontainers / Mockito for Java? pytest for Python? Vitest / Playwright for frontend? None named.

- **Owning phase**: Cross-cutting.
- **Status**: 🟢 closed in [`00-TESTING-STRATEGY.md`](00-TESTING-STRATEGY.md).

### I6 🟢 Frontend data-contract source

OpenAPI from Spring? Hand-written TS types? A monorepo `shared/` package?

- **Owning phase**: Phase 0.3 / Phase 0.6.
- **Status**: 🔵 open.
- **Resolution proposal**: Spring exposes OpenAPI at `/v3/api-docs` via `springdoc-openapi`. Frontend codegen step (`openapi-typescript`) writes to `frontend/src/api/types.ts`. Re-run on backend schema changes. **No** monorepo / shared package.

### I7 🟢 Source-data licensing

pybaseball / Statcast scraping ToS — mention in README.

- **Owning phase**: Phase 5.6 (README).
- **Status**: 🔵 open.
- **Resolution proposal**: README includes a "Data sources" section linking to MLB Stats API ToS, pybaseball repo, Open-Meteo terms. Project explicitly non-commercial; data not redistributed.

### I8 🟢 Cross-process trace correlation

Java emits `trace_id`; Python training jobs don't propagate one.

- **Owning phase**: Phase 3d (retraining job).
- **Status**: 🔵 open.
- **Resolution proposal**: Retraining queue row carries a `trigger_id` (UUID). Python job logs include `trigger_id`. Java emits `trace_id` for the request that enqueued it. Discord alert links the two by including both IDs.

### I9 🟢 Spring profile activation in tests

Tests must not load both `api` and `worker` profile beans simultaneously.

- **Owning phase**: Phase 0.3.
- **Status**: 🔵 open.
- **Resolution proposal**: `@ActiveProfiles("api")` or `@ActiveProfiles("worker")` per test class. Default test profile (`@ActiveProfiles("test")`) loads neither — only domain + data layers.

---

## Contradictions

### C1 🟢 `docs/decisions.md` vs root `decisions.md`

`CLAUDE.md` references `docs/decisions.md`; the original artifact in conversation context was `decisions.md` at root.

- **Status**: 🟢 closed. File written to `docs/decisions.md` per CLAUDE.md.

### C2 🟢 Cron vs systemd timer for retraining

`plan.md` says retraining via systemd timer; `design.md` §9 (GPU scheduling) says "cron-based". Same intent (hourly check, 2–6 AM ET window), different mechanism.

- **Owning phase**: Phase 3d.
- **Status**: 🟢 closed in this register: **systemd timer**. Reasons: (a) all other scheduled work uses systemd in the project (decision [16]); (b) systemd timers integrate with journald which the project uses for logs; (c) cron requires a separate `MAILTO` setup. Update `design.md` §9 to match if/when revisited.

---

## How to use this register

When implementing a leaf plan:

1. Skim this register for entries owned by your phase.
2. If a 🔴 entry is unresolved and your leaf depends on it, **stop and surface to the user** before coding.
3. If a 🟡 or 🟢 entry is unresolved but a resolution proposal exists, follow the proposal — and if you discover the proposal is wrong, propose an alternative as a `decisions.md` ADR before changing course.
4. After the leaf plan ships, update the entry's status row with a link to the closing leaf plan and (optionally) a one-line summary.
5. **New gaps discovered during implementation get added here**, not buried in commit messages.
