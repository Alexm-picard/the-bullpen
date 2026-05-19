# Plan

> **Project**: The Bullpen (`thebullpen.net`)
> **Total estimate**: ~460–585 hours / ~8–10 months calendar at 12–15h/week
> **Status**: Pre-implementation
> **Last updated**: 2026-05-09

This is the working plan. For full rationale on any decision, see
`design.md`. For chronological decision history, see `decisions.md`.

---

## The single most important rule

**Build the demoable spine first, thicken it later.**

The dominant failure mode for portfolio projects of this scope is
horizontal building — getting all of data done, then all of models,
then all of backend, then all of frontend. By month 5, lots of
half-systems and nothing clickable. Motivation collapses. Project dies.

The right pattern: **thin vertical slice end-to-end as fast as possible,
then thicken it.** A working prediction visible in a browser by week 6,
even if the model is dumb and the data is one season. After that, every
piece improves in passes.

---

## Phase 0: Foundation (Weeks 1–3) | ~40–50 hours

**Goal**: All infrastructure exists, even if empty. Reboot recovers cleanly.

### Build
- WSL2 setup: systemd enabled, memory cap (e.g., 32GB), CUDA passthrough verified
- Domain registration, Cloudflare DNS, Cloudflare Tunnel pointed at hello-world
- Spring Boot skeleton with `api` and `worker` profiles
- Both as systemd services with health endpoints
- ClickHouse via Docker, accessible from Spring
- SQLite + Flyway running
- React + Vite skeleton, deployed to Vercel, calling Spring health endpoint via CORS
- GitHub repo, Actions running tests on push, deploy.sh script working
- Prometheus + Grafana with one trivial dashboard
- Better Stack monitoring `/health`
- Backup of empty ClickHouse to USB + restore drill (with empty data)
- **ADR system established** under `docs/adr/`, first 5 ADRs written covering the locked decisions from `design.md` §10 (Java not Kotlin, ONNX in-process not sidecar, ClickHouse not Postgres-only, Mantine+Tailwind not pure Tailwind, polling not WebSockets). Template at `docs/adr/TEMPLATE.md`. `decisions.md` stays as the chronological flat log; ADRs are the depth layer for substantial decisions.
- **Structured JSON logging baseline** — `logback-json` on the Java side, `structlog` on the Python side, correlation IDs propagated through every request via MDC (Java) and contextvars (Python). `LOG_FORMAT=json` env var.
- **Multi-stage Dockerfile** for the Spring JAR: builder stage (Gradle) + slim runtime (`eclipse-temurin:21-jre-alpine`), non-root `appuser` (UID 1001), explicit `HEALTHCHECK` against `/actuator/health`.

### Exit criterion
**`sudo reboot` recovers everything in <5 min, all health checks green,
frontend reachable at domain.**

### MVP cuts
**None.** Foundation cannot be cut. If it's taking >3 weeks, diagnose
the blocker immediately. Most likely: WSL/CUDA setup (allocate one
weekend specifically), or DNS confusion (use Cloudflare defaults).

---

## Phase 1: Vertical Slice (Weeks 4–7) | ~50–65 hours

**Goal**: ONE prediction visible end-to-end, in the browser, deployed.

### Build
- Historical Statcast pull (one season, ~2024) → `raw_statcast`
- Cleaning into `pitches` with proper schema and dedup
- **Minimal** batted-ball model: LightGBM, 5 features, single output (not 30 parks). Not yet calibrated, not properly eval'd.
- ONNX export, Java loading, parity test passing
- Spring endpoint: `POST /predict/batted-ball`
- Park Explorer page with hardcoded list of historical batted balls
- Click → see prediction render in browser
- Primitive prediction logging to ClickHouse

### Exit criterion
**Visit `thebullpen.net/parks`, click a batted ball, see real prediction in <500ms end-to-end.**

### MVP cuts
This phase IS the credibility floor. Anything from here forward is the
real project.

---

## Phase 2: The Real Models (Weeks 8–17) | ~140–180 hours

**Goal**: Three calibrated models with eval artifacts.

