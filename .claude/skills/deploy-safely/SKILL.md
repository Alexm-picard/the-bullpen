---
name: deploy-safely
description: Wraps ./deploy.sh with the live-game-window check, git tag, and post-deploy smoke verification. Trigger when the user says "deploy", "push to prod", "ship it", or wants to run a release.
---

# deploy-safely

Deploys to the self-hosted WSL2 host. The deploy itself is `./deploy.sh` (git pull, rebuild, systemctl restart). This skill wraps it with safety.

## Hard rule (CLAUDE.md rule 3)

**No deploys during live games** (evenings April–October, roughly 16:00–24:00 ET on game days). If you're inside that window, refuse unless user explicitly overrides with "yes, I know it's a game window".

## Pre-deploy checklist

Print this and confirm each:

1. ✅ All tests passing locally? `./gradlew test && cd frontend && npm test`
2. ✅ Currently on `main` and clean working tree? `git status`
3. ✅ Last commit message looks like the change you're shipping?
4. ✅ Outside live-game window? (Check `date`, refuse if in window unless override)
5. ✅ Last drill ran within 30 days? (Check `docs/drills/` for the most recent file)
6. ✅ Ready to watch Grafana for 10 minutes post-deploy?

## Procedure

1. **Tag the release**:
   - `git tag -a v$(date +%Y.%m.%d-%H%M) -m "deploy: <summary>"`
   - `git push --tags`
2. **Run deploy**:
   - `./deploy.sh` (on the WSL2 host)
   - Capture stdout to a deploy log file: `./deploy.sh 2>&1 | tee docs/deploys/$(date +%Y-%m-%d-%H%M).log`
3. **Smoke check** (in order, must all pass). Two separate deploy targets: the **backend** is served at `api.thebullpen.net` (Cloudflare Tunnel -> `localhost:8080`); the **apex `thebullpen.net` is the Vercel-hosted frontend**. Hit each at its real host - `thebullpen.net/actuator/*` returns Vercel's SPA index with HTTP 200 on _any_ path (the catch-all rewrite in `frontend/vercel.json`), which would silently FALSE-PASS a backend check even with the backend down.
   - Backend liveness (grep the body, not just the status, so a stray 200 can't pass): `curl -fsS https://api.thebullpen.net/actuator/health | grep -q '"status":"UP"'`
   - Backend prediction - the **batted-ball champion** serves live (`/v1/predict/pitch` defaults to the PRE head, which has no champion, so it is NOT a reliable smoke): `curl -fsS -X POST https://api.thebullpen.net/v1/predict/batted-ball -H 'Content-Type: application/json' -d '{"launchSpeedMph":104.5,"launchAngleDeg":28.0,"releaseSpeedMph":92.0,"parkId":"COL","stand":"R"}' | grep -q '"probHr"'`
   - Confirm last prediction logged in ClickHouse: `SELECT count() FROM prediction_log WHERE request_at > now() - INTERVAL 1 MINUTE`
   - **Frontend (Vercel) smoke** - the frontend auto-deploys on push to `main` _separately_ from `./deploy.sh`, so a red Vercel build otherwise goes unnoticed. Confirm the apex serves the real SPA, not an error page: `curl -fsS https://thebullpen.net/ | grep -qE 'id="root"|The Bullpen'`
4. **Post-deploy ping**:
   - Discord webhook: "Deployed <tag>. Smoke OK. Watching Grafana for 10 min."
5. **Watch**:
   - Grafana dashboard, p99 latency and error rate, for 10 minutes
   - If anything spikes, see Rollback below

## Rollback

If the smoke check fails or Grafana shows regression:

1. `git checkout <previous tag>`
2. `./deploy.sh`
3. Re-run smoke check
4. Discord ping the rollback
5. Hand off to `decision-recorder` to draft a postmortem note in `docs/decisions.md`

## Anti-patterns to refuse

- Deploying during live game window without explicit override — even "it's small" is not acceptable
- Skipping the tag — every deploy must have a tag
- Skipping the smoke check — "it usually works" is the wrong heuristic for production
- Deploying with uncommitted local changes
