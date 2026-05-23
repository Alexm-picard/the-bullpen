# 00-OBSERVABILITY-STRATEGY — Logs, metrics, traces, alerts

> Internal observability is Prometheus + Grafana + journald. External is Uptime Robot + Healthchecks.io. Both flow page-worthy events to a Discord webhook so the alert stream itself becomes durable incident documentation. Decisions [16]–[18], with [17] revised by [129] (Better Stack → Uptime Robot).

---

## Logs

**Format**: JSON, one event per line, written to stdout/stderr, captured by journald.

**Logback config** (Spring): `JsonEncoder` from `logstash-logback-encoder` or equivalent. Required fields per event:

| Field        | Source    | Notes                                                                   |
| ------------ | --------- | ----------------------------------------------------------------------- |
| `@timestamp` | auto      | ISO-8601 UTC with milliseconds                                          |
| `level`      | auto      | `INFO` / `WARN` / `ERROR` etc.                                          |
| `logger`     | auto      | fully-qualified class name                                              |
| `thread`     | auto      | virtual-thread name when applicable                                     |
| `message`    | call site | human-readable; no string interpolation of secrets                      |
| `trace_id`   | MDC       | OpenTelemetry trace id; propagated to async logging                     |
| `span_id`    | MDC       | OpenTelemetry span id                                                   |
| `mdc.*`      | MDC       | request_id, model_name, model_version_id, role, game_id (if applicable) |

**Python (`structlog`)**: emit identical schema. Add `trigger_id` field for retraining-job logs (Risk Register I8).

**Filters**:

- Logback `MaskingPatternLayout` redacts: `password`, `Authorization`, `Cookie`, `webhook_url`, `api_key`, `B2_*`, `CLICKHOUSE_PASSWORD`.
- Never log full feature vectors at INFO. DEBUG only, behind a feature flag.
- Never log raw stack-trace strings — use Logback's structured exception field.

**Reading logs**:

```bash
journalctl -u thebullpen-api -f                   # follow
journalctl -u thebullpen-worker --since "1h ago"  # last hour
journalctl -u thebullpen-* | jq -c 'select(.level == "ERROR")'
```

---

## Metrics

**Source**: Spring Boot Actuator + Micrometer → Prometheus.

**Endpoints**:

- `/actuator/health` — public liveness (used by Uptime Robot).
- `/actuator/health/readiness` — gates "warm-up complete" (Risk Register G11).
- `/actuator/prometheus` — Prometheus scrape target.

**Naming convention** (Micrometer):

```
thebullpen_<subsystem>_<metric>_<unit>{<labels>}
```

Required metrics by phase:

| Subsystem    | Metric                                                 | Type      | Phase added |
| ------------ | ------------------------------------------------------ | --------- | ----------- |
| `inference`  | `prediction_total{model_name, role}`                   | counter   | Phase 1     |
| `inference`  | `prediction_latency_seconds{model_name}`               | histogram | Phase 1     |
| `inference`  | `prediction_error_total{model_name, error_type}`       | counter   | Phase 1     |
| `inference`  | `model_version_active{model_name}`                     | gauge     | Phase 3     |
| `logger`     | `prediction_log_queue_depth`                           | gauge     | Phase 3     |
| `logger`     | `prediction_log_dropped_total`                         | counter   | Phase 3     |
| `ingest`     | `live_poll_lag_seconds{game_id}`                       | gauge     | Phase 4     |
| `ingest`     | `mlb_api_request_total{endpoint, status}`              | counter   | Phase 4     |
| `drift`      | `drift_metric_psi{model_name, feature}`                | gauge     | Phase 3c    |
| `drift`      | `drift_metric_calibration_ratio{model_name}`           | gauge     | Phase 3c    |
| `retraining` | `retraining_queue_depth`                               | gauge     | Phase 3d    |
| `retraining` | `retraining_job_duration_seconds{model_name, trigger}` | histogram | Phase 3d    |

JVM, HTTP, and DB-pool metrics come free from Actuator — leave defaults on.

---

## Traces

**Tooling**: OpenTelemetry Spring Boot starter; `trace_id` and `span_id` injected into MDC; trace data **not** exported to a remote collector for v1 (Design §6 / decision deferred).

**Why no remote collector**: single-machine deployment, journald + structured logs cover the use case. Add Jaeger/Tempo if multi-service ever happens. v1.5 candidate at most.

