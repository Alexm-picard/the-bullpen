# ADR-0008: Self-hosted error tracking (GlitchTip), Sentry SDKs both sides

- **Status**: Accepted
- **Date**: 2026-05-30
- **Deciders**: alex
- **Related**: `decisions.md` entries [135], ADR-0006, ADR-0007, audit-remediation A6

## Context

Observability today is strong on the metrics + logs axes — Prometheus +
Grafana + Actuator internally, structured JSON logs with a `correlation_id`
threaded through MDC, external uptime via Uptime Robot + Healthchecks +
a Discord webhook. The gap the mid-level readiness audit flagged is
**error tracking**: an unhandled exception in the API or an error in the
React app lands in a log line and nowhere else. There is no aggregation,
no first-seen/last-seen, no regression alerting, no release-tagged grouping
— the things that turn "an error happened" into "this error, 142 times,
since the v2026.05.28 deploy, here's the stack and the request that caused
it."

The project's identity is self-hosted-on-one-box with deliberate vendor
restraint (ADR-0006, ADR-0007). The two realistic options were a SaaS
error tracker (Sentry's free tier — zero infra) or a self-hosted one
(GlitchTip — Sentry-wire-compatible, runs in Docker next to ClickHouse /
Prometheus / Grafana). The user chose self-hosted to keep the operating
story coherent ("everything that can run on the box, runs on the box") and
to avoid a fourth external dashboard.

GlitchTip speaks the Sentry ingestion protocol, so the mature, well-
documented Sentry SDKs (`sentry-spring-boot-starter-jakarta` on the JVM,
`@sentry/react` in the browser) are the clients — pointing them at a
GlitchTip DSN instead of Sentry's is a one-URL change, which preserves the
same swap-to-SaaS escape hatch the storage ADR (0007) values.

## Decision

- **Error tracking runs self-hosted via GlitchTip**, added to
  `infra/docker-compose.yml` behind an `errortracking` Compose profile
  (postgres + redis + web + worker + a one-shot migrate). Profile-gated so
  the default `docker compose up` stays the lean ClickHouse/Prometheus/
  Grafana stack — GlitchTip is opt-in (`--profile errortracking`), heavy
  enough (its own Postgres) that it shouldn't tax every dev boot.
- **Both apps report through the Sentry SDK**, parameterized by a single
  DSN env var (`SENTRY_DSN` backend, `VITE_SENTRY_DSN` frontend). **Blank
  DSN = disabled** — the SDK no-ops on the JVM, and the browser SDK is
  _tree-shaken out of the build entirely_ because Vite inlines the unset
  env var to `undefined` and eliminates the dynamic import. So nothing
  phones home in dev, CI, or tests; error tracking only activates where a
  DSN is configured (prod systemd `EnvironmentFile` / Vercel build env).
- **`correlation_id` is stamped onto every backend event as a Sentry tag**
  (via a `beforeSend` options bean reading MDC), so a GlitchTip issue links
  back to the exact structured-log line. The frontend↔backend join is the
  `X-Correlation-Id` response header surfaced in API-client errors.
- **The frontend SDK loads lazily** (`import("@sentry/react")` inside the
  DSN guard), so when it _is_ enabled it ships as a separate chunk off the
  initial-bundle budget rather than bloating the entry chunk.
- `SECRET_KEY` for GlitchTip is required at start (`${GLITCHTIP_SECRET_KEY:?}`)
  — no insecure default. `send-default-pii` is false on both sides (no IPs,
  no headers, no request bodies captured).

## Consequences

**Easier:**

- Unhandled exceptions and ERROR logs aggregate with first/last-seen,
  release tagging, and regression alerting — the audit's "observability is
  good not complete" gap closes.
- Vendor story stays coherent: one more thing on the box, no new SaaS
  account or dashboard, consistent with ADR-0006/0007's self-host posture.
- The Sentry-wire-compatible choice keeps a one-env-var swap to hosted
  Sentry (or any Sentry-compatible backend) if self-hosting ever becomes a
  burden — the same reversibility principle as the S3 endpoint in ADR-0007.

**Harder:**

- GlitchTip brings its own Postgres + Redis + a worker — more moving parts
  on the box, more to include in the reboot drill and the backup story.
  Mitigated by the Compose profile (off by default) and by treating the
  error DB as disposable (losing it loses history, not service).
- A `SECRET_KEY` and first-superuser bootstrap are now part of the prod
  setup runbook (`docs/runbooks/error-tracking.md`).

**New failure modes:**

- GlitchTip down → events are dropped by the SDK (best-effort, async); the
  app is unaffected. Acceptable: error tracking is not on the request path.
- DSN accidentally committed → it's an ingestion endpoint, not a secret
  read-credential, but still env-only by policy (never in git), same as
  every other endpoint/secret in ADR-0006/0007.

**Locked into:**

- The Sentry SDK as the client contract. Any move off it (e.g. OpenTelemetry
  logs/traces as the error transport) is a re-decision via `/decide`, not a
  quiet swap.

## Alternatives Considered

### Alternative A: SaaS Sentry free tier

- Point the same SDKs at Sentry's hosted free tier. Zero infra.
- Rejected (by the user, during A6 planning): adds a fourth external
  dashboard + account + the chance of paid-tier creep, and dents the
  "self-hosted on one box" story the README and postmortem lean on. The
  SDK-level compatibility means this stays a one-DSN-change fallback if the
  self-hosted instance ever becomes more trouble than it's worth.

### Alternative B: No dedicated error tracker — logs + Grafana only

- Keep the status quo: errors live in structured logs; eyeball Grafana/Loki.
- Rejected: that's exactly the gap the audit named. Logs answer "what
  happened in this request"; they don't answer "how often, since when, on
  which release, trending up or down" without building an aggregation layer
  — which is what GlitchTip already is.

### Alternative C: OpenTelemetry collector + a tracing backend

- Emit OTel and run a backend (Tempo/Jaeger + a logs store) for errors.
- Rejected for now: heavier to operate than GlitchTip for a single-box
  single-dev project, and overkill given there's one process and no
  distributed call graph to trace. Revisit if the system ever grows the
  multi-service shape that makes tracing pay off.

## Revision History

(none)
