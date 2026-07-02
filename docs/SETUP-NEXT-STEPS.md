# Setup Next Steps — The Bullpen

Everything Claude wrote during the project-setup interview is on disk. This doc is the punch list of things **you** need to do manually before any of it actually fires.

## 1. Move to WSL2

1. Install WSL2 Ubuntu 24.04 LTS if you haven't: `wsl --install -d Ubuntu-24.04`
2. Inside WSL2: `mkdir -p ~/code && cp -r /mnt/c/Users/<windows-user>/Desktop/thebullpen ~/code/` (or git clone fresh once a repo exists)
3. Confirm the repo path. The hook scripts default to `/home/$(whoami)/code/thebullpen` but fall back to `git rev-parse --show-toplevel` so any path works once you `git init`.
4. Remind me of the final WSL2 path when you have it — I'll update any absolute references if needed.

## 2. Clean up studyforesight cruft (optional but recommended)

The `.claude/` directory still has stale files from a previous project. Decide:

```
# Inspect first
ls .claude/agents/
ls .claude/skills/

# Then delete if they're truly not needed for this project:
rm .claude/agents/{backend-engineer,code-reviewer,db-specialist,frontend-engineer,idea-generator,infra-engineer,ml-engineer,test-engineer}.md
rm -rf .claude/skills/{api-design,debug-strategy,ideate,integration-fastapi,pr-creation,refactoring,security-audit}
```

(The new agents/skills I wrote sit alongside without conflict.)

The old `.claude/settings.json` hook pointed at a `studyforesight` path — I already replaced it.

## 2.5. Install the design-quality skills

The `ui-design-loop` and `frontend-reviewer` agents reference three external skills. They degrade gracefully if missing, but you get the most out of them with all three installed.

### `ui-ux-pro-max` — already enabled

You already have this via the `ui-ux-pro-max@ui-ux-pro-max-skill` plugin in `.claude/settings.json`. Nothing to do.

### `frontend-design` (Anthropic-official)

> _"Create distinctive, production-grade frontend interfaces with high design quality."_ Available in the `anthropic-agent-skills` marketplace (already on disk at `~/.claude/plugins/marketplaces/anthropic-agent-skills/skills/frontend-design/`).

Enable via the plugin system:

```bash
# In a Claude Code session:
/plugin marketplace add anthropic-agent-skills    # if not already added
/plugin install frontend-design@anthropic-agent-skills
```

Or copy directly into project scope:

```bash
mkdir -p .claude/skills
cp -r ~/.claude/plugins/marketplaces/anthropic-agent-skills/skills/frontend-design .claude/skills/
```

Verify:

```bash
ls .claude/skills/ | grep frontend-design   # or check `/plugin list` for enabled status
```

**Important — locks are challengeable, not gag orders:**

The agents are **not** wired to suppress `frontend-design`'s dissent against project locks. The whole reason to stack four skills is to get varying opinions. If `frontend-design` (or `taste-skill`) makes a strong case against a locked choice — say, "Inter is generic, try Söhne or IBM Plex Sans" — the agent captures the dissent, generates 2–3 concrete alternatives, scores them through all four lenses, and surfaces a recommendation to you with: _"this would reverse decision [N] — run `/decide` to formally reconsider."_

Outcomes per locked choice in any given iteration:

1. **Lock holds** — dissent is weak or already addressed by the lock's reasoning (the Inter+JetBrains+Serif pairing is _already_ differentiated; weak generic anti-Inter objections lose). Documented in the output.
2. **Lock surfaces for `/decide`** — two skills converging or one skill making a specific evidence-backed case. Alternatives proposed, user decides via `/decide`.
3. **Lock holds with documented dissent** — partial agreement; user can revisit later.

**Mechanical/safety rules stay non-negotiable** regardless of skill input: no hex codes, no `useEffect` for server state, no WebSockets, no `any` types, a11y basics. These aren't aesthetic — they're discipline rules.

The known tensions to watch for:

- `frontend-design`'s "avoid Inter" — likely to fire often; usually the three-font pairing answers the underlying concern, but stay open to a strong specific case (e.g., "Inter renders pitch-by-pitch numeric data poorly at 14px vs JetBrains Mono Variable")
- `frontend-design`'s "bold maximalism" bias — usually wrong for data screens (the locked editorial+industrial direction is intentional), but may be _right_ for marketing/onboarding/landing screens — challenge per-screen, not blanket

