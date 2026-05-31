# Runbook: error tracking (self-hosted GlitchTip)

A6 / ADR-0008. Error aggregation for the API (Sentry Java SDK) and the React
app (`@sentry/react`), reporting to a self-hosted GlitchTip. Disabled until a
DSN is configured — dev, CI, and tests never phone home.

## Architecture

```
Spring api  ──(SENTRY_DSN)──┐
                            ├──► GlitchTip web (Sentry-wire ingest) ──► Postgres
React (Vercel) ─(VITE_SENTRY_DSN at build)─┘                           Redis (queue)
```

- Backend: `sentry-spring-boot-starter-jakarta` captures unhandled controller
  exceptions + ERROR logs; `config/SentryConfig` stamps the request
  `correlation_id` (MDC) onto each event as a tag.
- Frontend: `src/main.tsx` lazy-imports `@sentry/react` **only** when
  `VITE_SENTRY_DSN` is set at build time (else tree-shaken out entirely).
- Join a frontend error to its backend log/issue via the `X-Correlation-Id`
  response header (surfaced in API-client errors) ↔ the backend event's
  `correlation_id` tag.

## First-time setup (on the desktop)

1. **Generate a secret and start the profile:**

   ```bash
   export GLITCHTIP_SECRET_KEY="$(openssl rand -hex 32)"   # persist in the systemd EnvironmentFile
   docker compose -f infra/docker-compose.yml --profile errortracking up -d
   # migrate runs once and exits; web + worker + postgres + redis stay up
   ```

2. **Create the first user.** Either set `GLITCHTIP_OPEN_REGISTRATION=true`
   for the first sign-up at `http://localhost:8000` then set it back to
   `false`, or:

   ```bash
   docker compose -f infra/docker-compose.yml --profile errortracking \
     exec glitchtip-web ./manage.py createsuperuser
   ```

3. **Create a project + grab its DSN** in the GlitchTip UI (one project for
   the API, one for the frontend is cleanest).

4. **Wire the DSNs (never commit them):**
   - Backend (prod systemd `EnvironmentFile`, e.g. `/etc/default/bullpen`):
     `SENTRY_DSN=...`, `SENTRY_ENVIRONMENT=prod`, `SENTRY_RELEASE=<tag>`.
   - Frontend (Vercel project env, **Production** scope, available at build):
     `VITE_SENTRY_DSN=...`, `VITE_SENTRY_ENVIRONMENT=production`,
     `VITE_SENTRY_RELEASE=<tag>`. Redeploy so Vite inlines them.

5. **Verify:** `curl -XPOST localhost:8080/v1/predict/pitch -d 'bad'` (or
   trigger any handled-internally error) and confirm the event appears in
   GlitchTip with a `correlation_id` tag matching the `X-Correlation-Id`
   response header.

## Operations

- **GlitchTip down:** events are dropped best-effort by the SDK; the app is
  unaffected (error tracking is off the request path). Restart with the
  compose command above.
- **Reset history:** the error DB is disposable — `docker compose ... down`
  - remove the `glitchtip-pg-data` volume loses issue history, not service.
- **Backups:** the GlitchTip Postgres is intentionally NOT in the Layer-1/2
  backup scope (ADR-0008) — error history is not load-bearing data.
- **Disable entirely:** unset `SENTRY_DSN` / `VITE_SENTRY_DSN` and redeploy;
  both SDKs no-op (frontend rebuilds without the chunk).

## Reboot-drill note

GlitchTip services carry `restart: unless-stopped` but are profile-gated, so a
bare `docker compose up -d` after reboot does **not** bring them back — bring
them up with `--profile errortracking`. The reboot drill should note this if
error tracking is considered part of the recovered surface.