### Phase 2a: Pitch outcome — pre-pitch head (Weeks 8–10)
- Full feature pipeline: all 4 tiers, target encoding with strict temporal cutoffs
- Leakage tests in CI (4 categories: future contamination, shuffled-target, calendar-date, ID consistency)
- **Property tests for the leakage detector itself** (Hypothesis) — generate random temporally-shuffled datasets, assert the 4 leakage tests fire correctly on both known-leaky and known-clean inputs. Proves the leakage tests aren't fooling themselves.
- **Coverage floor 75% enforced** in CI for `training/` (matches StudyForesight bar).
- **Contract tests on the API** via Schemathesis against Spring's `/v3/api-docs` — catches drift between the documented OpenAPI contract and the implementation. Required-to-merge CI job.
- Rolling-origin temporal CV harness (4 folds: 2015–2025)
- LightGBM training, isotonic calibration
- Logistic regression baseline (registered, kept as permanent reference)
- Eval artifact directory: metrics, reliability diagrams, segment metrics, feature importance
- Registered in registry, served at `/predict/pitch`
- Forward simulator (analytical + Monte Carlo), wired up

### Phase 2b: Pitch outcome — post-pitch head (Weeks 11–12)
- Reuses 2a infrastructure
- Adds Tier 4 post-pitch features
- Different model_name in registry
- Eval artifact

### Phase 2c: Batted-ball with physics retrodiction (Weeks 13–17)
- **Physics simulator (~200 lines Python)**: Nathan's drag/Magnus ODE, RK4 integration
- **Validation**: reproduce 100 known Statcast trajectories within tolerance
- **Park geometry data**: scrape + manual curation
- **Retrodiction labeling pipeline**: 30 outcomes per BIP
- **Multi-output MLP**: shared backbone + 30 per-park heads
- **30 isotonic calibrators**, one per park
- **Cross-park sanity tests**
- **LightGBM Option-A baseline** (park as categorical, single model)
- Eval artifact with explicit MLP-vs-LGBM comparison

### Exit criterion
**Three models registered, all with eval artifacts, all served via Spring,
all with passing leakage tests in CI. ECE < 0.02 on test data per model.**

### MVP cuts (in priority order if behind)

| When | Cut | Saves | Cost |
|---|---|---|---|
| End of Wk 12 if behind | Drop post-pitch head from v1, pre-pitch only | ~20h | Lose replay-analysis use case |
| End of Wk 15 if 2c at risk | Drop physics retrodiction, fall back to per-park naive subsets | ~25h | **Painful — model weakened**. Document honestly. |

**Hard rule: NEVER cut the eval artifact.** Models without eval are
screenshots, not systems.

---

## Phase 3: ML Systems Wrapper (Weeks 18–22) | ~80–100 hours

**Goal**: registry, A/B, drift, retraining — the FAANG-grade signal.

### Phase 3a: Registry (Weeks 18–19)
- 4-stage lifecycle (candidate / shadow / champion / archived)
- Promotion API + admin endpoints
- Feature schema hashing enforcement at registration
- Training data snapshotting tied to model lifecycle

### Phase 3b: A/B Routing (Weeks 19–20)
- Shadow-mode default + real-A/B path (game_id Murmur3 bucketing)
- Pre-declared promotion criteria + experiment_results table
- Async batched logging to ClickHouse `prediction_log`

### Phase 3c: Drift Detection (Weeks 20–21)
- Daily batch: PSI per feature, PSI on predictions, calibration on observed outcomes
- Weekly batch: per-segment metrics, long-window comparisons
- ClickHouse `drift_metrics` table
- Synthetic drift tests (inject known shifts, verify detection)
- Alerting policy: page / notice / logged-only via Discord webhook

### Phase 3d: Retraining Triggers (Weeks 21–22)
- `retraining_queue` table
- Three triggers: scheduled (monthly floor) + drift-based (calibration > 1.5x for 7d) + manual
- Python retraining job processes queue
- systemd timer for 2–6 AM ET window
- End-to-end retrain test

### Phase 3 hardening additions (concurrent with 3a–3d)
- **JMH benchmark suite** for the inference path (ONNX session prediction p50/p99, A/B router decision time, calibrator apply time). Wired into CI via `scripts/check_benchmarks.py` comparing against the previous commit's numbers; fails on >25% regression (matches StudyForesight's threshold).
- **Repository pattern formalized in `data/`** — five explicit aggregates: `ModelRegistryRepository`, `ExperimentResultsRepository`, `PredictionLogRepository`, `DriftMetricsRepository`, `RetrainingQueueRepository`. Each owns its table(s) with a typed API; no `JdbcTemplate` calls leak outside `data/`.