### `taste-skill` (Leonxlnx/taste-skill)

> _"Gives your AI good taste. Stops the AI from generating boring, generic slop."_

Install (project scope recommended so it ships with the repo):

```bash
# From the repo root
npx skills add https://github.com/Leonxlnx/taste-skill --skill design-taste-frontend
```

If you want all variants (frontend + image-gen + mobile + branding):

```bash
npx skills add https://github.com/Leonxlnx/taste-skill
```

The skill installs as a `SKILL.md` file in `.claude/skills/`. Verify with:

```bash
ls .claude/skills/ | grep -i taste
```

### `impeccable` (pbakaus/impeccable)

> _"The design language that makes your AI harness better at design."_ 23 commands, 7 domain references, 27 anti-pattern rules.

No `claude plugin add` for this one — install by copying the bundle:

```bash
# Option A: project-scoped (preferred — ships with the repo)
git clone https://github.com/pbakaus/impeccable /tmp/impeccable
cp -r /tmp/impeccable/dist/claude-code/.claude/* .claude/
rm -rf /tmp/impeccable

# Option B: global (~/.claude/) — available across all your projects
git clone https://github.com/pbakaus/impeccable /tmp/impeccable
cp -r /tmp/impeccable/dist/claude-code/.claude/* ~/.claude/
rm -rf /tmp/impeccable
```

Verify with:

```bash
ls .claude/commands/ | grep impeccable   # if project-scoped
# or
ls ~/.claude/commands/ | grep impeccable # if global
```

You should see `impeccable.md` and ~22 sub-commands. Test by running `/impeccable teach` in a fresh Claude Code session.

### How the agents use them

- **ui-design-loop**:
  - Phase 1 (proposal): `ui-ux-pro-max` + `taste-skill` prime the generation
  - Phase 2 (synthesis): both score the Claude/Stitch candidates before cherry-pick
  - Phase 4 (code gen): `frontend-design` for execution discipline + `/impeccable shape` + `/impeccable polish`
  - Phase 5 (verify): four-lens audit on the rendered screenshots (spec / execution-quality / impeccable / taste)
- **frontend-reviewer**: design-quality final pass after the project-specific rule checks, results reported in a dedicated `DESIGN-QUALITY PASS` block in the verdict (four rows: ui-ux-pro-max / taste-skill / frontend-design / impeccable)

If a skill isn't installed, the agent notes "fallback" and applies the principles from memory. Nothing breaks.

## 3. Install MCP servers

Run these on the same machine you'll run Claude Code from (WSL2):

```bash
# Tier 1 — definitely
claude mcp add --scope project github -- npx -y @modelcontextprotocol/server-github
claude mcp add --scope project clickhouse -- uvx mcp-clickhouse
claude mcp add --scope project playwright -- npx -y @playwright/mcp@latest

# Tier 2 — strongly recommended
claude mcp add --scope project sqlite -- npx -y @modelcontextprotocol/server-sqlite --db-path ${BULLPEN_REGISTRY_DB}
claude mcp add --scope project fs -- npx -y @modelcontextprotocol/server-filesystem ${CLAUDE_PROJECT_DIR}

# Vercel (frontend deploy visibility)
claude mcp add --scope project vercel -- npx -y vercel-mcp
```

(I already wrote `.mcp.json` with all of these declared — running `claude mcp add` is mostly to confirm/install dependencies and any auth flows.)

## 4. Set environment variables

Add to `~/.bashrc` or `~/.profile` on WSL2:

```bash
export GITHUB_PERSONAL_ACCESS_TOKEN="ghp_..."         # GitHub MCP
export CLICKHOUSE_HOST="localhost"
# CLICKHOUSE_PORT is the NATIVE protocol port (9000) - it is owned by the native
# clickhouse-driver client the training pipeline + leakage gate use. Do NOT set it to
# the HTTP port 8123: the native client cannot speak HTTP, and a stale 8123 silently
# skipped the SQL-path leakage gate during the 2026-06-07 build. `from_env` now FAILS
# LOUD on 8123/8443 (DEV-3). The ClickHouse MCP uses HTTP 8123 - configure that in the
# MCP server config, NOT via this shared shell var.
export CLICKHOUSE_PORT="9000"
export CLICKHOUSE_USER="default"
export CLICKHOUSE_PASSWORD="..."
export BULLPEN_REGISTRY_DB="${HOME}/code/thebullpen/backend/data/registry.sqlite"
```

