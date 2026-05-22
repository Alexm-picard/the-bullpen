# Decisions

> Chronological log of every decision locked during the planning session.
> One line of rationale per entry. For full reasoning, see `design.md`.

Format: `[N] DATE — DECISION — RATIONALE`

---

## Project framing

`[1]` 2026-05-09 — **Project #2 targets a gap, not a duplicate of StudyForesight** — same shape twice = ~20% credit on second.

`[2]` 2026-05-09 — **Domain: baseball analytics + ML systems engineering** — personal engagement + clean differentiation from StudyForesight.

`[3]` 2026-05-09 — **Drop the betting framing entirely** — regulatory exposure + polarizing resume signal + interesting work survives without it.

`[4]` 2026-05-09 — **Drop the real-time on-screen overlay** — undifferentiated engineering, scope killer.

`[5]` 2026-05-09 — **Drop the RAG chatbot for baseball stats** — duplicates StudyForesight architecturally.

`[6]` 2026-05-09 — **Drop the Pi cluster from project #2** — bolt-on distributed systems on a CRUD-shaped app reads as fluff.

`[7]` 2026-05-09 — **Project #2 = baseball + ML systems; Project #3 = distributed systems on the Pis** — two coherent projects, no cross-coupling.

---

## Hosting & infrastructure

`[8]` 2026-05-09 — **Self-hosted on personal desktop, not cloud** — costs ~$5/month electricity vs. cloud free tiers' cold-start/timeout issues.

`[9]` 2026-05-09 — **Cloudflare Tunnel for public access** — no port forwarding, no public IP exposure, free, real subdomain.

`[10]` 2026-05-09 — **Frontend on Vercel** — free tier ample for portfolio traffic.

`[11]` 2026-05-09 — **98% uptime target with monitoring + auto-restart, no failover** — honest, defensible, doesn't couple Project 3.

`[12]` 2026-05-09 — **Each project stands alone; Project 3 is NOT failover for Project 2** — entanglement guts both narratives.

`[13]` 2026-05-09 — **Backup: clickhouse-backup → rclone → Backblaze B2, 7-4-12 retention** — free, durable, off-machine.

`[14]` 2026-05-09 — **Verified restore drill required before season starts** — untested backups aren't backups.

`[15]` 2026-05-09 — **Windows 11 + WSL2 (Ubuntu 24.04 LTS)** — keeps daily Windows use, all services in WSL, CUDA passthrough works.

`[16]` 2026-05-09 — **Bare-metal systemd for application code; Docker for stateful data services** — direct JVM management for app, container isolation for ClickHouse/Prometheus.

`[17]` 2026-05-09 — **External monitoring via Better Stack + Healthchecks.io** — internal Prometheus can't tell me when the host is down.

`[18]` 2026-05-09 — **Discord webhook as alert channel** — durable incident log doubles as postmortem source material.

`[19]` 2026-05-09 — **GPU scheduling: systemd timers, 2–6 AM ET window, self-healing on failure** — cron-based simple version; v1.5 can add job queue if needed.

`[20]` 2026-05-09 — **Manual deploys via local script; no auto-deploy of backend** — one-developer projects don't need elaborate CI/CD; deploy when intended, not when CI passes.

`[21]` 2026-05-09 — **No deploys during live games (evenings April–October)** — operational discipline, not technical control.

---

## Backend stack

`[22]` 2026-05-09 — **Java 21, Spring Boot 3.x for the backend** — JVM/Spring is the FAANG-credible enterprise signal absent from StudyForesight.

`[23]` 2026-05-09 — **Strict Java, no Kotlin in the mix** — recruiter ATS greps "Java"; mixing weakens the keyword signal.

`[24]` 2026-05-09 — **Java 21 virtual threads, Spring MVC (not WebFlux)** — virtual threads make blocking I/O cheap; reactive Spring is unnecessary complexity.

`[25]` 2026-05-09 — **Profile-based monolith: one JAR, two systemd units (`api`, `worker`)** — runtime separation without two-binary deployment overhead.

`[26]` 2026-05-09 — **Python for training only; no Python on the serving path** — clean Java/Python split via ONNX file contract.

`[27]` 2026-05-09 — **ONNX Runtime Java for in-process inference** — no Python sidecar, no live RPC.

`[28]` 2026-05-09 — **Python → Java contract: ONNX + JSON metadata + feature_pipeline.json + Parquet snapshot** — file-based, hash-validated at registration.

