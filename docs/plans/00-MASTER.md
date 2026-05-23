# 00-MASTER — The Bullpen Execution Plan

> Top of the execution tree. Every leaf plan lives below this document.
> When in doubt, read this file, then `00-CONVENTIONS.md`, then the
> relevant phase `INDEX.md`, then the leaf plan you intend to execute.

---

## Frame

The Bullpen (`thebullpen.net`) is a self-hosted baseball-analytics platform with a custom-built ML systems wrapper (registry, A/B routing, drift detection, retraining triggers) serving three calibrated models. Solo developer, ~8–10 calendar months at 12–15h/week, operated through one MLB season for a real drift postmortem. **It is not a SaaS, not a betting tool, not a research contribution** — it is a FAANG-credible portfolio piece engineered to demonstrate ML systems engineering on tabular data.

For full context: [`../design.md`](../design.md) §1.

---

## Architecture snapshot

```
                                Cloudflare
                            ┌──────────────┐
                            │ Tunnel + DNS │
                            └──────┬───────┘
                                   │
                ┌──────────────────┼──────────────────┐
                ▼                  ▼                  ▼
         Vercel (frontend)   thebullpen/api    thebullpen/api/admin
                                   │
                            WSL2 (Ubuntu 24.04)
                                   │
                ┌──────────────────┼──────────────────┐
                ▼                  ▼                  ▼
         Spring API           Spring Worker      ClickHouse (Docker)
         (systemd, :8080)     (systemd, :8081)   (:8123 / :9000)
                  │                  │                 │
                  └─────── SQLite (registry, A/B, queue) ───────┘
                                   │
                  ONNX Runtime Java (in-process inference)
                                   │
                Python training (off serving path, ONNX export)
```

| Box               | One-line role                                                               |
| ----------------- | --------------------------------------------------------------------------- |
| Vercel            | Static React SPA (`thebullpen.net`)                                         |
| Cloudflare        | DNS + Tunnel; no port forwarding, no public IP                              |
| Spring API        | `@Profile("api")` — controllers, ONNX inference, async logger               |
| Spring Worker     | `@Profile("worker")` — `@Scheduled` ingest, drift, retraining               |
| ClickHouse        | `pitches`, `pitches_live`, `drift_metrics`, `prediction_log`, weather       |
| SQLite            | `model_versions`, `model_routing`, `experiment_results`, `retraining_queue` |
| ONNX Runtime Java | Loads model.onnx + applies isotonic calibration in-process                  |
| Python            | Training only; produces ONNX + metadata + Parquet snapshot                  |

Full design: [`../design.md`](../design.md) §2.

---

## Phase exit criteria — running scoreboard

| #   | Phase              | Weeks  | Hours   | Exit criterion                                                                                                 | Status         |
| --- | ------------------ | ------ | ------- | -------------------------------------------------------------------------------------------------------------- | -------------- |
| 0   | Foundation         | 1–3    | 40–50   | `sudo reboot` recovers everything in <5 min, all health checks green, frontend reachable at domain.            | ⬜ not started |
| 1   | Vertical slice     | 4–7    | 50–65   | Visit `thebullpen.net/parks`, click a batted ball, see real prediction in <500 ms end-to-end.                  | ⬜ not started |
| 2   | Real models        | 8–17   | 140–180 | Three models registered with eval artifacts; CI leakage tests pass; ECE < 0.02 per model.                      | ⬜ not started |
| 3   | ML wrapper         | 18–22  | 80–100  | Manual retrain → candidate → shadow → champion via API; traffic shifts visible in logs; old champion archived. | ⬜ not started |
| 4   | Frontend build-out | 23–30  | 70–90   | All 5 pages exist; loading/error/empty states; Lighthouse > 80; bundle < 300 KB gz.                            | ⬜ not started |
| 5   | Polish + operate   | 31–38+ | 80–100  | Public; ≥1 real drift event documented in postmortem; system running ≥4 weeks with documented uptime.          | ⬜ not started |

