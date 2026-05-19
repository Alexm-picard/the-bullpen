# WSL2 handoff — Phase 0 completion brief

**Written**: 2026-05-19, end-of-day from the macOS dev box
**Supersedes**: [`2026-05-19-wsl2-handoff.md`](2026-05-19-wsl2-handoff.md) (which is now stale —
several items it listed as pending are done)
**Audience**: the Claude Code instance running on WSL2 after the user opens it
in this repo for the first time

This doc is the single source of truth for Phase 0 continuation. The repo at
HEAD reflects everything done so far; this doc tells you _why_ the repo looks
the way it does, what's left, and how to finish.

---

## The very first prompt to give Claude after launching it on WSL2

Paste this verbatim:

> Read `docs/sessions/2026-05-19-wsl2-phase-0-completion.md` and proceed with
> finishing Phase 0. Don't re-derive context — the doc has it. Walk me
> through each remaining item in order, confirming before doing anything
> that has shared-state blast radius (deploys, systemd enable, USB writes).

That single prompt is enough. Don't paste the old handoff doc's prompt;
that one's stale.

---

## State as of this handoff

### What's done (don't redo any of this)

**Repo scaffolding**: backend (Spring Boot 3.5 + Java 21 + virtual threads),
training (uv-managed Python 3.11), frontend (Vite + React 19 + Mantine 9 +
Tailwind 4 + TanStack Query 5), contracts dir, infra docker-compose
(ClickHouse 24.12 + Prometheus + Grafana). All compiles, builds, tests pass
locally and in CI.

**ADRs**: 0001 (Java not Kotlin), 0002 (ONNX in-process), 0003 (ClickHouse +
SQLite), 0004 (Mantine + Tailwind), 0005 (polling not WebSockets). Each
cross-referenced in `docs/decisions.md` entries [121]–[125].