### Exit criterion
**Trigger retrain manually → new candidate registered with eval → promote
through shadow → champion via API → traffic shifts visible in logs → old
champion archived. Full lifecycle, end-to-end.**

### MVP cuts (in priority order if behind)

| When | Cut | Saves | Cost |
|---|---|---|---|
| End of Wk 20 if behind | Cut real-A/B path; keep shadow only | ~10h | Still demonstrates harder discipline (paired eval) |
| End of Wk 22 if behind | Cut automated drift triggering; keep drift measurement, manual retrains | ~5h | Drift detector still demonstrates depth |

**Hard rule: NEVER cut the registry.** It's the spine; everything else attaches.

---

## Phase 4: Frontend Build-Out (Weeks 23–30) | ~70–90 hours

**Goal**: 5 pages exist and demonstrate the system meaningfully.

### Phase 4a: Design system tokens (Week 23)
Mantine + Tailwind config with all design tokens:
- Source Serif loaded
- Color palette as CSS vars
- Type scale as utility classes
- Locked discipline rules: hex codes in components = defects

### Phase 4b: Player Lookup (Weeks 24–25)
Search, profile, prediction history, calibration plot. Simplest analytical
page — good warmup.

### Phase 4c: Park Explorer (Weeks 25–27) — MARQUEE
- 30-stadium HR probability heatmap
- Sliders for launch parameters (debounced)
- **Largest single component, highest variance in visual quality**
- Build basic version FIRST (simple grid of mini-charts with colors); iterate to polished.
- Don't try to nail it on first attempt.

### Phase 4d: Game / Live view (Weeks 27–28)
- Live polling (TanStack Query, 10–15s)
- Pitch-by-pitch feed with model predictions
- Real-time updates via cache invalidation

### Phase 4e: Ops Dashboard (Week 29)
- Model registry browser
- Drift charts
- A/B status
- Retraining queue
- Reliability diagrams per model version
- **This is the recruiter-facing page**

### Phase 4f: About / Methodology (Week 30)
Editorial visual treatment. Source Serif headlines. Long-form prose.

### Phase 4 hardening additions (do during 4a token work, before component build)
- **TS strict flags enabled** in `tsconfig.app.json`: `noUnusedLocals`, `noUnusedParameters`, `noImplicitReturns`, `noUncheckedIndexedAccess`. Pay the migration cost early when the codebase is small.
- **Security headers on the Vercel-served frontend**: COOP, CORP, CSP `frame-ancestors 'none'`. Configured in `vercel.json`.

### Exit criterion
**All 5 pages exist, all have loading/error/empty states, Lighthouse > 80,
bundle < 300KB gzipped initial.**

### MVP cuts (in priority order if behind)

| When | Cut | Saves | Cost |
|---|---|---|---|
| End of Wk 28 if behind | Drop Game / Live view | ~12h | Lose live-watching demo (Park Explorer "today's BIPs" partially compensates) |
| End of Wk 30 if behind | Cut visual ambition on About page | ~10h | About becomes minimal-functional |

**Hard rule: NEVER cut the Ops dashboard.** It's the recruiter-clicked page.

---

## Phase 5: Polish + Operate (Weeks 31–38+) | ~80–100 hours

**Goal**: Public launch. Operate through season. Write postmortems.

### Build
- Polish phase across all pages: typography, spacing, color, motion, accessibility
- One specific iteration on Park Explorer heatmap: "fine" → "memorable"
- Performance optimizations (bundle audit, image optimization, lazy loading)
- README rewrite, design decisions doc, methodology page final content
- **Public launch**: post to r/baseball, r/sabermetrics, r/programming, HN; share to network
- **Operate the system through the rest of the MLB season**
- **Write the mid-season drift postmortem** — the centerpiece artifact
- **Nightly k6 load test** against the deployed API. SLA gate: p99 prediction latency < 50ms, error rate < 0.1%. Runs via GitHub Actions cron, posts annotations to Grafana per run, pings Discord on failure.
- **Top-level `docs/runbooks/ROLLBACK.md`** covering all rollback scenarios (deploy / migration / model promotion / drift retrain). Each scenario: detection signal → decision criteria → exact commands → verification steps. The doc on-call you reads at 2am.

### Exit criterion
**Project publicly accessible, README links to all artifacts, ≥1 drift
event observed and documented in postmortem, system running ≥4 weeks
with documented uptime.**

### MVP cuts
**Extend timeline rather than cut.** Polish compounds; cutting it produces
a worse final artifact.