**Scope**: every `/v1/predict/*` request gets a root span; database queries get child spans automatically. The trace_id appears in the response header `X-Trace-Id` so frontend bug reports can include it.

---

## Dashboards (Grafana, locked)

Three dashboards. Provisioned via JSON files in `infra/grafana/dashboards/`.

### 1. Application

- Request rate / latency / error rate per endpoint
- Inference latency by model
- Async logger queue depth + drop count
- JVM heap, virtual-thread count, GC pauses

### 2. System

- CPU / memory / disk / network on the host (node_exporter)
- ClickHouse query latency, partition count, disk usage per partition
- SQLite WAL size, last vacuum
- R2 last-successful-backup timestamp (was B2; switched per decision [128] / ADR-0007)

### 3. ML Ops

- Active model versions per `model_name`
- Champion vs. challenger traffic split
- Drift metrics (PSI, calibration error) over rolling 30 days
- Retraining queue depth + last-run duration
- Reliability diagrams (per-class, per-model) — link to eval artifact

---

## External monitoring

- **Uptime Robot**: HTTP(S) monitor against `https://api.thebullpen.net/actuator/health` every 5 min (free tier — paid tiers go to 1 min). Page if 2 consecutive failures. Discord alert contact wired via the Uptime Robot "Alert Contacts" → Webhook integration.
- **Healthchecks.io**: heartbeat every 6 hours from each `@Scheduled` worker job. Page if missed window. URL stored in `secrets.env`.
- **Discord webhook**: shared channel for all `page` and `notice` events.

Uptime Robot and Healthchecks.io are picked because they're free at our usage level and they answer the question "is the host alive when Prometheus is also down?" (decision [17], revised by [129] from Better Stack to Uptime Robot for free-tier monitor count + simpler Discord wiring).

---

## Alert templates (Discord)

Three severities. Templates stored in `ops/runbooks/alert-templates.md`. Format:

**Page** (red):

```
🔴 PAGE — <title>
When: <ISO timestamp UTC>
Trigger: <metric> = <value>, threshold <threshold> over <window>
Affected: <model_name / endpoint / pipeline>
Runbook: <link>
trace_id / trigger_id: <ids>
```

**Notice** (yellow):

```
🟡 NOTICE — <title>
When: <ISO timestamp UTC>
Trigger: <metric> = <value>, sustained <duration>
Action: review at next weekly check
Runbook: <link>
```

**Logged-only** events do NOT page; they show on the Ops dashboard only and roll up into the weekly summary.

---

## Alert thresholds (locked from design.md §3.3 / §9)

| Trigger                                                            | Severity    | Action                                                    |
| ------------------------------------------------------------------ | ----------- | --------------------------------------------------------- |
| `/actuator/health` 5xx for 2 consecutive checks                    | Page        | Investigate within 1 hour                                 |
| Champion calibration error > 1.5× training calibration for 3+ days | Page        | Investigate within 24 hours; do NOT auto-promote anything |
| Retraining job failed                                              | Page        | Investigate before next scheduled run                     |
| Any feature PSI > 0.25 sustained 7+ days                           | Notice      | Review at next weekly check                               |
| Live polling lag > 60s for 5+ minutes during a game                | Notice      | Verify MLB API status                                     |
| Nightly job runtime anomaly (>2× p95)                              | Notice      | Review at next weekly check                               |
| Async logger drop count > 0 in last hour                           | Logged-only | Visible on dashboard                                      |

---

## Runbooks

Every alert template links to a runbook in `ops/runbooks/`. Required runbooks:

- `restore-drill.md`
- `reboot-drill.md`
- `secret-rotation.md`
- `calibration-drift-investigation.md`
- `retraining-failure-recovery.md`
- `live-polling-recovery.md`
- `clickhouse-disk-full.md`

Each runbook is short (1 page max), starts with "If you're paged, do these things in order", and ends with "Closing the incident: write a postmortem under `docs/postmortems/<date>-<slug>.md`".

---

## Drift postmortem (centerpiece artifact)

Decision [82]. Phase 5.7 templates the document.

A drift postmortem includes:

- Date range of the incident
- Linked Grafana dashboard snapshot (export PNG)
- Affected model_name + version_id
- Drift metrics that fired (PSI, calibration error, with values)
- Hypotheses and what each was tested against
- Resolution: retrained? Rolled back? Documented as known limitation?
- 5-Whys root cause
- What changed in the system as a result (test added? alert tightened?)

This is the resume-grade artifact. Every detected drift event during operation produces one.