`[29]` 2026-05-09 — **No auth on user-facing endpoints; HTTP basic on `/admin/*` only** — no users; full auth would duplicate StudyForesight work for zero new signal.

`[30]` 2026-05-09 — **Async batched logging to ClickHouse, drop on overflow** — never backpressure the inference path; logging is best-effort.

---

## Databases

`[31]` 2026-05-09 — **ClickHouse for analytical data (pitches, drift metrics, prediction logs)** — different paradigm from StudyForesight's Postgres+pgvector; FAANG-adjacent OLAP signal.

`[32]` 2026-05-09 — **SQLite for app state (model registry, A/B config, retraining queue)** — different paradigm from ClickHouse, transactional integrity for OLTP.

`[33]` 2026-05-09 — **Two databases by workload, not one** — workload-appropriate tooling is itself a signal.

---

## Models — pitch outcome

`[34]` 2026-05-09 — **Pitch outcome model output: 5 coarse classes (ball/called/swinging/foul/in-play)** — BIP outcomes hand off to batted-ball model; clean factorization.

`[35]` 2026-05-09 — **Two heads, two separate models (pre-pitch + post-pitch), NOT feature masking** — cleaner eval per head, honest separate metrics, better drift story.

`[36]` 2026-05-09 — **LightGBM (multinomial) as primary architecture** — dominant on tabular ML at this scale per published benchmarks.

`[37]` 2026-05-09 — **Logistic regression baseline trained alongside, registered permanently** — establishes floor, catches bugs, drift comparator.

`[38]` 2026-05-09 — **Isotonic regression per class on temporal holdout for calibration** — multiple eval surfaces; reliability diagrams in artifact.

`[39]` 2026-05-09 — **Target encoding for pitcher_id and batter_id with strict pre-game cutoff** — leakage-safe at high cardinality (~700 pitchers).

`[40]` 2026-05-09 — **All rolling form features computed via streaming temporal cutoff** — leakage-safe by construction.

`[41]` 2026-05-09 — **Defer catcher, umpire, sequence, weather, slumps from pitch model v1** — keep feature set tight; v1.5 candidates.

`[42]` 2026-05-09 — **v1.5: structured-data transformer as shadow-mode challenger to LightGBM** — best-of-both-worlds story; tests the platform.

`[43]` 2026-05-09 — **Reject LLM (text-based) for pitch outcome prediction** — wrong tool, 100× operational cost for worse predictions.

`[44]` 2026-05-09 — **Reject auto-promotion of retrained models** — defeats shadow + criteria discipline; humans check.

---

## Models — batted-ball / park effect

`[45]` 2026-05-09 — **Batted-ball architecture: multi-output MLP with shared backbone + 30 per-park heads** — single inference call yields all 30 parks; cleaner per-park calibration; better resume framing.

`[46]` 2026-05-09 — **LightGBM Option-A baseline (park-as-categorical) registered for direct comparison** — validates whether the multi-output design is worth the complexity.

`[47]` 2026-05-09 — **Path A: physics-retrodicted labels via Nathan's drag/Magnus ODE simulator** — escalated from v1.5 to v1 after user pushback; right answer for this problem.

`[48]` 2026-05-09 — **Reject PINN for ball-flight modeling** — physics is forward-solvable ODE not PDE; PINNs are wrong tool.

`[49]` 2026-05-09 — **Physics simulator validates against 100 known Statcast trajectories before any training run** — bug-prevention precondition.

`[50]` 2026-05-09 — **Park geometry NOT a feature** — Option B's whole point is that output heads learn park geometry implicitly.

`[51]` 2026-05-09 — **30 isotonic calibrators, one per park** — per-park calibration story; reliability diagrams per park.

`[52]` 2026-05-09 — **Cross-park sanity tests required** — model must produce monotonic park-HR-rate ordering for canonical inputs.

---

## Forward simulation

`[53]` 2026-05-09 — **Plate-appearance length as Markov chain forward simulation, not a separate model** — derive PA-length, K%, BB% from per-pitch model.

`[54]` 2026-05-09 — **Both analytical (fundamental matrix) and Monte Carlo implementations** — production endpoint analytical, diagnostic endpoint MC; convergence test catches bugs.

`[55]` 2026-05-09 — **Half-inning extension deferred to v1.5** — natural follow-on, low marginal effort post-launch.

