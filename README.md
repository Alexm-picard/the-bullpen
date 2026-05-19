# The Bullpen

A self-hosted baseball analytics platform with a custom ML systems wrapper —
model registry, A/B routing, drift detection, retraining triggers — serving
three calibrated models (pitch outcome pre-pitch, pitch outcome post-pitch,
batted ball per-park HR probability) for the duration of an MLB season.

**Status:** Phase 0 (foundation) — Mac-side scaffolding complete, all three
CI workflows green on `main`, Vercel deploy live, transitioning to WSL2 for
host-side completion (systemd, Cloudflare Tunnel, backups, restore drill).
See [`docs/phase-status.json`](docs/phase-status.json) and
[`docs/sessions/2026-05-19-wsl2-handoff.md`](docs/sessions/2026-05-19-wsl2-handoff.md).

- **Live frontend** (once Vercel Auth is disabled): https://the-bullpen-ml-git-main-alexmpicards-projects.vercel.app/
- **Repo**: https://github.com/Alexm-picard/the-bullpen

**Read first:** [`docs/design.md`](docs/design.md),
[`docs/plan.md`](docs/plan.md), [`docs/decisions.md`](docs/decisions.md),
[`CLAUDE.md`](CLAUDE.md). Most "obvious" alternatives have been rejected with
written rationale — check before re-litigating.

---

## Repository layout

```
thebullpen/
├── backend/        Java 21 + Spring Boot 3 (Gradle Kotlin DSL)
├── training/       Python 3.11 (uv) — model training, eval, ONNX export
├── frontend/       React 18 + TypeScript + Vite + Mantine + Tailwind
├── contracts/      Canonical Python↔Java file contract (feature_pipeline.json)
├── infra/          docker-compose, Prometheus + Grafana provisioning, backup scripts
├── docs/           design.md, plan.md, decisions.md, adr/, drills/, deploys/
├── .githooks/      pre-commit (schema_hash discipline)
└── deploy.sh       Phase 0 deploy stub — prefer the deploy-safely skill
```

## Local dev — quickstart

### Stateful services (ClickHouse, Prometheus, Grafana)

```bash
docker compose -f infra/docker-compose.yml up -d
# ClickHouse  http://localhost:8123   (default / thebullpen)
# Prometheus  http://localhost:9090
# Grafana     http://localhost:3000   (admin / admin)
```

### Backend

```bash
cd backend
./gradlew bootRun --args='--spring.profiles.active=api'
# http://localhost:8080/health
# http://localhost:8080/actuator/health
# http://localhost:8080/actuator/prometheus
```

Worker profile binds 8081: `./gradlew bootRun --args='--spring.profiles.active=worker'`.

### Training

```bash
cd training
uv sync
uv run pytest                # smoke + leakage placeholder
uv run ruff check
uv run pyright
```

### Frontend

```bash
cd frontend
npm install
npm run dev                  # http://localhost:5173 — calls Spring /health via CORS
npm run build                # production bundle to dist/
```

## Discipline rules (non-negotiable)

1. **Build the demoable spine first.** Vertical slice end-to-end is the
   credibility floor; no horizontal building.
2. **No hex codes in component files.** Mantine theme tokens or Tailwind
   theme colors only.
3. **No deploys during live games** (evenings April–October).
4. **No cuts** to: Phase 0 foundation, eval artifacts, the model registry,
   the Ops dashboard, Phase 6 hiring-readiness work.
5. **No promotion of a model without pre-declared promotion criteria.**
6. **No auto-promotion of retrained models** — retraining is automated,
   promotion stays human-gated.
7. **Feature schema hashing is enforced at registration** — refuse models
   whose schema hash doesn't match `contracts/feature_pipeline.json`.
8. **Restore + reboot drills run before the season starts.**
9. **Two heads = two separate models** in the registry (pre-pitch +
   post-pitch).
10. **All rolling/form features computed via streaming temporal cutoff.**
    Leakage tests in CI are non-negotiable.

Full discipline list and rationale in [`CLAUDE.md`](CLAUDE.md) and
[`docs/plan.md`](docs/plan.md).

## ADRs

The depth-layer architectural records live in [`docs/adr/`](docs/adr/).
[`docs/decisions.md`](docs/decisions.md) is the chronological flat log;
ADRs are the long-form record for the top ~15% of decisions.

- [ADR-0001 — Java 21, not Kotlin](docs/adr/0001-java-not-kotlin.md)
- [ADR-0002 — ONNX in-process, not Python sidecar](docs/adr/0002-onnx-in-process-not-python-sidecar.md)
- [ADR-0003 — ClickHouse + SQLite, not Postgres-only](docs/adr/0003-clickhouse-plus-sqlite-not-postgres-only.md)
- [ADR-0004 — Mantine + Tailwind, not pure Tailwind](docs/adr/0004-mantine-plus-tailwind-not-pure-tailwind.md)
- [ADR-0005 — Polling, not WebSockets](docs/adr/0005-polling-not-websockets.md)
