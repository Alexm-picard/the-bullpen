# 00-DEPLOYMENT-STRATEGY — How code reaches production

> Production = the WSL2 host on personal desktop, behind Cloudflare Tunnel, plus the Vercel-hosted SPA. There is no other environment. Decision [16], decision [20].

---

## What gets deployed where

| Component                                  | Target                                           | Mechanism                                                  | Trigger                                                              |
| ------------------------------------------ | ------------------------------------------------ | ---------------------------------------------------------- | -------------------------------------------------------------------- |
| Spring API (`api` profile)                 | systemd unit `thebullpen-api.service` on WSL2    | `deploy.sh` rebuilds JAR, `systemctl restart`              | Manual; user runs `./deploy.sh`                                      |
| Spring Worker (`worker` profile)           | systemd unit `thebullpen-worker.service` on WSL2 | Same JAR; restarted by `deploy.sh`                         | Manual                                                               |
| ClickHouse                                 | Docker container managed by systemd unit         | Migrations applied on Spring startup via Flyway            | Migration files in `db/migration/clickhouse/`                        |
| SQLite                                     | File at `/var/lib/thebullpen/registry.sqlite`    | Flyway runs on Spring startup                              | Migration files in `db/migration/sqlite/`                            |
| Cloudflared tunnel                         | systemd unit `cloudflared.service`               | One-time setup; updates via apt                            | OS update cycle                                                      |
| Prometheus + Grafana                       | Docker via systemd                               | Config in `infra/prometheus/`, `infra/grafana/`            | Config-only changes redeployed via `deploy.sh --infra`               |
| Frontend SPA                               | Vercel                                           | Auto-deploy on push to `main`                              | Push to `main`                                                       |
| Backend artifacts (model.onnx, etc.)       | `/var/lib/thebullpen/models/<name>/<version>/`   | Python registry-client copies after training               | Retraining job                                                       |
| Object storage (backups + model artifacts) | Cloudflare R2 bucket `bullpen-prod`              | `backup.sh` via systemd timer + registry on snapshot write | Daily 5 AM ET (was B2; switched to R2 per decision [128] / ADR-0007) |

---

## `deploy.sh` shape (target)

~30 lines, lives at repo root. Idempotent. Aborts on any error.

```bash
#!/usr/bin/env bash
set -euo pipefail

REPO="/home/$USER/thebullpen"
cd "$REPO"

# 0. Don't deploy during live games (April–October evenings ET).
./scripts/check-no-live-games.sh || { echo "Live games — refusing deploy"; exit 1; }

# 1. Pull latest.
git fetch origin
git checkout main
git pull --ff-only origin main

# 2. Build backend JAR.
./mvnw -pl backend -DskipTests=false package

# 3. Stop services.
sudo systemctl stop thebullpen-worker thebullpen-api

# 4. Deploy artifact.
sudo install -o thebullpen -m 0644 backend/target/thebullpen-*.jar /opt/thebullpen/app.jar

# 5. Apply infra changes if --infra flag.
if [[ "${1:-}" == "--infra" ]]; then
  sudo cp infra/systemd/*.service /etc/systemd/system/
  sudo cp infra/systemd/*.timer /etc/systemd/system/
  sudo systemctl daemon-reload
fi

# 6. Start services (worker first so scheduled jobs catch up).
sudo systemctl start thebullpen-worker
sleep 5
sudo systemctl start thebullpen-api

# 7. Smoke check.
./scripts/post-deploy-smoke.sh
```

Frontend deploys are **automatic via Vercel on push to `main`** — no `deploy.sh` involvement. Decision [20].

---

## systemd patterns (locked)

Each service unit follows this shape (drift-detection prevented by linting):

```ini
[Unit]
Description=The Bullpen — <service>
After=network-online.target docker.service clickhouse.service
Requires=clickhouse.service

[Service]
Type=exec
User=thebullpen
Group=thebullpen
EnvironmentFile=/etc/thebullpen/secrets.env
WorkingDirectory=/opt/thebullpen
ExecStart=/usr/bin/java -jar /opt/thebullpen/app.jar --spring.profiles.active=<profile>
Restart=on-failure
RestartSec=10s
StartLimitBurst=5
StartLimitIntervalSec=300s
KillSignal=SIGTERM
TimeoutStopSec=30s
MemoryMax=<cap>
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
```