**Exception**: if dragging into October and season is ending, accept
"ships in winter, postmortem in 2027 spring training" as the actual
timeline. Don't force a partial postmortem on insufficient data.

---

## Phase 5.5: Mid-Season Hardening Sweep (concurrent with Phase 5, ~10–20 hours)

**Goal**: Take ~2 weeks of dedicated time, mid-season or end-of-season, to do a
deliberate hardening pass on whatever you observed during operations — then
produce a single artifact (`docs/hardening/{date}_sweep.md`) with an Impact
table showing before/after metrics for each item.

This is the artifact that specifically maps to StudyForesight's "Q2 2026
hardening sweep" Impact table. It is what makes an L5 reviewer screenshot the
README. Without this, the "I operated this through a season" story is
incomplete — operation without follow-through is just deployment.

### Build

- **Keep `docs/hardening/observations.md` during operation** — a running list of
  anything that surprised you. Slow queries, perf bottlenecks, observability
  gaps, places where the discipline rules saved you, places where they failed,
  ergonomic friction in your own runbooks. Low-friction: just append.
- **Triage at sweep start**: pick the top 8–15 items by impact-to-effort ratio.
- **Implement** each, with a **measurable before/after**. Examples: query
  latency p99, false-positive rate on drift detector, time-to-rollback in
  drill, RLS coverage count.
- **Write `docs/hardening/{date}_sweep.md`** with the Impact table:
  `| Area | Before | After | Where |` — mirror StudyForesight's Q2 sweep
  layout exactly; it's a known-good pattern.
- **Update ADRs** for any decisions revised during the sweep. Add a Revision
  History entry on each affected ADR.
- **Reference the sweep doc** from the README (Phase 6) and from the drift
  postmortem (Phase 5). They are a matched set.

### Exit criterion

**`docs/hardening/{date}_sweep.md` exists with ≥8 items, each with before/after
metrics and a file/PR reference. README and Phase 6 hiring work both link to it.**

### MVP cuts

**Do not cut the artifact.** If energy is limited, *shrink the number of items*
in the table — but still produce the document. Even 5 honest hardening items
with before/after metrics is better than nothing. A missing sweep doc undoes
the work of Phases 0–5 from an L5-evaluation standpoint.

---

## Phase 6: Hiring Readiness (post-Phase-5, ongoing) | ~30–50 hours

**Goal**: Make the engineering legible to a hiring audience. Phase 5
ships the engineering; Phase 6 makes the engineering *land*.

This phase is unusual in that it can run concurrent with Phase 5 calendar-wise
(write the README during the season, file the OSS PR whenever you hit a real
bug, draft the postmortem as drift events happen). The hour estimate assumes
deliberate dedicated time even if it overlaps the Phase 5 weeks.

The standard here is **"clear the bar by a mile"**, not "clear the bar."
Phases 0–5 are the engineering investment; if Phase 6 produces a forgettable
README and a blog-format postmortem, that investment doesn't compound.

### Build

- **README rewrite to "people screenshot and share" quality**
  - One-paragraph origin story (baseball → ML systems framing → why self-hosted)
  - Ops Dashboard screenshot above the fold
  - Live link to the dashboard (kept accessible post-launch)
  - Link to the drift postmortem
  - Tech-decisions section pulling highlights from `docs/decisions.md` with one-line rationales (do not link to the raw log; summarize)
  - "Why these tech choices over the obvious alternatives" — short, references `design.md` §10
- **One merged OSS PR in a project adjacent to The Bullpen's stack**
  - Substantive code, not a docs typo
  - Targets in priority: `onnxruntime` (Java bindings), `clickhouse-java`/`clickhouse-jdbc`, `lightgbm`, `pybaseball`, `mantine`, `tanstack/query`, `taste-skill`, `frontend-design`
  - Only file when you genuinely hit a bug worth fixing during the build — manufactured contributions read worse than no contribution
  - Goal: one defensible answer to "tell me about working with a maintainer through code review"
- **Drift postmortem in real SRE format** (not blog format)
  - Steal the structure from real published postmortems — Cloudflare, GitHub, Stripe, Fly.io
  - Sections: TL;DR (3 lines), Timeline (detection → mitigation → resolution with timestamps), Root Cause (Five Whys), Impact (what was wrong with predictions for how long), What Went Well / What Went Poorly, Lessons Learned, Action Items (with owners and dates — even if owner is always "me"), Runbook Updates
  - If the season is too quiet to produce a natural drift event, **induce one** in a controlled way (inject a known shift, watch detection fire, write up the response) and document the synthetic nature honestly
