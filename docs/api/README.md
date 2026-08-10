# The Bullpen API reference

The committed [`openapi.json`](./openapi.json) is a point-in-time snapshot of the
springdoc-generated OpenAPI 3 spec (38 operations). The LIVE spec is always at
[`https://api.thebullpen.net/v3/api-docs`](https://api.thebullpen.net/v3/api-docs)
(interactive explorer at
[`/swagger-ui.html`](https://api.thebullpen.net/swagger-ui.html)) - the spec and
its explorer are deliberately public (M1 task 10). CI fuzzes every operation
against this contract (Schemathesis, `contract.yml`), so contract-vs-impl drift
fails the build rather than landing silently.

## Surfaces at a glance

| Surface                                                | Auth                                  | Notes                                                                                                                                                                         |
| ------------------------------------------------------ | ------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `POST /v1/predict/batted-ball`                         | public, rate-limited                  | single-park P(HR); the Phase-1 toy path                                                                                                                                       |
| `POST /v1/predict/batted-ball/all-parks`               | public, rate-limited                  | the served per-park champion: HR probability (and carry, when the champion ships a carry head) across all 30 parks. Logs every request to `prediction_log`                    |
| `POST /v1/predict/pitch?head=pre\|post`                | public, rate-limited                  | calibrated 5-class pre/post-pitch outcome distribution. `head=pre` 503s until a PRE champion is promoted (rule 6, human-gated); `head=post` requires the Tier-4 flight fields |
| `POST /v1/simulate/plate-appearance[/monte-carlo]`     | public, own rate bucket               | unrouted diagnostic (decision [176]): pins one artifact, never routes                                                                                                         |
| `GET /v1/ops/*`                                        | public reads                          | the Ops dashboard's registry / routing / drift / latency / retrain / events / accuracy surfaces. `/v1/ops/events` is offset-paginated (`page`/`size` + `hasNext`)             |
| `GET /health`, `/actuator/health/**`, `/actuator/info` | public                                | liveness/readiness probes (Uptime Robot + deploy smoke)                                                                                                                       |
| `/v1/admin/**`                                         | HTTP Basic, role `ADMIN`              | registry writes (register / promote), routing config, experiments (incl. `import-offline`), retrain queue admin                                                               |
| `/actuator/prometheus`                                 | HTTP Basic, role `METRICS` or `ADMIN` | the Prometheus scrape endpoint (metrics-only credential; F3 role split)                                                                                                       |

Error envelope: every 4xx/5xx returns the shared `ApiError` body
(`status` / `error` / `message` / `correlationId`). A `503` from a predict
endpoint means "no live champion for that head" - a routing outcome, not a
failure. Rate limits are per-IP token buckets (429 on exhaustion).

## Regenerating the snapshot

The snapshot is produced from a local boot against an empty stack (no
ClickHouse, tmp SQLite), exactly like the CI contract job:

```bash
cd backend && ./gradlew bootJar
JAR="$(ls build/libs/*.jar | grep -vE 'plain|jmh' | head -1)"
THEBULLPEN_ADMIN_BASIC_AUTH=ci:ci java -jar "$JAR" \
  --spring.profiles.active=api --bullpen.clickhouse.enabled=false \
  --bullpen.ratelimit.enabled=false \
  --spring.datasource.url="jdbc:sqlite:/tmp/openapi.sqlite" \
  --spring.flyway.url="jdbc:sqlite:/tmp/openapi.sqlite" &
# wait for /actuator/health/readiness, then:
curl -fsS http://localhost:8080/v3/api-docs | python3 -m json.tool > ../docs/api/openapi.json
```

Regenerate whenever a controller/DTO change alters the contract (the
Schemathesis job guards the live spec either way; the snapshot exists so the
contract is reviewable in-repo without booting anything).