Update the Status column when a phase completes. Append a one-line entry to the Status Log at the bottom.

---

## Phase tree (every leaf plan)

Leaf plan files are authored **just-in-time** at the start of their phase, not all up-front. The phase `INDEX.md` lists every planned leaf with a one-line description; the leaf file is created when work on that leaf begins.

### Phase 0 — Foundation → [`phase-0-foundation/INDEX.md`](phase-0-foundation/INDEX.md)

- 0.1 — WSL2 + systemd bootstrap
- 0.2 — Cloudflare domain + tunnel
- 0.3 — Spring skeleton (`api` + `worker` profiles)
- 0.4 — ClickHouse via Docker
- 0.5 — SQLite + Flyway
- 0.6 — React + Vite + Vercel
- 0.7 — CI + `deploy.sh`
- 0.8 — Prometheus + Grafana
- 0.9 — External monitoring (Uptime Robot + Healthchecks.io + Discord)
- 0.10 — Backup + restore drill (Phase 0 exit gate)

### Phase 1 — Vertical Slice → [`phase-1-vertical-slice/INDEX.md`](phase-1-vertical-slice/INDEX.md)

- 1.1 — Statcast 2024 historical pull
- 1.2 — `pitches` cleaning + dedup
- 1.3 — Toy batted-ball model (LightGBM, 5 features, 1 output)
- 1.4 — ONNX export + Java load + parity test
- 1.5 — `POST /predict/batted-ball` endpoint
- 1.6 — Park Explorer toy page
- 1.7 — Primitive prediction logging

### Phase 2 — The Real Models → [`phase-2-models/INDEX.md`](phase-2-models/INDEX.md)

- 2a Pitch pre-pitch head (9 leaves)
- 2b Pitch post-pitch head (3 leaves)
- 2c Batted-ball with physics retrodiction (9 leaves)

### Phase 3 — ML Systems Wrapper → [`phase-3-ml-wrapper/INDEX.md`](phase-3-ml-wrapper/INDEX.md)

- 3a Registry (5 leaves)
- 3b A/B routing (5 leaves)
- 3c Drift detection (7 leaves)
- 3d Retraining triggers (4 leaves)

### Phase 4 — Frontend Build-Out → [`phase-4-frontend/INDEX.md`](phase-4-frontend/INDEX.md)

- 4a Design tokens
- 4b Player Lookup (3 leaves)
- 4c Park Explorer (4 leaves) — MARQUEE
- 4d Game / Live view (2 leaves)
- 4e Ops Dashboard (5 leaves) — RECRUITER-FACING
- 4f About / Methodology

### Phase 5 — Polish + Operate → [`phase-5-polish/INDEX.md`](phase-5-polish/INDEX.md)

- 5.1 Typography pass
- 5.2 Color audit (catch hex-code defects)
- 5.3 Perf / bundle audit
- 5.4 Accessibility audit
- 5.5 Park Explorer polish iteration
- 5.6 README rewrite + public launch
- 5.7 Drift postmortem template

---

## Cross-cutting docs

Read alongside any leaf plan that needs them:

- [`00-CONVENTIONS.md`](00-CONVENTIONS.md) — Java / Python / TS code style, naming, commit format, ADR template
- [`00-RISK-REGISTER.md`](00-RISK-REGISTER.md) — known gaps, open questions, owning phases
- [`00-TESTING-STRATEGY.md`](00-TESTING-STRATEGY.md) — what unit / integration / leakage / e2e tests look like, CI gates per phase
- [`00-DEPLOYMENT-STRATEGY.md`](00-DEPLOYMENT-STRATEGY.md) — `deploy.sh`, systemd patterns, secret management, no-deploys-during-games rule
- [`00-OBSERVABILITY-STRATEGY.md`](00-OBSERVABILITY-STRATEGY.md) — log JSON schema, Micrometer naming, Grafana dashboard inventory, Discord alert templates
- [`00-PLAN-TEMPLATE.md`](00-PLAN-TEMPLATE.md) — the canonical shape every leaf plan follows