- **Lessons-learned doc** separate from the postmortem
  - Broader than a single incident; covers the whole season of operation
  - What you'd architect differently, what surprised you, which discipline rules paid off vs felt like overhead, what you'd tell yourself if starting again
  - Demonstrates *reflection* — the trait that separates "junior who shipped" from "junior who'll grow fast"
- **60-second verbal pitch rehearsed**
  - Practice it spoken, not just written. If it feels stilted, rewrite. HMs can tell a memorized script from a story you actually believe.
- **Keep the Ops Dashboard accessible post-launch with realistic data**
  - Recruiters will visit months later
  - Either keep ingest running on a low cadence in the off-season, or freeze the dashboard at a known-good state with a banner noting the snapshot date
  - A dead dashboard is worse than no dashboard

### Exit criterion

**A hiring manager visiting the GitHub repo for 60 seconds gets the right
impression and can find the postmortem, the Ops Dashboard, and proof of
collaboration without scrolling. The project is interview-ready as a
90-second verbal walkthrough that you can deliver naturally.**

### MVP cuts

**Do not cut.** This phase IS the resume work; cutting it wastes Phases 0–5.

If energy is depleted by Phase 5, **extend the calendar** for Phase 6 rather
than reduce the scope. The README, postmortem, and OSS PR all benefit from
the time. A rushed README is worse than a delayed one.

---

## The honest review schedule

Every two weeks, sit down for 30 minutes. Ask:

1. Am I on the exit criterion for this phase?
2. If not, by how many days/hours behind?
3. If behind by ≥1 week, which soft cut do I take *now*?

**Cuts made early are surgical. Cuts made late are amputations.**

If you fall behind by 4 weeks total, escalate the review: re-scope the
*project*, not just the phase. Better to ship a smaller version on time
than a bigger version never.

---

## Soft-cut priority list (consolidated, in order)

If behind, cut in this order:

1. Drop pitch post-pitch head, keep pre-pitch only (saves ~20h)
2. Drop A/B real-routing, keep shadow only (saves ~10h)
3. Drop automated drift retraining, keep manual (saves ~5h)
4. Drop Game/Live view (saves ~12h)
5. Drop physics retrodiction, fall back to Path B for batted-ball (saves ~25h, weakens model significantly)

**Never cut**: foundation (Phase 0), eval artifacts, model registry, Ops dashboard, Phase 5.5 hardening-sweep artifact, Phase 6 hiring-readiness work.

---

## What's left for v1.5 (after launch)

Cherry-pick from this list rather than committing upfront:

- Sequence transformer challenger via shadow mode
- Path A physics retrodiction (if Phase 2c fell back)
- ABS challenge model
- Half-inning extension to forward simulator
- Pitcher/batter learned embeddings (uses GPU more)
- Visual ambition pass on Park Explorer (WebGL/3D)
- Dark mode
- Catcher framing / umpire features for pitch model
- Sequence features (previous N pitches)

---

## Discipline rules (non-negotiable, written down so I read them)

1. **No design tokens drift.** Hex codes in component files are defects.
2. **No deploys during live games.** Evenings April–October, hands off.
3. **No cuts to Phase 0.** Diagnose blockers; don't paper over.
4. **No cuts to eval artifacts.** Models without eval aren't models.
5. **No cuts to the registry.** Spine; everything attaches.
6. **No cuts to the Ops dashboard.** Recruiter-facing page.
7. **No skipping the restore drill.** Untested backups don't exist.
8. **No skipping the reboot drill.** Untested recovery means unreliable system.
9. **No promotion without pre-declared criteria.** Discipline that distinguishes the project.
10. **No auto-promotion of retrained models.** Human in the loop.

---

## Energy management

Real solo capacity for technical work after a day job is **8–12 hours/week
sustainable**, not 15–20. Pushing higher leads to burnout. The 12–15
range I've planned around is realistic but assumes good weeks.

**8–10 months calendar is the realistic floor**, not optimistic. Plan
shipping in early 2027 with in-season operation in 2027's first half if
2026 finish is tight.

Ship-during-offseason is fine. Operation continues into the season; the
drift postmortem is what matters and can be captured in 2027.