---

## Eval methodology

`[56]` 2026-05-09 — **Rolling-origin temporal CV, 4 folds spanning 2015–2025** — strictly more rigorous than single split; variance is itself a metric.

`[57]` 2026-05-09 — **Headline metrics reported as mean ± std-dev across folds** — honest reporting; error bars build credibility with careful reviewers.

`[58]` 2026-05-09 — **Reject random train/test split** — leaks pitcher/batter histories; catastrophic for baseball ML.

`[59]` 2026-05-09 — **Within-fold split granularity by date, NEVER by game or pitch** — within-game leakage of game effects.

`[60]` 2026-05-09 — **Primary metrics: Brier (multi-class), log loss, ECE** — calibrated probabilistic models need calibration metrics.

`[61]` 2026-05-09 — **Per-segment metrics by handedness, park, count, inning, month-of-season** — within-season non-stationarity is real.

`[62]` 2026-05-09 — **Eval artifact directory per model version** — versioned, reproducible, machine-readable; ships alongside model.

`[63]` 2026-05-09 — **4 leakage tests in CI: future contamination, shuffled-target, calendar-date trace, ID consistency** — non-negotiable; prove the pipeline is clean.

`[64]` 2026-05-09 — **Synthetic drift tests required before drift detector trusted in production** — inject known shifts, verify detection.

---

## ML systems wrapper

`[65]` 2026-05-09 — **Custom-build the model registry in Spring + SQLite, NOT MLflow** — building it custom is the resume signal; MLflow reduces it to "an integration."

`[66]` 2026-05-09 — **4-stage lifecycle: candidate → shadow → champion → archived** — shadow stage is the operationally critical one most projects skip.

`[67]` 2026-05-09 — **Feature schema hashing enforced at registration** — prevents the dominant production-ML failure mode (silent feature pipeline drift).

`[68]` 2026-05-09 — **Training data versioning: full Parquet snapshots (Option B), not hash + windowed pull (Option A)** — MLB historical data is mutable; bitwise reproducibility matters.

`[69]` 2026-05-09 — **Default eval mode: shadow (run both, return champion, log both)** — paired comparisons are statistically dominant; no feedback loop in this domain.

`[70]` 2026-05-09 — **Real A/B available but reserved for cases where shadow can't answer** — A/B is for feedback-loop domains, which this isn't.

`[71]` 2026-05-09 — **Murmur3 hash on game_id for A/B bucketing, 1000 buckets** — deterministic, sticky, no client state required.

`[72]` 2026-05-09 — **Pre-declared promotion criteria + experiment_results table; no promotion without a passing record** — discipline that distinguishes ML platform engineering from "I built A/B testing."

`[73]` 2026-05-09 — **Manual stepwise A/B ramping (1% → 5% → 25% → 50%)** — implemented as a single slider; ramp is bumping the number with metric checks.

`[74]` 2026-05-09 — **Three drift types tracked separately: data, prediction, concept** — most projects conflate; we don't.

`[75]` 2026-05-09 — **PSI for continuous drift, chi-squared for categorical** — interpretable, well-known, simple.

`[76]` 2026-05-09 — **Calibration error / Brier on observed outcomes is the most important metric** — concept drift is the failure mode that matters most for probabilistic models.

`[77]` 2026-05-09 — **Daily and weekly cadences, not real-time** — alert fatigue prevention; baseball games run on daily cadence.

`[78]` 2026-05-09 — **Alerting policy: page / notice / logged-only, with documented thresholds** — written down in README.

`[79]` 2026-05-09 — **Hybrid retraining triggers: scheduled monthly (floor) + drift-based (ceiling) + manual button** — pure-drift creates feedback loop with detector; pure-scheduled wastes/under-reacts.

`[80]` 2026-05-09 — **Drift retrain trigger threshold (7 days) tighter than alert threshold (3 days)** — 4-day gap is the human investigation window.

`[81]` 2026-05-09 — **Hyperparameters fixed within retrain run; HP search is v1.5 explore phase** — reproducibility, training-time bounds.

`[82]` 2026-05-09 — **Commit to operating the system through the 2026 (and into 2027) MLB season** — drift postmortem with real data is the centerpiece resume artifact.

---

## Data pipeline

`[83]` 2026-05-09 — **Three pipelines: historical backfill / nightly incremental / live polling — separate code, shared schemas** — different failure semantics; unifying creates regression risk.

