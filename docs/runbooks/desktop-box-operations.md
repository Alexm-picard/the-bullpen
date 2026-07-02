# The Bullpen - Desktop Box Operations

> Master operator guide for the production desktop. It describes the box and walks
> the **current post-merge operator sequence** top to bottom. Per-task detail lives
> in the linked runbooks; this doc is the map and the order. Last updated 2026-06-09,
> after PRs #25 (pitch onto the registry), #26 (P1 backup fix), and #27 (physics gate)
> landed on `main`.

---

## 0. The dev/prod boundary (read this first)

Per **ADR-0006**, the desktop is a deploy target, never an authoring target:

- **Code and config-in-repo are edited on the MacBook only.** Never `vim`/`sed` a tracked
  file in the box's working copy. The box's repo at `/home/<user>/code/the-bullpen` is owned
  by `deploy.sh` alone.
- **`./deploy.sh` is the only writer of `/opt/bullpen`.** Writes reach prod as: edit on Mac ->
  `git push` -> CI green -> merge -> `./deploy.sh` on the box (off-window).
- **On the box you OBSERVE and TRIGGER**, you do not edit: tail logs, query ClickHouse, read
  Grafana, run `deploy.sh` / training / the snapshot. That is the whole interaction surface.
- **Rule 3:** no deploys during live MLB games (evenings April-October). Prefer the
  `deploy-safely` skill, which enforces the live-game-window check.
- Operational state that legitimately lives only on the box (the `/etc/default/bullpen` env,
  systemd units, ClickHouse data dirs, R2 credentials) must be reconstructable from the repo
  plus a documented bootstrap. The pre-season **restore drill** (rule 8) is the forcing
  function that flushes out anything only on the box.

---

## 1. What runs on the box

**Hardware:** Ryzen 7 7800X3D + RTX 4070 Super, Windows 11 host, WSL2 Ubuntu 24.04 LTS. All
services run inside WSL2.

| Component                | Manager   | Port(s)     | Notes                                                                                                                                    |
| ------------------------ | --------- | ----------- | ---------------------------------------------------------------------------------------------------------------------------------------- |
| `bullpen-api`            | systemd   | 8080        | Spring profile `api`. HTTP predictions, players, ops, `/admin`.                                                                          |
| `bullpen-worker`         | systemd   | 8081        | Spring profile `worker`. Drift jobs, retraining queue, live poller.                                                                      |
| ClickHouse               | Docker    | 8123 / 9000 | `bullpen-clickhouse` container. Pitches, prediction_log, drift_metrics.                                                                  |
| Prometheus               | Docker    | 9090        | Scrapes the api/worker actuator.                                                                                                         |
| Grafana                  | systemd   | 3000        | Application / System / ML-Ops dashboards.                                                                                                |
| cloudflared              | systemd   | -           | Tunnel: `api.thebullpen.net` -> `localhost:8080`. The only public ingress. Config template: `infra/cloudflared/config.yml.example` (M2). |
| GlitchTip (error track)  | Docker    | (compose)   | Behind the `errortracking` compose profile (ADR-0008).                                                                                   |
| Python training env      | on demand | -           | uv-managed, uses the GPU. Not a service; runs for training only.                                                                         |
| `bullpen-snapshot@<user>.timer` | systemd   | -           | Daily 03:00 backup (`clickhouse-snapshot.sh`).                                                                                           |

**Install layout:**

```
/opt/bullpen/app.jar            -> symlink to releases/<TAG>/app.jar (deploy.sh swaps it)
/opt/bullpen/releases/<TAG>/    last 5 kept
/opt/bullpen/data/registry.sqlite   the LIVE model registry (~94 KB). NOT the repo's 24 KB
                                    backend/data/registry.sqlite (that one is a stale dev artifact)
/etc/default/bullpen            the env contract (see section 2), chmod 600
/home/<user>/code/the-bullpen   the box's repo working copy (deploy.sh owns it)
/var/lib/clickhouse-backup/     daily snapshots (auto_<ts>/ + <ts>_sqlite/)
```

