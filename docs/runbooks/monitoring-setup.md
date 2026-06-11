# Monitoring + watchdogs (WS4)

The watchdog stack that gates the live-data flip: host metrics, alert routing to Discord, and a
snapshot dead-man's switch. The goal is that a silently-dead worker (the 2026-06-04 blindspot), an
OOM-pressured host, a heap-saturated JVM, or a missed nightly backup all page someone instead of
being discovered by accident.

## What landed in code

| Piece                                                                                         | File                                        |
| --------------------------------------------------------------------------------------------- | ------------------------------------------- |
| `node-exporter` (host RAM/CPU/disk) + `alertmanager`, behind the `monitoring` compose profile | `infra/docker-compose.yml`                  |
| Prometheus `alerting` + `rule_files` + the `node` scrape job                                  | `infra/prometheus/prometheus.yml`           |
| 5 alert rules (WorkerDown, ApiDown, HostMemoryLow, JvmHeapHigh, SnapshotStale)                | `infra/prometheus/rules/bullpen-alerts.yml` |
| Alertmanager -> Discord (native `discord_configs` receiver)                                   | `infra/alertmanager/alertmanager.yml`       |
| Worker `:8081` smoke + rollback on a half-up deploy                                           | `deploy.sh`                                 |
| Snapshot freshness: node_exporter textfile metric + Healthchecks.io ping                      | `infra/backup/clickhouse-snapshot.sh`       |

## Box bring-up (prod, WSL2 desktop)

1. **Discord secret for Alertmanager.** Create the gitignored secret from the example, using the
   same RAW webhook URL as `BULLPEN_DISCORD_WEBHOOK` - no suffix. (Do NOT append `/slack`: the
   Slack-compat endpoint 400s Alertmanager's payload, box-proven 2026-06-11; the config now uses
   the native `discord_configs` receiver.)

   ```bash
   cp infra/alertmanager/secrets/discord_url.example \
      infra/alertmanager/secrets/discord_url
   # edit it to the real https://discord.com/api/webhooks/<id>/<token>
   ```

2. **Validate the configs before starting** (no promtool/amtool in CI - this is the gate):

   ```bash
   promtool check config infra/prometheus/prometheus.yml
   promtool check rules  infra/prometheus/rules/bullpen-alerts.yml
   amtool check-config   infra/alertmanager/alertmanager.yml
   docker compose -f infra/docker-compose.yml --profile monitoring config >/dev/null
   ```

3. **Start the monitoring profile** (node-exporter + alertmanager join the default stack):

   ```bash
   docker compose -f infra/docker-compose.yml --profile monitoring up -d
   ```

4. **Snapshot env.** Set `BULLPEN_HC_PING_URL` (the Healthchecks.io ping URL, below) in the
   `bullpen-snapshot` unit's environment. The script writes
   `bullpen_snapshot_last_success_timestamp_seconds` into `/var/lib/node_exporter/` on success; ensure
   that dir exists and is writable by the snapshot user, and that node-exporter mounts it.

5. **Verify end-to-end:** stop `bullpen-worker` for >2m and confirm a Discord alert fires; restart and
   confirm a resolve message. Then re-enable.

## Native-dockerd quirks (the WSL2 box; box-found 2026-06-11)

Dev Macs run Docker Desktop; the prod box runs native dockerd inside WSL2. Two behaviors differ,
and both bit during the first bring-up:

- **`host.docker.internal` does not resolve on native dockerd** (Docker Desktop magic only), which
  broke Prometheus's api/worker JVM scrape targets. Fixed in code: the `prometheus` service maps it
  via `extra_hosts: ["host.docker.internal:host-gateway"]` (commit 3017a8f). No box action needed
  beyond pulling.
- **node-exporter needs shared mount propagation on `/`.** Its `rslave` root bind fails on WSL2's
  default private propagation; the container dies until the host runs:

  ```bash
  sudo mount --make-rshared /
  ```

  That command is SESSION-SCOPED - it does not survive a WSL restart. Persist it in `/etc/wsl.conf`
  on the box:

  ```ini
  [boot]
  command = mount --make-rshared /
  ```

  This line is part of the box's documented bootstrap (ADR-0006: box-only state must be
  reconstructable from the repo + documented bootstrap) and belongs on the reboot-drill checklist:
  after any cold boot, verify `bullpen-node-exporter` is up before trusting host-memory alerts.

## Console side (operator, NOT code)

- **Healthchecks.io** - create a check (period 1 day, grace ~2h) for the nightly snapshot; put its
  ping URL in `BULLPEN_HC_PING_URL`. This is the external dead-man: it fires even if the whole host
  (and Prometheus with it) is down, which the internal `SnapshotStale` rule cannot.
- **Uptime Robot** - add a monitor on the worker via the Cloudflare Tunnel (or an internal health URL)
  so a worker-down is caught even if Alertmanager/Prometheus are themselves down.

## Notes

- Alertmanager + node-exporter are behind the `monitoring` profile so the default `docker compose up`
  stays lean and dev does not need the Discord secret (Prometheus tolerates an absent Alertmanager).
- `SnapshotStale` is deliberately doubled: the internal Prometheus rule (textfile metric) for the
  dashboard + the external Healthchecks.io dead-man for the host-down case. A backup is exactly where
  a single internal watchdog is insufficient.