Then `source ~/.bashrc`.

## 5. Verify hooks fire

The 6 hooks are wired in `.claude/settings.json`. Test each:

```bash
# Auto-format hooks: edit a file in Claude Code, watch for the formatter running
# (output may be silent if files are already formatted)

# Block retro decisions: try editing line 1 of docs/decisions.md, stage, attempt commit
# Should be blocked with a message about append-only.

# Live-game reminder: run between 4pm and midnight ET, April-October,
# attempt git push to main. Should print a reminder.

# Block destructive CH: in Claude Code, try
#   clickhouse-client --query "DROP TABLE prediction_logs"
# Should be blocked unless a recent snapshot exists.
```

If any hook misbehaves, check `${CLAUDE_PROJECT_DIR}/.claude/hooks/<name>.sh` and re-run with `bash -x` to debug.

## 5.5. Install git hooks (one-time, after cloning to WSL2)

The repo ships pre-commit hooks in `.githooks/`. Wire them up once:

```bash
./.githooks/install.sh
```

This sets `git config core.hooksPath .githooks`. The active hook today is `pre-commit`, which enforces `contracts/feature_pipeline.json` schema_hash + pipeline_version discipline (refuses to commit a stale hash or unbumped version when the schema changes).

## 5.6. Wire up the backup layers

**Layer 1 — daily automated snapshot.** See `infra/backup/README.md` for the full runbook. Quick version:

```bash
# Install clickhouse-backup
wget https://github.com/Altinity/clickhouse-backup/releases/latest/download/clickhouse-backup_amd64.deb
sudo dpkg -i clickhouse-backup_amd64.deb

# Drop in systemd units
sudo cp infra/backup/bullpen-snapshot.service /etc/systemd/system/bullpen-snapshot@.service
sudo cp "infra/backup/bullpen-snapshot@.timer" /etc/systemd/system/bullpen-snapshot@.timer

# Env file (KEEP chmod 600 — contains Discord webhook)
sudo tee /etc/default/bullpen >/dev/null <<EOF
BULLPEN_DISCORD_WEBHOOK="https://discord.com/api/webhooks/..."
REPO_ROOT="/home/$(whoami)/code/thebullpen"
RETAIN_DAYS="14"
EOF
sudo chmod 600 /etc/default/bullpen

# Enable + smoke test
sudo systemctl daemon-reload
sudo systemctl enable --now "bullpen-snapshot@$(whoami).timer"
sudo systemctl start "bullpen-snapshot@$(whoami).service"
sudo journalctl -u "bullpen-snapshot@$(whoami).service" --since today
```

**Layer 2 — air-gapped USB backup.**

```bash
# One-time
lsblk                                          # find the USB device
sudo mkfs.ext4 -L BULLPEN_BACKUP /dev/sdX1     # or exfat
./infra/backup/install-sudoers.sh              # NOPASSWD rule for usb-backup.sh only

# Every backup run
./infra/backup/usb-backup.sh                   # no password prompt
```