**GitHub repo**: https://github.com/Alexm-picard/the-bullpen (private; will
go public once Phase 0 closes). All 39 labels created. Branch protection
deferred until repo goes public (free tier doesn't allow it on private).

**GitHub Actions (all 3 green on `main`)**:

- `backend.yml` — gradle build + test + spotlessCheck + spotbugsMain
- `frontend.yml` — tsc + lint + vitest + build (Playwright job stub waiting
  for Phase 4d to add `playwright.config.ts`)
- `training.yml` — ruff format/check + pyright + pytest + leakage suite

**Vercel**: project `the-bullpen-ml` on team `alexmpicards-projects`.

- Latest deploy `f65ce5e` is READY + PROMOTED
- Vercel Authentication: **Disabled** (public-viewable)
- Domains: both `thebullpen.net` (apex) and `www.thebullpen.net` attached,
  both serving 200 with real TLS certs
- Build settings: Vite framework detected, root dir `frontend`,
  `npm run build` → `dist/`
- `vercel.json` ships COOP / CORP / CSP `frame-ancestors 'none'` headers

**Cloudflare DNS for `thebullpen.net`**:

- Nameservers: `harley.ns.cloudflare.com` / `samara.ns.cloudflare.com`
- Apex → Vercel anycast (gray cloud, DNS only)
- `www` → CNAME `cname.vercel-dns.com` (gray cloud, DNS only)
- `api.thebullpen.net` → **NOT YET** — created automatically when the Tunnel
  daemon registers on WSL2 (item 2 below)
- Stale Squarespace `www` record was deleted

**Discord webhook**: created, smoke message verified landed in
`#bullpen-alerts`. The URL itself is on the user's clipboard / personal
notes — they need to put it in `~/.bashrc` on WSL2 (see env vars section).

**Backend Docker image**: `backend/Dockerfile` builds end-to-end and the
container's HEALTHCHECK reports `healthy` within ~1s of boot. Image runs as
non-root `appuser` UID 1001. Validated on macOS Docker Desktop; will work
on WSL2 Docker identically.

**ClickHouse backup primitives**: bash syntax clean, `sqlite3 .backup`
produces a 24KB registry snapshot, `clickhouse-client` reachable inside the
Docker container, `ALTER TABLE … FREEZE` produces shadow parts on disk.
Full end-to-end script needs Linux + GNU coreutils to run — that's WSL2.

**Cleanup the user needs to do (not blocking)**: there are two duplicate
Vercel projects (`thebullpen`, `the-bullpen`) from accidental "Add Project"
clicks. Delete each via Vercel dashboard → project Settings → Delete
Project. Keep only `the-bullpen-ml`.

### What's left in Phase 0 (the 6 items)

Per `docs/phase-status.json` (canonical tracker):

1. **WSL2 host setup** — systemd enabled, memory cap, CUDA passthrough
2. **Cloudflare Tunnel** — `api.thebullpen.net` → WSL2 `:8080`
3. **systemd units** for `bullpen-api` + `bullpen-worker`
4. **Better Stack monitoring** `/health` (needs the public Tunnel URL from #2)
5. **USB backup + restore drill** (with empty data)
6. **Reboot drill** — the actual Phase 0 exit criterion

When all six are done, run the reboot drill once more as confirmation, bump
`docs/phase-status.json` to `current_phase: "1"`, then Phase 1 begins
(the vertical-slice work — one prediction visible end-to-end in browser).

---

## WSL2 bootstrap (run before anything else)

Assumes a fresh WSL2 Ubuntu 24.04 install. If you're on a previously-used
WSL2 and most of this is already done, skip ahead to "Clone the repo."

### 1. Enable systemd (critical — without this nothing else works)

```bash
sudo tee /etc/wsl.conf >/dev/null <<'EOF'
[boot]
systemd=true
EOF
# Then from PowerShell on Windows side:
#   wsl --shutdown
# Then reopen the WSL2 terminal. systemd should now PID 1.
systemctl is-system-running   # expect: running (or degraded — ok if no services started yet)
```

### 2. System packages

```bash
sudo apt update && sudo apt upgrade -y
sudo apt install -y \
    git curl wget jq build-essential \
    openjdk-21-jdk \
    python3.11 python3.11-venv \
    docker.io docker-compose-plugin \
    sqlite3 \
    rsync
```

### 3. Node via nvm

```bash
curl -o- https://raw.githubusercontent.com/nvm-sh/nvm/v0.40.1/install.sh | bash
source ~/.bashrc
nvm install 20 && nvm use 20
node --version       # v20.x
```

### 4. uv for Python

```bash
curl -LsSf https://astral.sh/uv/install.sh | sh
source ~/.bashrc
uv --version
```

### 5. Docker daemon + your user

```bash
sudo systemctl enable --now docker
sudo usermod -aG docker $USER
# Exit + reopen the shell (so the group takes effect)
docker ps   # should list nothing without sudo
```

### 6. Clone the repo

```bash
mkdir -p ~/code && cd ~/code
git clone https://github.com/Alexm-picard/the-bullpen.git
cd the-bullpen
./.githooks/install.sh
```

The repo path is `~/code/the-bullpen` (the GitHub repo name has the hyphen).
Some older references in `SETUP-NEXT-STEPS.md` say
`~/code/thebullpen` — those are stale; the canonical path is what
`git clone` produces, which is `~/code/the-bullpen`.

### 7. Env vars (in `~/.bashrc`)

```bash
cat >> ~/.bashrc <<'EOF'

# --- The Bullpen ---
export VERCEL_API_KEY="vcp_..."             # mint at https://vercel.com/account/tokens
export GITHUB_PERSONAL_ACCESS_TOKEN="$(gh auth token 2>/dev/null || echo 'gho_...')"
export CLICKHOUSE_HOST="localhost"
export CLICKHOUSE_PORT="8123"
export CLICKHOUSE_USER="default"
export CLICKHOUSE_PASSWORD="thebullpen"     # local-only; production sets its own
export BULLPEN_REGISTRY_DB="$HOME/code/the-bullpen/backend/data/registry.sqlite"
export BULLPEN_DISCORD_WEBHOOK="https://discord.com/api/webhooks/..."
export LOG_FORMAT="json"                    # both Java + Python emit structured logs
EOF
source ~/.bashrc
```

Verify each:

```bash
echo "VERCEL_API_KEY len: ${#VERCEL_API_KEY}"
echo "GITHUB token len: ${#GITHUB_PERSONAL_ACCESS_TOKEN}"
echo "DISCORD set: $([ -n "$BULLPEN_DISCORD_WEBHOOK" ] && echo yes || echo no)"
```

After this, **fully relaunch Claude Code on WSL2** so the MCP servers pick up
the env. Then `claude mcp list` should show `vercel: ✓ Connected`,
`clickhouse: ✓ Connected`, `sqlite: ✓ Connected`, etc.

### 8. Sanity check: everything builds + boots locally

```bash
docker compose -f infra/docker-compose.yml up -d
# wait ~15s for ClickHouse to be healthy
curl -fsS http://localhost:8123/ping        # ClickHouse: "Ok."

cd backend
./gradlew test --no-daemon                  # should pass
./gradlew bootRun --args='--spring.profiles.active=api' &
sleep 15
curl http://localhost:8080/health            # should return JSON
curl http://localhost:8080/actuator/health   # status:UP
kill %1; wait
cd ..

cd training
uv sync
uv run pytest                                # 3 passed
cd ..

cd frontend
npm ci --include=optional
npm run build                                # produces dist/
cd ..
```

If any of those fail, that's a regression vs. macOS — flag it, don't paper
over. The most likely culprit if it happens: Docker daemon not enabled
(`sudo systemctl status docker`), or WSL2 systemd not enabled (re-check
step 1).

---

## The six remaining Phase 0 items, in execution order

### Item 1 — WSL2 host hardening

**Why**: Phase 0 exit criterion includes `sudo reboot` recovering everything
in <5 min. That requires the OS to be configured to start the services on
boot, the memory cap to keep ClickHouse from being OOM-killed, and (later
for ML training) CUDA passthrough so GPU jobs work.

**Steps**:

1. **Memory cap for WSL2** — create `C:\Users\<you>\.wslconfig` (on Windows side):

   ```ini
   [wsl2]
   memory=32GB
   processors=8
   swap=8GB
   ```

   (Tune to your machine — 32GB is for a 64GB+ desktop. Lower if you have less.)
   Then from PowerShell: `wsl --shutdown` and reopen.

2. **CUDA passthrough** — if you have an NVIDIA GPU. Install NVIDIA's CUDA-on-WSL
   driver on Windows (from nvidia.com), then inside WSL2:

   ```bash
   nvidia-smi   # should list your GPU
   ```

   If you don't have an NVIDIA GPU, skip this — `lightgbm` runs CPU-only fine
   for Phase 1, and the MLP training in Phase 2c can fall back to CPU if needed
   (slower but works).

3. **Verify systemd autostart**: confirm docker comes up after reboot:
   ```bash
   sudo systemctl enable docker
   sudo reboot
   # after reboot, in a fresh terminal:
   docker ps   # should work without sudo systemctl start docker
   ```

**Definition of done**: `sudo reboot` produces a working `docker ps`,
ClickHouse + Prometheus + Grafana come back up automatically (covered by
item 3 below), and `nvidia-smi` works if you set up CUDA.

---

### Item 2 — Cloudflare Tunnel for `api.thebullpen.net`

**Why**: The Spring backend on WSL2 has no public ingress otherwise. The
Tunnel is the only public path in (decision [9]). The frontend on Vercel
needs to reach the backend to make any real prediction call.

**Steps**:

1. **Install `cloudflared`**:

   ```bash
   curl -L https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64.deb \
     -o /tmp/cloudflared.deb
   sudo dpkg -i /tmp/cloudflared.deb
   cloudflared --version
   ```

2. **Authenticate**:

   ```bash
   cloudflared tunnel login
   # opens browser → log into Cloudflare → select thebullpen.net → done
   ```

3. **Create the tunnel**:

   ```bash
   cloudflared tunnel create bullpen-api
   # prints a tunnel ID and writes ~/.cloudflared/<UUID>.json (the credentials)
   ```

4. **Configure routing**: create `~/.cloudflared/config.yml`:

   ```yaml
   tunnel: <UUID-from-step-3>
   credentials-file: /home/<you>/.cloudflared/<UUID>.json

   ingress:
     - hostname: api.thebullpen.net
       service: http://localhost:8080
     - service: http_status:404
   ```

5. **Tell Cloudflare the DNS record**:

   ```bash
   cloudflared tunnel route dns bullpen-api api.thebullpen.net
   # Cloudflare auto-creates a proxied CNAME at api.thebullpen.net → <UUID>.cfargotunnel.com
   ```

6. **Run it (foreground, to verify)**:

   ```bash
   cloudflared tunnel run bullpen-api
   ```

   In another shell:

   ```bash
   # Make sure the backend is running (./gradlew bootRun --args='--spring.profiles.active=api')
   curl https://api.thebullpen.net/health
   # → {"status":"ok","profile":"api","ts":"..."}
   ```

7. **Install as a systemd service** (so it survives reboot):
   ```bash
   sudo cloudflared service install
   sudo systemctl enable --now cloudflared
   sudo systemctl status cloudflared
   ```

**Definition of done**: `curl https://api.thebullpen.net/health` returns the
Spring `/health` JSON from anywhere on the internet. `systemctl status
cloudflared` shows `active (running)` and `Loaded: enabled`.

**Update the frontend to point at the new API base**:

- In Vercel dashboard → project `the-bullpen-ml` → Settings → Environment
  Variables → add `VITE_API_BASE` = `https://api.thebullpen.net` (Production
  scope). Redeploy.

---

### Item 3 — systemd units for `bullpen-api` + `bullpen-worker`

**Why**: The application has to come back up after `sudo reboot` without you
typing anything (Phase 0 exit criterion). The two profiles run as separate
systemd services so they can be restarted independently and have separate
log streams.

**Steps**:

1. **Decide where the JAR lives.** Convention: build with `./gradlew bootJar`
   under the repo, then copy the resulting JAR to `/opt/bullpen/app.jar`. The
   deploy.sh stub in the repo root captures this pattern — when you're ready
   to formalize it, extend `deploy.sh` with the rsync + systemctl restart
   commands it already has TODO markers for.

   For Phase 0, the bare path is:

   ```bash
   cd ~/code/the-bullpen/backend
   ./gradlew bootJar
   sudo mkdir -p /opt/bullpen
   sudo cp build/libs/*.jar /opt/bullpen/app.jar
   sudo chown -R bullpen:bullpen /opt/bullpen  # see step 2 for the user
   ```

2. **Create a dedicated `bullpen` user** (don't run as your own user — discipline):

   ```bash
   sudo useradd --system --shell /bin/false --home-dir /opt/bullpen bullpen
   sudo mkdir -p /opt/bullpen/data /opt/bullpen/logs
   sudo chown -R bullpen:bullpen /opt/bullpen
   ```

3. **API unit**: `/etc/systemd/system/bullpen-api.service`:

   ```ini
   [Unit]
   Description=The Bullpen — Spring Boot api profile
   After=network-online.target docker.service
   Wants=network-online.target

   [Service]
   Type=simple
   User=bullpen
   Group=bullpen
   WorkingDirectory=/opt/bullpen
   Environment="BULLPEN_REGISTRY_DB=/opt/bullpen/data/registry.sqlite"
   Environment="LOG_FORMAT=json"
   Environment="BULLPEN_API_PORT=8080"
   ExecStart=/usr/bin/java -jar /opt/bullpen/app.jar --spring.profiles.active=api
   Restart=on-failure
   RestartSec=5
   StandardOutput=append:/opt/bullpen/logs/api.log
   StandardError=append:/opt/bullpen/logs/api.err

   [Install]
   WantedBy=multi-user.target
   ```

4. **Worker unit**: `/etc/systemd/system/bullpen-worker.service`:
   Identical to the API unit but:
   - `Description=The Bullpen — Spring Boot worker profile`
   - `Environment="BULLPEN_WORKER_PORT=8081"`
   - `ExecStart=... --spring.profiles.active=worker`
   - `StandardOutput=append:/opt/bullpen/logs/worker.log`
   - `StandardError=append:/opt/bullpen/logs/worker.err`

5. **Enable + start**:
   ```bash
   sudo systemctl daemon-reload
   sudo systemctl enable --now bullpen-api bullpen-worker
   sudo systemctl status bullpen-api bullpen-worker
   curl http://localhost:8080/health        # api profile
   curl http://localhost:8081/actuator/health  # worker profile
   ```

**Definition of done**: `sudo reboot` and after the system is up,
`systemctl is-active bullpen-api bullpen-worker` returns `active` for both,
without you typing anything.

---

### Item 4 — Better Stack monitoring `/health`

**Why**: Prometheus + Grafana on the host can't alert you when the host
itself is down. Better Stack is the external watcher (decision [17]).
Discord is the alert sink (decision [18]).

**Steps**:

1. **Create a Better Stack account** at https://betterstack.com (free tier
   covers what we need). Verify your email.

2. **Add a monitor**:
   - Dashboard → Uptime → "Create monitor"
   - URL: `https://api.thebullpen.net/health`
   - Check every: 1 minute
   - Expect: HTTP 200, response contains `"status":"ok"`
   - Alert after: 2 failures (avoids flap-noise)

3. **Wire the alert to Discord**:
   - In Better Stack: Integrations → Discord → New integration
   - Paste your Discord webhook URL
   - Test it — you should see a fake-incident message in `#bullpen-alerts`
   - Attach this integration to the monitor

4. **Optional: also monitor the frontend** — add another monitor for
   `https://thebullpen.net/` (just expects 200, no body match).

**Definition of done**: monitor shows "Up" with green check, and a test
incident from Better Stack lands in Discord.

---

### Item 5 — USB backup + restore drill

**Why**: Untested backups don't exist (CLAUDE.md hard never rule). The
restore drill must run before the season starts (discipline rule 8). The
backup script (`infra/backup/clickhouse-snapshot.sh`) is already written;
this is about exercising it end-to-end with empty data, so the procedure is
proven before there's real data to lose.

**Steps**:

1. **Install `clickhouse-backup`** (Layer 1 of the backup story):

   ```bash
   wget https://github.com/Altinity/clickhouse-backup/releases/latest/download/clickhouse-backup_amd64.deb \
     -O /tmp/clickhouse-backup.deb
   sudo dpkg -i /tmp/clickhouse-backup.deb
   clickhouse-backup --version
   ```

2. **Run the snapshot script manually** (against the empty Docker ClickHouse):

   ```bash
   cd ~/code/the-bullpen
   SNAPSHOT_DIR=/tmp/bullpen-snapshots \
   SQLITE_REGISTRY=$HOME/code/the-bullpen/backend/data/registry.sqlite \
     ./infra/backup/clickhouse-snapshot.sh
   ls -la /tmp/bullpen-snapshots/  # should show auto_<timestamp>_* dirs
   ```

3. **Plug in the USB drive**, find its device path:

   ```bash
   lsblk
   # identify the device (e.g., /dev/sdb1)
   ```

4. **Format + label it** (only on the first use — destroys existing data):

   ```bash
   sudo mkfs.ext4 -L BULLPEN_BACKUP /dev/sdb1
   # confirm before running — this wipes the drive
   ```

5. **Install the sudoers rule** so `usb-backup.sh` can self-elevate:

   ```bash
   ./infra/backup/install-sudoers.sh
   ```

6. **Run the USB backup**:

   ```bash
   ./infra/backup/usb-backup.sh
   # mounts /dev/disk/by-label/BULLPEN_BACKUP, rsyncs snapshots to it,
   # unmounts cleanly
   ```

7. **Restore drill** (the actual test):
   - Stop the running ClickHouse: `docker compose -f infra/docker-compose.yml down`
   - Wipe the ClickHouse volume: `docker volume rm thebullpen_clickhouse-data`
     (this is the "data loss" scenario)
   - Bring ClickHouse back up: `docker compose -f infra/docker-compose.yml up -d clickhouse`
   - Wait for it to be healthy
   - Use `clickhouse-backup restore <snapshot-name>` to restore from the
     snapshot you just took
   - Run `clickhouse-client --query "SELECT * FROM system.databases"` to
     confirm the structure is back (with empty data, since the snapshot
     was of an empty CH).

8. **Write the drill report** at `docs/drills/2026-MM-DD_restore_drill.md`:
   - What you did, when
   - How long it took (target: <30 min from "data lost" to "service back")
   - What broke that needs fixing before season starts
   - Sign-off line

**Definition of done**: `docs/drills/2026-MM-DD_restore_drill.md` exists,
checked into git, with an "outcome: pass" line and a recorded
time-to-restore.

---

### Item 6 — Reboot drill (the actual Phase 0 exit criterion)

**Why**: This is the test that closes Phase 0. CLAUDE.md rule 8: "Untested
recovery means unreliable system."

**Steps**:

1. **Pre-drill snapshot**: take a quick log of what's running:

   ```bash
   systemctl is-active bullpen-api bullpen-worker docker cloudflared
   curl -s https://thebullpen.net/ | head -1
   curl -s https://api.thebullpen.net/health
   ```

   Copy that output into `docs/drills/2026-MM-DD_reboot_drill.md`.

2. **`sudo reboot` and start a stopwatch.**

3. **When WSL2 comes back up** (you'll need to reopen the terminal),
   immediately re-run the same checks. Stop the stopwatch when all four
   services are `active` AND `curl` to both endpoints succeeds.

4. **Target**: under 5 minutes from `sudo reboot` to all-green. If it's over,
   diagnose:
   - Did docker take too long? Pre-pull ClickHouse + Prometheus + Grafana
     images so they don't re-download.
   - Did cloudflared fail? Check `journalctl -u cloudflared --since "5 min ago"`
   - Did the JVM take too long? Spring Boot 3.5 with virtual threads on
     usually boots in 3-5s for this app size.

5. **Write the drill report** at `docs/drills/2026-MM-DD_reboot_drill.md`
   with: pre-state, the exact reboot timestamp, when each service came
   back, total recovery time, and an "outcome: pass / fail" line.

**Definition of done**: `docs/drills/2026-MM-DD_reboot_drill.md` exists,
checked into git, with `outcome: pass` and total recovery time < 5 min.

**This item completing closes Phase 0.** Once both drill reports are in,
bump `docs/phase-status.json` `current_phase` to `"1"`, flip Phase 0's
`status` to `complete`, set `completed_on` to today's date, and commit.

---

## After Phase 0 closes — Phase 1 begins

Phase 1 is the vertical slice: one prediction visible end-to-end in the
browser. The full plan is in `docs/plan.md` Phase 1. Highlights:

- Historical Statcast pull (one season, ~2024) into `raw_statcast`
- Cleaning into `pitches` with proper schema and dedup
- Minimal batted-ball model: LightGBM, 5 features, single output
- ONNX export, Java loading, parity test passing
- Spring endpoint `POST /predict/batted-ball`
- Park Explorer page in the frontend with hardcoded historical BIPs
- Click → real prediction renders in browser
- Primitive prediction logging to ClickHouse

**Exit criterion for Phase 1**: visit `thebullpen.net/parks`, click a
batted ball, see real prediction in <500ms end-to-end.

Don't start Phase 1 work until both drill reports are in. The discipline
rule is real — building on an unverified foundation is exactly the
"horizontal building" failure mode `docs/plan.md` warns about.

---

## Gotchas / pitfalls

These are things that will trip you up if you don't already know them:

- **WSL2 systemd must be enabled in `/etc/wsl.conf`** before docker / cloudflared
  / bullpen-api as systemd services will work. Step 1 of the bootstrap.
- **GUI-launched terminals on macOS don't source `~/.zshrc`** — that bit us
  earlier with `VERCEL_API_KEY`. On WSL2 it's bash by default and `~/.bashrc`
  IS sourced by interactive shells, so this is less of a problem, but
  Claude Code's tool shells inherit from whatever launched VSCode.
- **`server: Squarespace` on a `curl -sI` is a curl artifact, not a real
  response from Squarespace** — it shows up when there's a TLS cert
  mismatch. Use `curl -sIk` to bypass and see what the actual upstream is
  returning.
- **Don't deploy during live games** (April–October evenings — decision [21]).
  The `live-game-reminder` hook in `.claude/hooks/` reminds you on `git
push`.
- **Never touch live ClickHouse without a backup snapshot first** — CLAUDE.md
  hard never rule. The `block-destructive-ch` hook enforces this on
  `DROP`/`TRUNCATE`/`ALTER`.
- **Never commit a trained model artifact** — only metadata. CLAUDE.md hard
  never rule. The `.gitignore` covers `training/artifacts/**/*.onnx`,
  `*.pt`, `*.parquet`.
- **The four GitHub Actions quirks are intentional** — see the rationale
  section in the previous handoff doc or just don't "clean up" the
  workflows without reading the commits that put them in that shape:
  - `backend.yml` has no ClickHouse service container (Alpine + Actions
    runtime incompat)
  - `frontend.yml` uses `npm ci --include=optional` (linux-only optional
    native deps)
  - `frontend.yml` e2e job uses a step-level shell guard, not job-level
    `if: hashFiles(...)`
  - `training.yml` has `pythonpath = ["src"]` in pyproject so pytest
    finds the package without an editable install

---

## Open items the user (alex) needs to do that aren't fully WSL2 work

- **Decide whether the repo goes public during or after Phase 0** — branch
  protection on `main` needs public-or-Pro. Free tier supports it on public
  repos. Phase 6 publishes the repo anyway.
- **Delete the two duplicate Vercel projects** (`thebullpen`, `the-bullpen`)
  in the Vercel dashboard. Keep `the-bullpen-ml`. Each `git push` currently
  triggers builds on all three; cleanup saves build minutes.
- **Verify `am.picard03@gmail.com` is a verified email on the GitHub account
  Alexm-picard** at https://github.com/settings/emails. The recent commits
  (post-`65700d8`) use this address; without verification, Vercel still
  blocks them with `COMMIT_AUTHOR_REQUIRED`.

---

## Commits made on macOS that you'll see in `git log`

```
f65ce5e chore: redeploy via verified commit author
65700d8 docs: WSL2 handoff doc + README status update + phase-status bump (9/14 done)
70a916c ci: pin @emnapi/{core,runtime} as devDeps so lockfile validates on linux runners
660e460 ci: drop CH service from backend.yml; sync frontend lockfile w/ optional deps
7a59a5b ci+chore: fix Actions workflows (drop pre-checkout hashFiles guards) + .mcp.json arg form + training pyright/discovery
2fbfdc3 feat: phase 0 foundation — gradle backend, uv training, vite frontend, ADRs 0001–0005
```

All on `origin/main`. CI green on `f65ce5e`.

---

## Soft-cut rules if WSL2 setup runs over

Per `docs/plan.md` and CLAUDE.md discipline rule 3: **No cuts to Phase 0**.
Diagnose blockers, don't paper over.

That said, the realistic risk areas:

- **WSL2 systemd not working** — this is the most likely 1+ hour blocker. If
  it's not enabling, recreate the WSL2 distro (`wsl --unregister Ubuntu-24.04
&& wsl --install -d Ubuntu-24.04`). Saves time vs. debugging a broken
  distro.
- **CUDA passthrough failing** — defer it. Phase 0 doesn't need GPU. Add it
  as a Phase 2 prerequisite.
- **Cloudflare Tunnel connection issues** — usually means the cloudflared
  user isn't authenticated or the credentials file path in `config.yml` is
  wrong. Verify `cloudflared tunnel info bullpen-api` shows the tunnel.

If any of these block for >2 hours, write what you tried in
`docs/drills/2026-MM-DD_blocker.md` and ping me with the file — I can help
diagnose.

---

## Definition of done — Phase 0

All of the following true:

- [ ] `sudo reboot` recovers everything in <5 min
- [ ] All systemd services come back `active` automatically
- [ ] `curl https://thebullpen.net/` → 200, React app
- [ ] `curl https://api.thebullpen.net/health` → 200, Spring `/health` JSON
- [ ] Better Stack monitor reads "Up"
- [ ] Discord webhook received at least one real alert during testing
- [ ] `docs/drills/2026-MM-DD_restore_drill.md` exists, outcome: pass
- [ ] `docs/drills/2026-MM-DD_reboot_drill.md` exists, outcome: pass
- [ ] `docs/phase-status.json` Phase 0 marked `status: complete`,
      `current_phase` bumped to `"1"`

When that's all true: commit the phase-status bump + the two drill reports,
tag the commit `v0.1.0-phase0-complete`, push, and announce Phase 0 done.
Then start Phase 1.

Good luck.