`[84]` 2026-05-09 — **Three storage layers: raw_statcast → pitches → features** — raw layer is truth; cleaned layer is contract; features layer is training input.

`[85]` 2026-05-09 — **Live data in separate `pitches_live` table; nightly handoff to canonical `pitches`** — sparse live schema doesn't pollute canonical.

`[86]` 2026-05-09 — **Data sources: MLB Stats API + pybaseball + Open-Meteo + static park dimensions** — locked, no others.

`[87]` 2026-05-09 — **Reject ESPN as backup data source** — ToS-questionable, undocumented, unstable.

`[88]` 2026-05-09 — **Weather: separate pre-game forecast pull AND post-game observed pull** — prevents serving/training feature skew; forecast accuracy itself becomes signal.

`[89]` 2026-05-09 — **Live polling implemented as game state machine, not fixed timer** — handles doubleheaders, postponements, suspended games.

`[90]` 2026-05-09 — **Per-stage SQL assertions, fail-loud, alert-integrated** — Great Expectations is overkill; plain SQL is enough.

`[91]` 2026-05-09 — **No Airflow / Prefect / Dagster** — Spring `@Scheduled` and cron is enough for this scale.

`[92]` 2026-05-09 — **Pitch identity: (game_id, at_bat_index, pitch_number); ReplacingMergeTree for dedup** — natural PK, ClickHouse-native dedup.

---

## Frontend

`[93]` 2026-05-09 — **React + TypeScript + Vite, pure SPA** — already known; SEO doesn't matter for portfolio site.

`[94]` 2026-05-09 — **Reject Next.js / SSR** — marginal cost not worth it for backend-heavy portfolio project.

`[95]` 2026-05-09 — **TanStack Query for server state; plain React + Context for client state** — no Redux/Zustand.

`[96]` 2026-05-09 — **Polling via TanStack Query for live updates; reject WebSockets** — coordination overhead not justified at polling cadence.

`[97]` 2026-05-09 — **5 pages: Game / Player / Park Explorer / Ops / About — locked** — anything else is scope creep.

`[98]` 2026-05-09 — **Park Explorer (30-park heatmap) is the marquee component, ~50–70 hours allocated** — visual identity payoff lives here.

`[99]` 2026-05-09 — **Ops dashboard is the recruiter-facing page, never cut** — model registry browser, drift charts, A/B status, reliability diagrams.

---

## Design system

`[100]` 2026-05-09 — **Visual identity: editorial-data (Observable structural + Pudding aspirational + Athletic typography)** — fits the product; achievable in timeline.

`[101]` 2026-05-09 — **Reject Lusion-tier visual ambition across the project** — marketing-site rhetoric fights analytical content; +6 months of work; deferred to a separate creative project.

`[102]` 2026-05-09 — **Visual ambition allowed on About + Park Explorer; restraint on other pages** — user pushed for more, agreed in compromise form.

`[103]` 2026-05-09 — **Typography: Inter (UI) + JetBrains Mono (data) + Source Serif 4 (display)** — three-font system; serif used boldly at large sizes.

`[104]` 2026-05-09 — **Type scale: 1.25 modular, 16px base, tabular figures always on** — discipline in scale, no arbitrary sizes.

