# Required desktop environment (`/etc/default/bullpen`)

> **Why this exists.** On 2026-06-04 the `bullpen-worker` service was found crash-looping
> (~7,200 restarts, every ~10s since the 2026-05-31 deploy -- ~4 days). Root cause: the
> environment never set `bullpen.clickhouse.enabled`, and `ClickHouseConfig` gates the
> `clickhouseDataSource` bean on it with `matchIfMissing=false`. No data source ->
> `DriftMetricsRepository` (`@ConditionalOnBean`) is skipped -> the `@Profile("worker")` drift
> jobs (CalibrationJob / PsiFeatureJob / PsiPredictionJob / WeeklySegmentJob), which
> hard-require it, fail to wire -> context refresh fails -> exit 1 -> systemd restart loop.
>
> The `api` profile survived the same missing bean (its ClickHouse-backed endpoints are
> `@ConditionalOnProperty` and simply don't load; `OpsController` injects the repo
> `required=false`), so it booted -- but it **silently lost** player search, live game data,
> and async prediction logging the whole time. Enabling the one flag fixes both profiles.
>
> The repo deliberately does NOT carry these values (secrets stay out of git;
> `matchIfMissing=false` properties must be set explicitly). That makes them **untracked
> desktop state** -- exactly what ADR-0006's restore drill exists to flush out. This runbook is
> the env contract a clean rebuild / restore must reproduce.

Both systemd units (`bullpen-api`, `bullpen-worker`) load the single
`EnvironmentFile=/etc/default/bullpen` (they differ only in `--spring.profiles.active`, port,
and heap). Spring relaxed-binds `FOO_BAR_BAZ` -> `foo.bar.baz`.

## Required -- the app misbehaves or won't boot without these

| Env var                                                                                     | Property                     | What breaks if missing                                                                                                                                                                                                                                                      |
| ------------------------------------------------------------------------------------------- | ---------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `BULLPEN_CLICKHOUSE_ENABLED=true`                                                           | `bullpen.clickhouse.enabled` | `matchIfMissing=false`, no default. The `clickhouseDataSource` bean is never created: the **worker crash-loops** (drift jobs hard-require the repo) and the **api silently loses** live game data, player search, and prediction logging. This was the 2026-06-04 incident. |
| `THEBULLPEN_ADMIN_BASIC_AUTH=<user>:<password>`                                             | `bullpen.admin.basicauth`    | No default. `SecurityConfig` throws at startup, so the **api won't boot** at all. `/v1/admin/**` (registration, promotion, routing) needs it.                                                                                                                               |
| `S3_ENDPOINT_URL=https://<account>.r2.cloudflarestorage.com` (+ the R2 access key / secret) | --                           | ADR-0007. Snapshot storage + the model-artifact ingest at registration. Backups also depend on it.                                                                                                                                                                          |

## Optional / has a safe default (override only if your box differs)

- `bullpen.clickhouse.{url,user,password}` -- default to `jdbc:ch:http://localhost:8123/default`
  / `default` / `thebullpen`, which match the `bullpen-clickhouse` container. Override only if
  the container's address or creds differ.
- `BULLPEN_DISCORD_WEBHOOK` (`bullpen.discord.webhook`) -- drift + uptime alerts; they no-op
  silently if unset.
- `bullpen.ratelimit.*`, `bullpen.snapshot.*`, `bullpen.inference.*` -- defaulted in the
  `application*.yml` files.

(Full surface of externalized settings: `backend/src/main/resources/application{,-api,-worker}.yml`
plus the `@Value("${bullpen...}")` and `@ConditionalOnProperty` annotations in `config/` and the
controllers.)

## Verify after any deploy or restore

```bash
systemctl is-active bullpen-api bullpen-worker           # BOTH must print 'active'
systemctl show bullpen-worker -p NRestarts               # low + stable, not climbing
curl -sf http://localhost:8080/actuator/health           # api healthy
# Confirm ClickHouse is actually wired (the log line ClickHouseConfig emits when the bean builds):
journalctl -u bullpen-api    -n 60 --no-pager | grep -i "ClickHouse DataSource ready"
journalctl -u bullpen-worker -n 60 --no-pager | grep -i "ClickHouse DataSource ready"
```

A **crash-looping `bullpen-worker` is the canary** for a missing `BULLPEN_CLICKHOUSE_ENABLED` --
it's the only profile that hard-fails on the absent bean, so it surfaces the gap before the
api's silent feature loss does.

## Restore-drill hook (rule 8)

The pre-season restore drill (`/drill restore`) MUST reach **"worker `active (running)`, not
restarting"** as an exit criterion. A clean WSL2 + `git clone` + `deploy.sh` + restore that
brings the api up but leaves the worker crash-looping is an **incomplete restore** -- it has
reproduced the code + data but not this env contract. Treat a non-active worker (or a missing
"ClickHouse DataSource ready" log on either unit) as a drill failure, and reconcile
`/etc/default/bullpen` against the table above.