---

## Soft-cut priority order (in this order, never out of order)

If a phase exit criterion is at risk at the 2-week review, cut in this order:

1. ⬜ Drop pitch post-pitch head, keep pre-pitch only (~20 h)
2. ⬜ Drop A/B real-routing, keep shadow only (~10 h)
3. ⬜ Drop automated drift retraining, keep manual (~5 h)
4. ⬜ Drop Game/Live view (~12 h)
5. ⬜ Drop physics retrodiction, fall back to per-park naive subsets (~25 h, **weakens model**, document honestly)

When a cut is taken, change ⬜ to ✂️ and append a Status Log entry naming the soft-cut number, the date, and the trigger.

**Never cut**: Phase 0, eval artifacts, model registry, Ops dashboard.

---

## Discipline rules (verbatim from `CLAUDE.md`)

These exist because past failure modes are known. Don't relax without explicit user approval.

1. **Build the demoable spine first, thicken it later.** No horizontal building.
2. **No design tokens drift.** Hex codes in component files are defects.
3. **No deploys during live games** (evenings April–October).
4. **No cuts to**: Phase 0 foundation, eval artifacts, the model registry, the Ops dashboard.
5. **No promotion of a model without pre-declared promotion criteria** and a passing row in `experiment_results`.
6. **No auto-promotion of retrained models** — retraining is automated, promotion stays human-gated.
7. **Feature schema hashing is enforced at registration** — refuse models whose schema hash doesn't match the production feature pipeline.
8. **Restore drill and reboot drill must run before season starts.** Untested backups / untested recovery don't count.
9. **Two heads = two separate models** in the registry (pre-pitch / post-pitch). Not one model with feature masking.
10. **All rolling/form features computed via streaming temporal cutoff.** Leakage tests in CI are non-negotiable.

---

## How to use this plan tree (operating instructions for an AI agent)

When working on this project:

1. **Always start with three files in context**: [`../CLAUDE.md`](../../CLAUDE.md), this `00-MASTER.md`, and [`00-CONVENTIONS.md`](00-CONVENTIONS.md). They fit easily and answer most "is this allowed?" questions.
2. **Before authoring a leaf plan**: read the phase `INDEX.md`, the relevant `design.md` section, and any upstream leaf plans listed as dependencies.
3. **Before executing a leaf plan**: read the leaf plan in full, plus its declared upstream dependencies. If you need to look at code, use Grep/Read directly — do not load entire files into context speculatively.
4. **Never modify a leaf plan during execution** to record what you actually did. Instead, append to the Status Log here. Plans are inputs; commit messages and the Status Log are outputs.
5. **If you discover a contradiction** between docs (e.g., `design.md` says X, `decisions.md` says Y): do **not** silently pick one. Add an entry to [`00-RISK-REGISTER.md`](00-RISK-REGISTER.md), surface it to the user, and proceed only after explicit resolution.
6. **If a locked decision is being revisited**: do **not** quietly change `design.md`. Append a new numbered entry to [`../decisions.md`](../decisions.md) referencing the decision being reversed, in the format documented at the bottom of that file.
7. **Phase boundaries are real**: do not start work on Phase 2 leaves before Phase 1's exit criterion is met. The vertical slice exists to discover problems early; jumping ahead defeats it.
8. **Keep the context small**: average leaf plan + master + conventions + relevant `design.md` section ≈ 6 files, well under any context budget. If you find yourself loading more than 8 files, you're either off-plan or the leaf plan is too big — surface the latter as a refactor candidate.

---

## Status Log

> Append-only. One line per significant event: phase completion, soft cut taken, decision reversal, drift incident, postmortem published.

| Date       | Event                                                                  |
| ---------- | ---------------------------------------------------------------------- |
| 2026-05-09 | Planning session complete; design.md / plan.md / decisions.md locked.  |
| 2026-05-10 | Execution-plan tree (this directory) bootstrapped. Pre-implementation. |
