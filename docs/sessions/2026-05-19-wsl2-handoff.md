# Session handoff: macOS → WSL2 — Phase 0 continuation

**Written**: 2026-05-19, end of macOS dev session
**Audience**: future-Claude (after the user opens Claude Code on WSL2) and future-self

This document captures Phase 0 state precisely enough that work can resume on
WSL2 without re-deriving context. The repo at HEAD is the source of truth;
this doc explains _why_ HEAD looks the way it does and what comes next.

---

## What the next prompt should say

After you've cloned the repo on WSL2 and launched Claude Code from inside it,
paste this as your first prompt:

> Read `docs/sessions/2026-05-19-wsl2-handoff.md` and continue Phase 0 from
> where we left off. The remaining items are host-side (systemd, Cloudflare
> Tunnel, USB backup + restore drill, reboot drill).

That's it. The handoff doc + the repo state will reorient Claude.

---

## Phase 0 status at handoff time

Per `docs/phase-status.json` (the canonical tracker — `/status` reads from it):

### Done on macOS (these check items are `done: true`)

1. Spring Boot skeleton with `api` + `worker` profiles, virtual threads on
2. ClickHouse 24.12 via Docker (in `infra/docker-compose.yml`)
3. SQLite + Flyway running — `V1__init_registry.sql` creates `schema_meta`
4. React + Vite skeleton calling Spring `/health` via CORS
5. Prometheus + Grafana with a provisioned JVM-overview dashboard
6. ADR system + first 5 ADRs (`docs/adr/0001`–`0005`)
7. Structured JSON logging (logback-json on Java side, structlog on Python
   side, correlation ID propagated end-to-end)
8. Multi-stage Dockerfile for the Spring JAR (non-root UID 1001 + HEALTHCHECK)
   — **verified locally**: image builds, container boots, HEALTHCHECK turns
   `healthy` within 1s of boot

### Verified additionally during this session (not on the original checklist)

- **GitHub Actions all green** on `main`. Three workflows: `backend`,
  `frontend`, `training`. Each passes on its respective `paths` filter.
- **Vercel deploy is `READY`** from commit `2fbfdc3` (the first push). URL:
  `the-bullpen-ml-git-main-alexmpicards-projects.vercel.app` (currently gated
  by Vercel Authentication — see Open Items below).
- **GitHub labels** all created (39 total — type/sev/area/phase/status per
  `.github/labels.md`).
- **ClickHouse backup primitives verified**: bash syntax clean,
  `sqlite3 .backup` produces a 24KB registry snapshot,
  `clickhouse-client` reachable inside the Docker container,
  `ALTER TABLE … FREEZE` produces shadow parts in
  `/var/lib/clickhouse/shadow/...` as expected.

### Still pending (these check items are `done: false`)

| #   | Item                                                           | Notes                                                                                                          |
| --- | -------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------- |
| 1   | **WSL2 setup** — systemd enabled, memory cap, CUDA passthrough | Needs to happen on the WSL2 box itself                                                                         |
| 2   | **Domain + Cloudflare DNS + Tunnel**                           | DNS half can be done from any browser (instructions below). Tunnel daemon needs WSL2.                          |
| 3   | **Both as systemd services with `/health` endpoints**          | Service unit files need to live on the WSL2 box's `/etc/systemd/system/`                                       |
| 4   | **`deploy.sh` end-to-end working**                             | Script exists; full path includes rsync to the WSL2 host + `systemctl restart`, which only works once #3 lands |
| 5   | **Better Stack monitoring `/health`**                          | Needs the public hostname from the Cloudflare Tunnel (so #2 first)                                             |
| 6   | **ClickHouse backup to USB + restore drill (empty data)**      | Needs WSL2 + physical USB drive                                                                                |

Once #1–#6 are done, Phase 0 exit criterion (`sudo reboot` recovers
everything in <5 min, all health checks green, frontend reachable at domain)
is testable.

---

## Open items the user (alex) needs to decide / act on

### A. **Vercel Authentication** (Settings → Deployment Protection)

The current Vercel deploy is gated by Vercel SSO. To make the frontend
publicly viewable:

