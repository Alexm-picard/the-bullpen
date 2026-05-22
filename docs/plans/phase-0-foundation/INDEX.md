# Phase 0 — Foundation · INDEX

> All infrastructure exists, even if empty. Reboot recovers cleanly.
> Weeks 1–3 · ~40–50 hours. See [`../../plan.md`](../../plan.md) §Phase 0.
>
> **Phase exit criterion**: `sudo reboot` recovers everything in <5 min, all health checks green, frontend reachable at domain.
>
> **MVP cuts**: NONE. Foundation cannot be cut (Discipline Rule 3 in [`../00-MASTER.md`](../00-MASTER.md)). If >3 weeks, diagnose blocker, don't cut.

---

## Cross-cutting docs to read alongside any leaf in this phase

- [`../00-MASTER.md`](../00-MASTER.md)
- [`../00-CONVENTIONS.md`](../00-CONVENTIONS.md)
- [`../00-DEPLOYMENT-STRATEGY.md`](../00-DEPLOYMENT-STRATEGY.md) — systemd patterns, secrets, deploy.sh
- [`../00-OBSERVABILITY-STRATEGY.md`](../00-OBSERVABILITY-STRATEGY.md) — log schema, metric naming
- [`../../design.md`](../../design.md) §2, §6, §9

---

## Leaf plans

Each leaf plan is authored just-in-time using [`../00-PLAN-TEMPLATE.md`](../00-PLAN-TEMPLATE.md). Author the file at the path shown when work begins.

### 0.1 — `0.1-wsl-systemd-bootstrap.md`

WSL2 setup, systemd-genie or built-in systemd, memory cap (32 GB), CUDA passthrough verified end-to-end via `nvidia-smi` inside WSL.

- **Closes / addresses**: foundation for everything; partial close on G6 (TZ).
- **Acceptance**: `systemctl status` works, `nvidia-smi` shows the GPU inside WSL, system reboot survives.

### 0.2 — `0.2-cloudflare-domain-tunnel.md`

Domain registration, Cloudflare DNS records (`thebullpen.net` → Vercel; `api.thebullpen.net` → Tunnel), `cloudflared` installed and managed by systemd, tunnel pointed at hello-world Spring endpoint.

- **Decisions referenced**: [9].
- **Acceptance**: `curl https://api.thebullpen.net/hello` returns 200 from internet.

### 0.3 — `0.3-spring-skeleton.md`

Maven-based Spring Boot 3.x skeleton, Java 21, virtual threads enabled. Two profiles (`api`, `worker`). Two systemd units. `/actuator/health` per profile. `springdoc-openapi` exposing `/v3/api-docs`. Bean Validation wired. `MockMvc` test class scaffolded.

- **Decisions referenced**: [22], [24], [25], [29].
- **Closes / addresses**: G9 (API versioning — fix prefix to `/v1/` here), I6 (OpenAPI source), I9 (test profile activation).
- **Acceptance**: both systemd units start, both `/actuator/health` return 200, `/v3/api-docs` returns valid JSON.

### 0.4 — `0.4-clickhouse-docker.md`

ClickHouse via Docker, managed by systemd unit. Spring connects via clickhouse-jdbc. Flyway migration directory created with V001 (empty schema baseline). One smoke INSERT/SELECT round-trip from Spring.

- **Decisions referenced**: [16], [31], [33].
- **Acceptance**: Spring app logs show successful ClickHouse connection on startup; smoke test passes.

### 0.5 — `0.5-sqlite-flyway.md`

SQLite file at `/var/lib/thebullpen/registry.sqlite`. Flyway migration directory + V001 baseline (empty). Spring repository scaffolded with one no-op query. Tests use embedded SQLite (in-memory or temp file).

- **Decisions referenced**: [32], [33].
- **Acceptance**: app starts, file exists, Flyway version table populated.

### 0.6 — `0.6-react-vite-vercel.md`

Vite + React + TypeScript scaffold. Mantine + Tailwind installed. TanStack Query installed. One page calling Spring `/v1/health` via CORS, displaying response. Deployed to Vercel auto-deploy.

- **Decisions referenced**: [10], [93], [95], [109].
- **Closes / addresses**: I3 (CORS).
- **Acceptance**: `https://thebullpen.net` loads; the page shows live response from `https://api.thebullpen.net/v1/health`.

### 0.7 — `0.7-ci-and-deploy-script.md`

GitHub Actions: `test-java.yml`, `test-frontend.yml`. `deploy.sh` at repo root (~30 lines). `scripts/check-no-live-games.sh` returning 0 for now (placeholder).

- **Decisions referenced**: [20], [21].
- **Acceptance**: pushing to `main` triggers Actions; `./deploy.sh` runs end-to-end on the host.

### 0.8 — `0.8-prometheus-grafana.md`

Prometheus container + node_exporter. Grafana with one trivial dashboard ("Application overview"). Prometheus scrapes Spring `/actuator/prometheus`.

- **Decisions referenced**: [16], [18].
- **Closes / addresses**: I1 (SLOs documented in dashboard panels).
- **Acceptance**: Grafana shows live request-rate panel.

### 0.9 — `0.9-external-monitoring.md`

Better Stack HTTP probe configured against `/actuator/health`. Healthchecks.io heartbeat URL added to `secrets.env`. Discord webhook integration test fires from a CLI script. Alert thresholds documented in [`../00-OBSERVABILITY-STRATEGY.md`](../00-OBSERVABILITY-STRATEGY.md) verified.

- **Decisions referenced**: [17], [18].
- **Closes / addresses**: I4 (secrets management exercised end-to-end).
- **Acceptance**: take API down, Better Stack pages within 1 min; Discord webhook fires manually.

### 0.10 — `0.10-backup-restore-drill.md` ★ **Phase 0 exit gate**

clickhouse-backup runs nightly via systemd timer → rclone → Cloudflare R2 (originally Backblaze B2; switched per decision [128] / ADR-0007). Retention 7-4-12. **Restore drill executed**: pull a backup, restore to a fresh ClickHouse, verify `count(*)` matches.

- **Decisions referenced**: [13], [14], [128].
- **Acceptance**: a documented restore drill (`ops/runbooks/restore-drill.md`) and a screenshot or log line proving it ran successfully. Without this, Phase 0 is not complete.

---

## Reboot drill

Decision [15] / Discipline Rule 8. After 0.10 ships, run:

```bash
sudo reboot
# (wait)
ssh back in
systemctl status thebullpen-api thebullpen-worker clickhouse cloudflared
curl https://api.thebullpen.net/actuator/health
curl https://thebullpen.net
```

If anything is red, file a child leaf plan to fix it. **Phase 0 does not complete until reboot is clean.**