Frontend is **not** on the box: it is on Vercel and auto-deploys on push to `main`.

**Stack verification (M3):** the monitoring (Prometheus/Alertmanager) and error-tracking
(GlitchTip) stacks run behind opt-in compose profiles, so run
[`infra/check-stack.sh`](../../infra/check-stack.sh) after `docker compose ... up -d` (and on a
timer) - it fails loud if any expected container is missing or unhealthy, otherwise alerting can be
silently absent. Every long-lived compose service now has an env-overridable memory cap
(`BULLPEN_*_MEM`) and a healthcheck.

---

## 2. The environment contract (`/etc/default/bullpen`)

Both units load this single `EnvironmentFile`. A committed template documenting **every** variable
lives at [`infra/default-bullpen.env.example`](../../infra/default-bullpen.env.example) (M2) - copy
it to `/etc/default/bullpen` (chmod 600) and fill in real values. The **required** values (the app
misbehaves or won't boot without them) are owned by [`desktop-environment.md`](desktop-environment.md)

- read it; the summary:

- `BULLPEN_CLICKHOUSE_ENABLED=true` - without it the worker **crash-loops** and the api
  **silently loses** live data / player search / prediction logging (the 2026-06-04 incident).
  A crash-looping worker is the canary for this being unset.
- `THEBULLPEN_ADMIN_BASIC_AUTH=<user>:<password>` - the api won't boot without it; `/v1/admin/**`
  (register, promote, routing) needs it. M5: this same credential also gates
  `/actuator/{prometheus,metrics}`, so Prometheus needs `infra/prometheus/secrets/metrics_authz`
  (base64 of `<user>:<password>`, created from the committed `.example`) or the scrape 401s and
  metrics go blind.
- `S3_ENDPOINT_URL=https://<account>.r2.cloudflarestorage.com` + the R2 access key/secret -
  snapshot storage, model-artifact ingest at registration, backups (ADR-0007).
- `BULLPEN_REGISTRY_DB=/opt/bullpen/data/registry.sqlite` - the live registry path the P1 backup
  scripts source (decision [153]; default is correct, set explicitly for clarity).
- Optional: `BULLPEN_DISCORD_WEBHOOK` (drift/uptime alerts), `BULLPEN_INGEST_LIVE_ENABLED`
  (default `false`, the live poller).

### Discord webhook: THREE surfaces, TWO env names (L3 - do not unify casually)

The same webhook URL is consumed in three places under different names; missing any one of
them silently mutes that surface:

| Surface                               | Name                                                  | Form                               |
| ------------------------------------- | ----------------------------------------------------- | ---------------------------------- |
| App (DiscordNotifier: drift/registry) | `DISCORD_WEBHOOK_URL` (`bullpen.discord.webhook-url`) | raw URL, in `/etc/default/bullpen` |
| Snapshot script failure ping          | `BULLPEN_DISCORD_WEBHOOK`                             | raw URL, in `/etc/default/bullpen` |
| Alertmanager (watchdog alerts)        | `infra/alertmanager/secrets/discord_url`              | raw URL, gitignored secret file    |

Unifying the two env names is a code change that breaks the box env - if you do it, change
both readers in one commit, say so loudly in the commit message, and update this table plus
`/etc/default/bullpen` in the same deploy window. When ROTATING the webhook (L9), re-stage
all three surfaces.

---

## 3. Access + health (observe-only)

Get on the box via the WSL2 terminal (or SSH). Everything here is read-only observation.

**After any deploy or restore, run the health block:**

```bash
systemctl is-active bullpen-api bullpen-worker         # BOTH must print 'active'
systemctl show bullpen-worker -p NRestarts             # low + stable, NOT climbing (canary)
curl -sf http://localhost:8080/actuator/health         # api healthy
# Confirm ClickHouse is actually wired (the line ClickHouseConfig emits when the bean builds):
journalctl -u bullpen-api    -n 60 --no-pager | grep -i "ClickHouse DataSource ready"
journalctl -u bullpen-worker -n 60 --no-pager | grep -i "ClickHouse DataSource ready"
```

External: `curl -sf https://api.thebullpen.net/actuator/health` (through the tunnel). Grafana at
`localhost:3000`. Discord receives drift/uptime alerts.

---

## 4. The post-merge operator sequence (DO THIS NOW)

`main` currently carries: the pitch heads routed through the registry/router (#25), the P1
backup fix (#26), and the physics-gate fix (#27). Nothing of that is _running_ on the box until
you deploy. Do these in order; each step gates the next.

### Step 1 - Deploy the merged backend (off-window)

```bash
# On the box, outside a live-game window (rule 3):
cd /home/<user>/code/the-bullpen
git fetch origin && git log -1 --oneline origin/main        # confirm the merged SHA
# Prefer the deploy-safely skill (live-game-window check + tag + smoke). Or directly:
./deploy.sh
```

`deploy.sh` builds the bootJar (`-x test`, CI is the gate), atomically swaps
`/opt/bullpen/app.jar`, restarts both units, smoke-checks `/actuator/health` for 30s, and rolls
back the symlink on smoke failure. It does **not** run migrations (Flyway runs at boot) or touch
the frontend (Vercel). **Verify** with the section-3 health block. If the worker climbs
restarts, reconcile `/etc/default/bullpen` against [`desktop-environment.md`](desktop-environment.md).

Rollback: [`ROLLBACK.md`](ROLLBACK.md).

### Step 2 - P1 backup box-side (refresh + the dry-run that proves it)

`deploy.sh` does not manage the `infra/backup` units, so the P1 script change (#26) needs a
manual refresh, then a dry-run to prove the registry capture is real. Full detail:
[`h6-backup-r2-verification-and-drills.md`](h6-backup-r2-verification-and-drills.md).

```bash
# Refresh the snapshot script into wherever the unit's ExecStart points (confirm the path first):
systemctl cat bullpen-snapshot@.service | grep ExecStart
sudo cp infra/backup/clickhouse-snapshot.sh <that-ExecStart-path> && sudo systemctl daemon-reload
# Ensure /etc/default/bullpen has BULLPEN_REGISTRY_DB=/opt/bullpen/data/registry.sqlite (sudoedit).

# THE GATE: run one snapshot and confirm it captured the LIVE registry, not the 24 KB stale one.
sudo systemctl start "bullpen-snapshot@$(whoami).service"
journalctl -u "bullpen-snapshot@$(whoami).service" --since "5 min ago" --no-pager
#   EXPECT: "registry captured: ~94000B, integrity ok, schema present"  and NO "SKIP"
LATEST=$(ls -1dt /var/lib/clickhouse-backup/auto_*_sqlite | head -1)
ls -l "$LATEST/registry.sqlite"     # MUST be ~94 KB (live), NOT ~24 KB (stale)
sqlite3 "$LATEST/registry.sqlite" 'PRAGMA integrity_check; SELECT count(*) FROM model_versions;'
```

Do not trust the 03:00 timer until this dry-run shows the ~94 KB capture.

### Step 3 - Box training (the substantial step)

Train the pitch heads (`pitch_outcome_pre`, `pitch_outcome_post`) and their LR baselines on the
GPU. Full per-stage heat/cooldown procedure:
[`h1-box-training-trigger.md`](h1-box-training-trigger.md) +
[`training-models.md`](training-models.md). Two hard preconditions:

- **OOM discipline.** The box is memory-constrained (three OOMs fixed during the build). Use the
  subsample / decouple-to-non-live-box levers in the h1 runbook; snapshot first.
- **Leakage fix (do not skip):** rebuild the tier-1/2 target-encoding window to `train_end =
test_year - 2` before the run. `cv_harness.py:25-29` documents why - the gate currently uses
  `test_year - 1`, which puts the validation year inside the TE window. The CI leakage tests +
  the box re-run are the proof of production leakage-safety; the sample-data evidence only
  cleared the SHADOW bar.

GPU issues: [`cuda-ptx-mismatch.md`](cuda-ptx-mismatch.md).

### Step 4 - Register + promote (human-gated)

Register the trained pitch heads + LR baselines, then promote through SHADOW -> CHAMPION.
Procedure: the `register-model` and `promote-model` skills +
[`2c-register-and-close.md`](2c-register-and-close.md). Discipline:

- **Rule 9:** pre and post are two separate registry models, each co-registered with its LR
  baseline. The registration places the Tier-2 lookup files into the snapshot (the #25
  register-copy fix) so a registered champion actually loads.
- **Rules 5/6:** promotion to CHAMPION needs **pre-declared criteria + a passing
  `experiment_results` row on box-trained data** (sample-data evidence only clears SHADOW). The
  promotion **load gate** ([151]) loads the model through the serving loader and runs one
  forward pass before the write - a load/predict failure is a 422 at promote-time, not a 500 live.
- **It may legitimately not promote.** The sample run showed the pre head failing its ECE
  guardrail vs the LR baseline; if the box-data run agrees, the model stays SHADOW and the gate
  did its job. First-champion recovery: [`clean-recovery-first-champion.md`](clean-recovery-first-champion.md).

### Step 5 - Flip the live poller (off-window)

Turn on the MLB live-game poller so real traffic flows, predictions log with a real
`model_version_id`, and the truth-join populates. Detail:
[`h3-live-poller-activation.md`](h3-live-poller-activation.md) +
[`live-data-setup.md`](live-data-setup.md).

```bash
# Off-window. Optionally fixture-replay dry-run first (BULLPEN_INGEST_LIVE_BASE_URL), then:
sudoedit /etc/default/bullpen      # set BULLPEN_INGEST_LIVE_ENABLED=true
sudo systemctl restart bullpen-worker
# Verify the GameStateMachine starts polling + pitches_live / prediction_log rows appear.
# Rollback: set false, restart worker.
```

### Step 6 - Operate + the drift postmortem

The worker's daily/weekly drift jobs run; PAGE/NOTICE alerts fire to Discord; the truth-join
feeds calibration. Watch Grafana. When a real drift event lands, write it up in SRE format (the
postmortem is the centerpiece artifact); if the season is too quiet, induce one in a controlled
way and document it as synthetic. Investigations:
[`calibration-drift-investigation.md`](calibration-drift-investigation.md) +
[`feature-drift-investigation.md`](feature-drift-investigation.md). Retraining failures:
[`retraining-failure-recovery.md`](retraining-failure-recovery.md).

---

## 5. Incident playbook index

| Symptom                                 | Runbook                                                                |
| --------------------------------------- | ---------------------------------------------------------------------- |
| Bad deploy / need to roll back          | [`ROLLBACK.md`](ROLLBACK.md)                                           |
| `bullpen-worker` crash-looping          | [`desktop-environment.md`](desktop-environment.md) (the env canary)    |
| Registered champion 500s / won't load   | [`clean-recovery-first-champion.md`](clean-recovery-first-champion.md) |
| Registry DB lost / corrupted            | [`registry-snapshot-recovery.md`](registry-snapshot-recovery.md)       |
| Retraining job failed                   | [`retraining-failure-recovery.md`](retraining-failure-recovery.md)     |
| GPU / CUDA PTX mismatch during training | [`cuda-ptx-mismatch.md`](cuda-ptx-mismatch.md)                         |
| Unhandled exceptions not visible        | [`error-tracking.md`](error-tracking.md) (GlitchTip)                   |

---

## 6. Still open (backup-remediation tail)

P1 (registry capture) shipped in #26. The rest of the 2026-06-08 backup remediation is not yet
authored: **P2** (offsite push to Cloudflare R2, the durability close per ADR-0007), **P5**
(host/container observability + alert rules), **drill redesign** (restore must boot the worker
profile, not just api - the 2026-06-04 crash-loop went undetected for 4 days because drills only
booted api), and **P4** (GlitchTip error tracking on-box). These are Mac-authored, deploy-shipped,
same pattern as P1.