The `install-sudoers.sh` writes a single, narrow rule to `/etc/sudoers.d/bullpen-backup` that whitelists exactly one absolute path (the script). Validates with `visudo -c` before installing; refuses to install a broken rule. Uninstall any time with `--uninstall`. Hardened variant available via `--hardened` (points the rule at `/usr/local/sbin/bullpen-usb-backup`, root-only-writable). Full details + security trade-off in [infra/backup/README.md](infra/backup/README.md#about-the-sudoers-rule).

Run cadence: weekly during build, daily during season, and before any disruptive change (Windows update, WSL2 distro upgrade, driver install).

## 5.7. GitHub Issues setup

The backlog lives in GitHub Issues. Templates are pre-wired in `.github/ISSUE_TEMPLATE/` (bug, idea, cut-proposal). One-time setup:

```bash
# Create the label conventions (run from repo root, after pushing to GitHub)
gh repo set-default derpthund/thebullpen   # adjust to your repo
# Then copy-paste the label-creation block from .github/labels.md
```

See [.github/labels.md](.github/labels.md) for the full label scheme (type / severity / area / phase / status) and what does and doesn't belong in Issues vs `docs/`.

## 5.8. CI workflows are pre-wired

`.github/workflows/{backend,training,frontend}.yml` are ready. They use file-presence guards (`hashFiles(...) != ''`) so they no-op gracefully until scaffolding lands. Specifically:

- **training.yml** marks the **leakage-tests** job as REQUIRED (its name prefix is "REQUIRED — temporal leakage tests"). When you set up branch protection on `main`, mark that job as required to merge.
- **backend.yml** runs Spotless check + SpotBugs + tests with a ClickHouse Testcontainers-compatible service
- **frontend.yml** runs tsc + ESLint + Prettier + Vitest + build; Playwright E2E activates when you add `playwright.config.ts`

Use the `/ci-add` slash command (or `ci-add` skill) to add new jobs as the project grows.

## 6. Phase 0 scaffolding (when you're ready to start coding)

Per `docs/plan.md` Phase 0 — bootstrap the build system:

```bash
# Backend
mkdir -p backend && cd backend
gradle init --type java-application --dsl kotlin --java-version 21 --project-name thebullpen-backend
# add Spring Boot, Spotless, Error Prone, SpotBugs to build.gradle.kts
cd ..

# Training
mkdir -p training/{eval,tests/leakage,artifacts} && cd training
uv init --name bullpen-training --python 3.11
uv add lightgbm onnxruntime onnx pandas numpy scikit-learn pyarrow
uv add --dev ruff pyright pytest
cd ..

# Frontend
mkdir -p frontend && cd frontend
npm create vite@latest . -- --template react-ts
npm install @mantine/core @mantine/hooks @tanstack/react-query
npm install -D tailwindcss postcss autoprefixer @playwright/test vitest
npx tailwindcss init -p
cd ..

# Contracts directory (referenced by both backend and training)
mkdir -p contracts

# Initial commit
git init
git add -A
git commit -m "feat: phase 0 scaffolding — gradle backend, uv training, vite frontend"
```

## 7. First few decisions to lock via `/decide`

Once Phase 0 is up, use `/decide` to formally lock:

1. Gradle group/artifact coordinates for the backend
2. Mantine theme tokens (color palette mapping to editorial-data identity)
3. ClickHouse `pitches` table partition and order-by keys
4. SQLite registry schema v1 (use `add-schema-change` skill)
5. The exact ONNX opset version for training-to-serving compatibility

Each will land as a numbered entry in `docs/decisions.md` and update `docs/design.md` where relevant.

## 8. Run the drills before the season

Per discipline rule 8:

- `/drill restore` — once the SQLite registry + ClickHouse have real data
- `/drill reboot` — once systemd units exist for `api` and `worker`

Don't ship to the season without both reports under `docs/drills/`.

## 9. Things I deliberately did NOT do

- **Did not add web/product analytics** (PostHog etc.) — your internal telemetry stack (Prometheus + Grafana + ClickHouse prediction logs + drift metrics) is the right "analytics" story for an ML-systems portfolio piece. See the chat for reasoning.
- **Did not add Cloudflare MCP** — only useful if you'll manage the Tunnel/DNS through Claude. Skip unless you want it.
- **Did not delete the studyforesight agents/skills** — destructive; flagged for you to clean up in step 2.
- **Did not write any production code** — Phase 0 scaffolding is yours to drive.
- **Did not set up cloud offsite backup** at original-doc time — USB was Layer 2. Updated 2026-05-21: ADR-0007 + decisions [127][128] add Cloudflare R2 as an S3-compatible target for backups + model artifacts (free tier covers Phase-0 traffic, $0 egress, vendor-consolidated with Tunnel + DNS). USB remains Layer 2 for hardware-contingency; R2 becomes the offsite layer. Defense in depth.
- **Did not generate Phase 0 docker-compose, deploy.sh, or any backend/training/frontend scaffolding** — that's Tier 2 deferred work. When you're ready, ask for `infra/docker-compose.yml` and the Phase 1 vertical-slice doc.

## 10. Files written during this setup

(Updated as the setup has evolved through this conversation.)

```
CLAUDE.md                                          (extended in place)
.claude/settings.json                              (replaced — stale studyforesight hook removed)
.mcp.json                                          (extended — added 6 MCP servers)
.claude/agents/ml-leakage-auditor.md               (new)
.claude/agents/registry-guard.md                   (new)
.claude/agents/java-reviewer.md                    (new)
.claude/agents/python-training-reviewer.md         (new)
.claude/agents/frontend-reviewer.md                (new)
.claude/agents/schema-migration-author.md          (new)
.claude/agents/drill-runner.md                     (new)
.claude/agents/decision-recorder.md                (new)
.claude/agents/ui-design-loop.md                   (new)
.claude/skills/register-model/SKILL.md             (new)
.claude/skills/promote-model/SKILL.md              (new)
.claude/skills/lock-decision/SKILL.md              (new)
.claude/skills/add-schema-change/SKILL.md          (new)
.claude/skills/run-rolling-cv/SKILL.md             (new)
.claude/skills/deploy-safely/SKILL.md              (new)
.claude/commands/decide.md                         (new)
.claude/commands/promote.md                        (new)
.claude/commands/drill.md                          (new)
.claude/commands/review-ml.md                      (new)
.claude/commands/review-java.md                    (new)
.claude/commands/design.md                         (new)
.claude/commands/status.md                         (new)
.claude/hooks/format-java.sh                       (new, +x)
.claude/hooks/format-python.sh                     (new, +x)
.claude/hooks/format-ts.sh                         (new, +x)
.claude/hooks/block-retro-decisions.sh             (new, +x)
.claude/hooks/live-game-reminder.sh                (new, +x)
.claude/hooks/block-destructive-ch.sh              (new, +x)
docs/drills/                                       (empty, ready for drill reports)
docs/deploys/                                      (empty, ready for deploy logs)
SETUP-NEXT-STEPS.md                                (this file)

# Design-quality skill stack (from the "integrate taste/impeccable/frontend-design" pass):
.claude/agents/ui-design-loop.md                   (updated — 6 phases inc. lock-challenge review)
.claude/agents/frontend-reviewer.md                (updated — categorized findings + lock challenges)

# Tier 1 + Tier 3 (from the "anything else?" pass):
.github/workflows/backend.yml                      (new — gradle + spotless + spotbugs)
.github/workflows/training.yml                     (new — ruff + pyright + REQUIRED leakage tests)
.github/workflows/frontend.yml                     (new — tsc + lint + vitest + playwright)
.github/ISSUE_TEMPLATE/bug.yml                     (new)
.github/ISSUE_TEMPLATE/idea.yml                    (new)
.github/ISSUE_TEMPLATE/cut-proposal.yml            (new — enforces soft-cut discipline)
.github/ISSUE_TEMPLATE/config.yml                  (new)
.github/labels.md                                  (new — label scheme + create-all commands)
.claude/skills/ci-add/SKILL.md                     (new)
.claude/commands/ci-add.md                         (new)
infra/backup/clickhouse-snapshot.sh                (new, +x — daily snapshot script)
infra/backup/bullpen-snapshot.service              (new — systemd unit template)
infra/backup/bullpen-snapshot@.timer               (new — daily 03:00 template timer)
infra/backup/usb-backup.sh                         (new, +x — air-gapped USB backup)
infra/backup/README.md                             (new — full backup runbook)
.githooks/pre-commit                               (new, +x — schema_hash discipline)
.githooks/install.sh                               (new, +x — one-time hook wiring)
docs/phase-status.json                             (new — machine-readable phase tracking)
.claude/commands/status.md                         (rewritten — reads phase-status.json + verifies on-disk evidence)
CLAUDE.md                                          (extended — backlog/githooks/backups/phase-status)

# Sudoers narrow rule (this pass):
infra/backup/sudoers.d/bullpen-backup.template     (new — template for /etc/sudoers.d entry)
infra/backup/install-sudoers.sh                    (new, +x — validates and installs the rule)
infra/backup/usb-backup.sh                         (updated — self-elevates via sudo if not root)
infra/backup/README.md                             (updated — sudoers install step + hardening note)
```