- `MemoryMax`: API 4G, worker 2G, ClickHouse 8G.
- Always JsonEncoder logback config → journald (no log files, no rotation cron).
- Boot dependency: `Requires=clickhouse.service` so Spring won't start before ClickHouse.

---

## Secrets management

Risk Register I4.

- **Location**: `/etc/thebullpen/secrets.env` (root:root, mode 0600).
- **Format**: `KEY=value`, one per line, no quotes.
- **Required keys**:
  - `THEBULLPEN_ADMIN_BASIC_AUTH` — `username:password` for HTTP basic on `/admin/*`
  - `CLICKHOUSE_PASSWORD`
  - `DISCORD_WEBHOOK_URL`
  - `B2_ACCOUNT_ID`, `B2_APPLICATION_KEY`
  - `BETTER_STACK_HEARTBEAT_URL`, `HEALTHCHECKS_IO_PING_URL`
  - `OPEN_METEO_API_KEY` (if rate-limited tier needed)
- **Rotation runbook**: `ops/runbooks/secret-rotation.md`. Steps: generate new value → update `secrets.env` → `systemctl restart` services → verify with curl probe.
- **Never in git.** Never in environment dumps. Never in log lines (Logback filters defined in `00-OBSERVABILITY-STRATEGY.md`).

---

## Migrations

- **SQLite**: Flyway runs on Spring startup. Forward-only. `V001__init.sql`, `V002__add_routing.sql`, etc.
- **ClickHouse**: same. `V001__pitches.sql`, `V002__prediction_log.sql`, etc.
- **Backwards-compatible always.** Add a column nullable; backfill in a separate migration; constrain in a third migration. Never drop a column in the same release as the code that stops using it (one release lag).

---

## CI/CD (locked)

- **GitHub Actions** runs tests + build on every push (decision [20]).
- CI does **not** auto-deploy backend (decision [20]).
- Vercel auto-deploys frontend on push to `main` (decision [10]).
- Workflow files live in `.github/workflows/`:
  - `test-java.yml` — JUnit + Testcontainers
  - `test-python.yml` — pytest + leakage suite (Phase 2+)
  - `test-frontend.yml` — Vitest + typecheck + Lighthouse budget
  - `e2e-smoke.yml` — Playwright against a temp backend (Phase 1+)

---

## Operational rules (verbatim)

1. **No deploys during live games.** Decision [21]. Evenings April–October ET. Enforced by `scripts/check-no-live-games.sh` reading the day's MLB schedule.
2. **Reboot drill required before season.** Decision [15]. `sudo reboot` and verify all health checks green. Documented in `ops/runbooks/reboot-drill.md`.
3. **Restore drill required before season.** Decision [14]. Pull latest R2 backup → restore to a fresh ClickHouse → run `SELECT count(*) FROM pitches` and verify count matches expected. Documented in `ops/runbooks/restore-drill.md`.

---

## What we explicitly do NOT do (decision [16] / design.md §9)

- ❌ Kubernetes / Docker Swarm / orchestration. One machine.
- ❌ Terraform / Pulumi. No cloud infrastructure to manage.
- ❌ Blue-green / canary deployment. A/B at the model layer instead (decision [69]–[73]).
- ❌ Centralized log aggregation. journalctl is enough.
- ❌ APM SaaS (Datadog, New Relic). Prometheus + Grafana cover this.
- ❌ Auto-deploy of backend. Manual `./deploy.sh` only.
- ❌ Hot-reload of model artifacts. Restart `thebullpen-api` to pick up new models. (Restart is sub-5s thanks to virtual threads + warm-up.)

---

## Public DNS / TLS

- `thebullpen.net` → Vercel (frontend).
- `api.thebullpen.net` → Cloudflare Tunnel → WSL2:8080.
- Cloudflare manages TLS for both. No Let's Encrypt setup needed.
- WSL2 ports never exposed publicly. The tunnel is the only ingress.