> Vercel dashboard → project `the-bullpen-ml` → **Settings → Deployment
> Protection → Vercel Authentication → Disabled** (or "Only Preview
> Deployments")

One-click change. Until done, `https://the-bullpen-ml-git-main-alexmpicards-projects.vercel.app/`
returns a Vercel SSO login page instead of the React app.

### B. **Repo visibility: keep private or go public?**

Branch protection on `main` requires either:

- A public repo (free), or
- GitHub Pro on a private repo ($4/mo).

Phase 6 will publish the repo anyway. Doing it now means:

- Branch protection works for free.
- Public GitHub Actions minutes are unlimited (private has a cap).
- The repo is in the user's portfolio earlier.
- Early commits are public — which is what "portfolio project" implies.

If staying private until Phase 6 is preferred, branch protection waits.
The decision is logged here so it doesn't get lost.

### C. **Cloudflare DNS walkthrough (from prior in-session message)**

User owns `thebullpen.net` (confirmed in conversation). Subdomain plan
chosen: root + www → Vercel, `api.*` → WSL2 Tunnel.

1. **Add the domain to Cloudflare**
   - https://dash.cloudflare.com → "Add a domain" → `thebullpen.net` → Free plan
   - Cloudflare will give two nameservers (e.g., `nina.ns.cloudflare.com` /
     `walt.ns.cloudflare.com`)

2. **Switch nameservers at the registrar**
   - Replace the registrar's default NS with Cloudflare's two
   - Wait 5 min – few hours; Cloudflare flips domain to "Active"

3. **Connect apex + www to Vercel**
   - Vercel → project `the-bullpen-ml` → Settings → Domains → "Add Domain" →
     `thebullpen.net`. Vercel shows DNS records to add.
   - In Cloudflare DNS: add the CNAME (or A) records Vercel asked for.
     **Set Proxy status: DNS only (gray cloud)** — Vercel terminates TLS;
     Cloudflare proxy fights with that.
   - Verify by visiting `https://thebullpen.net` — should serve the React app
     (assuming Vercel Authentication is off — see A).

4. **`api.thebullpen.net` is reserved for the Tunnel** — created automatically
   in step 5 (WSL2).

### D. **Discord webhook for alerts** (decision [18])

Create now (no WSL2 needed): Discord → server → channel → Integrations →
Webhooks → New Webhook → copy URL.

Then put it in `~/.zshenv` (or WSL2's `~/.bashrc`) as:

```bash
export BULLPEN_DISCORD_WEBHOOK="https://discord.com/api/webhooks/..."
```

The `infra/backup/clickhouse-snapshot.sh` reads this on failure. Better Stack
also sends to Discord for the host-down case.

---

## WSL2 bootstrap recipe (run these in order on WSL2 Ubuntu 24.04)

```bash
# 1. System deps
sudo apt update && sudo apt upgrade -y
sudo apt install -y \
    git curl wget jq build-essential \
    openjdk-21-jdk \
    python3.11 python3.11-venv \
    docker.io docker-compose-plugin \
    sqlite3

# Enable systemd in WSL2 (only needed if not already on)
# (Edit /etc/wsl.conf to ensure: [boot]\nsystemd=true, then exit + wsl --shutdown from Windows)

# 2. Node via nvm
curl -o- https://raw.githubusercontent.com/nvm-sh/nvm/v0.40.1/install.sh | bash
source ~/.bashrc
nvm install 20 && nvm use 20

# 3. uv for Python
curl -LsSf https://astral.sh/uv/install.sh | sh
source ~/.bashrc

# 4. Docker permissions (so you don't need sudo every time)
sudo usermod -aG docker $USER
# Exit + re-enter the shell

# 5. Clone the repo
mkdir -p ~/code && cd ~/code
git clone https://github.com/Alexm-picard/the-bullpen.git
cd the-bullpen

# 6. Install git hooks
./.githooks/install.sh

# 7. Bring up the stateful services
docker compose -f infra/docker-compose.yml up -d

# 8. Verify the backend builds + runs
cd backend
./gradlew test --no-daemon              # should pass
./gradlew bootRun --args='--spring.profiles.active=api' &
sleep 15
curl http://localhost:8080/health       # should return JSON
kill %1
cd ..

# 9. Verify training
cd training
uv sync
uv run pytest                            # should be 3 passed
cd ..

# 10. Verify frontend
cd frontend
npm ci --include=optional
npm run build                            # should produce dist/
cd ..
```

If any of those steps fail on WSL2, that's a bug — they all passed on macOS
and CI. Most likely culprits if it happens: Docker daemon not started
(`sudo systemctl start docker`), or a network-restricted package mirror.

---

## Env vars that need to be set on WSL2 (in `~/.bashrc` or `~/.zshenv`)

```bash
# Required for MCP servers Claude Code uses (.mcp.json reads these)
export VERCEL_API_KEY="vcp_..."           # mint at https://vercel.com/account/tokens
export GITHUB_PERSONAL_ACCESS_TOKEN="$(gh auth token)"

# Required for ClickHouse (matches infra/docker-compose.yml)
export CLICKHOUSE_HOST="localhost"
export CLICKHOUSE_PORT="8123"
export CLICKHOUSE_USER="default"
export CLICKHOUSE_PASSWORD="thebullpen"   # local-only; production sets its own

# Required for the SQLite registry MCP server
export BULLPEN_REGISTRY_DB="$HOME/code/the-bullpen/backend/data/registry.sqlite"

# Required when backup script lands
export BULLPEN_DISCORD_WEBHOOK="https://discord.com/api/webhooks/..."

# Optional
export LOG_FORMAT="json"                  # switches both Java + Python to structured logs
```

After `source ~/.bashrc`, restart Claude Code so MCP servers pick up the env.

---

## Why CI workflows look the way they do (in case you're tempted to "clean them up")

- `backend.yml` has **no ClickHouse service container** intentionally. The
  Alpine ClickHouse image fails in Actions' service runtime with
  `get_mempolicy: Operation not permitted`. The current tests don't need
  ClickHouse — Spring boots with SQLite only. Phase 1+ tests use
  Testcontainers, which spawns ClickHouse from inside the JVM at test time.
- `frontend.yml` uses `npm ci --include=optional` because the lockfile
  records linux-only optional binary deps that get pulled in by Vite's
  bundler on linux but not on macOS.
- `frontend.yml`'s `e2e` job uses a step-level shell guard for
  `playwright.config.ts`, not a job-level `if: hashFiles(...)`. Job-level
  `hashFiles` runs before checkout and returns empty, which is exactly the
  bug that took down all three workflows on the very first push.
- `training.yml`'s `leakage-tests` job runs `pytest tests/leakage -v` as a
  single step. When Phase 2a lands the four real leakage tests (future
  contamination, shuffled-target, calendar-date trace, ID consistency), each
  gets its own named step so CI failures are precise.
- `training.yml` has `pythonpath = ["src"]` set in `pyproject.toml` so
  `pytest` finds the `bullpen_training` package without needing uv to do an
  editable install (which races with shell `VIRTUAL_ENV` mismatches).
- `@emnapi/{core,runtime}` are declared as explicit `devDependencies` in
  `frontend/package.json`. They're transitive optional deps of vite's linux
  binary that macOS skips; declaring them direct keeps the lockfile platform-
  agnostic.

---

## Commits made during this macOS session

```
70a916c ci: pin @emnapi/{core,runtime} as devDeps so lockfile validates on linux runners
660e460 ci: drop CH service from backend.yml; sync frontend lockfile w/ optional deps
7a59a5b ci+chore: fix Actions workflows (drop pre-checkout hashFiles guards) + .mcp.json arg form + training pyright/discovery
2fbfdc3 feat: phase 0 foundation — gradle backend, uv training, vite frontend, ADRs 0001–0005
```

All four are on `origin/main`. CI is green on the latest.

---

## Definition of done for Phase 0

Per `docs/plan.md` Phase 0 exit criterion:

> `sudo reboot` recovers everything in <5 min, all health checks green,
> frontend reachable at domain.

That requires the six pending items above to land. The Mac side of the work
is complete; everything left is host-side.

After Phase 0 exits, Phase 1 begins (vertical slice — one prediction visible
end-to-end). See `docs/plan.md` §Phase 1.