`[105]` 2026-05-09 — **Color palette: warm off-white background (#FAFAF7), warm near-black text (#161513), brick-red accent (#B53D2C)** — editorial feel; one accent used sparingly.

`[106]` 2026-05-09 — **Viridis for sequential data viz; rejected rainbow scales** — perceptually uniform, colorblind-safe.

`[107]` 2026-05-09 — **Reject gradients, drop shadows, "primary blue"** — SaaS-marketing rhetoric.

`[108]` 2026-05-09 — **No dark mode in v1** — 15–20 hours of work, deferred to v1.5.

`[109]` 2026-05-09 — **Mantine + Tailwind for components** — different from StudyForesight's library; data-dense interfaces.

`[110]` 2026-05-09 — **Spacing: 8-point grid (4, 8, 12, 16, 24, 32, 48, 64, 96)** — locked tokens.

`[111]` 2026-05-09 — **Three layout patterns: editorial (max 720px) / analytical with sidebar (max 1200px) / marquee (full-width)** — density tells you the page's purpose.

`[112]` 2026-05-09 — **Motion: functional only, 150–300ms, no entrance animations** — `cubic-bezier(0.4, 0, 0.2, 1)` default.

`[113]` 2026-05-09 — **Polish phase at end of build (~30–50 hours)** — typography, spacing, color, motion, accessibility audit; cohesion compounds at the end.

`[114]` 2026-05-09 — **Reject pre-frontend "design phase"** — design in isolation drifts on contact with components.

---

## Process / discipline

`[115]` 2026-05-09 — **Build the demoable spine first; thicken it** — vertical slice end-to-end by Phase 1 exit; no horizontal building.

`[116]` 2026-05-09 — **Phase ordering: foundation → vertical slice → models → wrapper → frontend → polish** — wrapper after models because wrapper needs real customers.

`[117]` 2026-05-09 — **Honest progress review every 2 weeks** — cuts made early are surgical; cuts made late are amputations.

`[118]` 2026-05-09 — **Soft cuts ordered by priority** — pitch post-pitch (1) → A/B real-routing (2) → automated drift triggering (3) → Game/Live view (4) → physics retrodiction (5).

`[119]` 2026-05-09 — **Hard rule: never cut foundation, eval artifacts, registry, or Ops dashboard** — these are the spine and recruiter-facing pieces.

`[120]` 2026-05-09 — **Document maintenance**: design.md / plan.md / decisions.md updated when decisions revise; reversed decisions stay in place with reversal note appended — **history is part of the artifact.**

---

## Phase 0 — initial ADRs (depth layer for the locked tech choices)

`[121]` 2026-05-19 — **Strict Java 21, no Kotlin** — protects the "Java" ATS signal that frames the project; see ADR-0001 (depth of [22] [23] [24]).

`[122]` 2026-05-19 — **ONNX Runtime Java for in-process inference, no Python sidecar on the serving path** — file-based Python↔Java contract keeps p99 < 50ms and the operational story single-process; see ADR-0002 (depth of [26] [27] [28] [30]).

`[123]` 2026-05-19 — **ClickHouse for analytics + SQLite for app state, not Postgres-only** — workload-matched stores; different paradigm from StudyForesight; see ADR-0003 (depth of [31] [32] [33]).

`[124]` 2026-05-19 — **Mantine for components + Tailwind for layout, not pure Tailwind/shadcn** — data-dense pages need real primitives; tokens shared between Mantine theme and Tailwind config; see ADR-0004 (depth of [109]).

`[125]` 2026-05-19 — **HTTP polling via TanStack Query, no WebSockets** — keeps the backend stateless and Cloudflare Tunnel boring; 10s/30s cadence is enough for actual screens; see ADR-0005 (depth of [95] [96]).

---

## How to update this file

When a decision is locked, add it as the next numbered entry with date + one-line rationale.

When a decision is reversed, **don't delete the original**. Add a new entry referencing it:

> `[N]` 2026-XX-XX — **Reverse decision [M] (DESCRIPTION)** — RATIONALE FOR REVERSAL.

This lets future-you (and recruiters reading the repo) see how thinking evolved. ADRs naturally fall out of this discipline.

---

## Phase 0 — operational discipline ADRs (dev environment + storage)

`[126]` 2026-05-21 — **Local dev on macOS, prod on self-hosted Linux desktop; deploy crosses git** — no code edits on the prod box; `deploy.sh` (decision [20]) is the only authoring boundary; protects [11]'s 98 % uptime SLO and keeps the hiring-readiness deploy story honest; see ADR-0006.

`[127]` 2026-05-21 — **All object storage via S3-compatible client; `S3_ENDPOINT_URL` is the only environment-specific knob** — B2 in prod, MinIO on the portable drive for offline dev; one code path for backups (decision [13]) and model artifacts (decisions [28] [68]); no `file://` storage code paths; see ADR-0007.

`[128]` 2026-05-21 — **Reverse decisions [13] and [127]'s B2 endpoint; use Cloudflare R2 instead for all object storage** — same S3-compatible abstraction (ADR-0007's discipline survives, only the endpoint string changes); consolidates on Cloudflare (already DNS + Tunnel, planned monitor → fewer vendors / credentials); R2 has $0 egress at portfolio scale and a 10 GB free tier that covers Phase-0 traffic. Reversed before any code was written against B2 — caught during Group B credentialing while still authoring rclone config. ADR-0007 amended via Revision History (status stays Accepted; the abstraction is unchanged).
