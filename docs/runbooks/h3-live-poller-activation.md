# H3 - Live-poller activation (off-window)

> **Scope:** operator hand-off to turn on the MLB live-game poller for the first
> time (or re-enable it after a rollback). The poller is built and on `origin/main`
> but dormant by default (`BULLPEN_INGEST_LIVE_ENABLED=false`). This runbook
> covers the off-window deploy, the environment flip, the dry-run validation, and
> the live enable + rollback procedure.
>
> **Where:** the WSL2 desktop (ADR-0006). Authoring is on the Mac; deployment
> crosses git (`./deploy.sh`). The operator runs the commands below on the box.
>
> **Rule 3:** never deploy during live MLB games (evenings April-October). Do
> the deploy and environment flip in a pre-game no-game window (morning, off-day,
> or winter). Observe only during a game - no changes while the game is live.
>
> **References:** `docs/runbooks/live-data-setup.md` (full build rationale,
> step-8 dry-run procedure, step-9 enable, step-10 operate), decisions [143]
> (predict-next semantics), [144] (inference loader widened to worker profile),
> issue #1.

---

## Context: what is already built

The full producer chain (steps 1-7 of `live-data-setup.md`) is merged to
`origin/main` as of 2026-06-06:

- `MlbStatsApiClient` + `MlbFeedParser`
- `pitches_live` writer
- `prediction_log` natural-key migration (V017 - additive `ADD COLUMN IF NOT EXISTS`)
- `live_game_status` migration (V018 - additive `CREATE TABLE IF NOT EXISTS`)
- `LivePitchPredictor` (pre-head, in-process, worker profile per decision [144])
- `LivePollingService` (worker `@Scheduled`, cadence from `GameStateMachine`)
- Status decoration, frontend prediction column wired

**The loop is off.** `bullpen.ingest.live.enabled` defaults to `false` in
`application-worker.yml`. The application default must NOT be changed (do not
commit a change to that file). The flag is controlled exclusively by the box's
`/etc/default/bullpen`.

**Live predictions gate on the pre-head artifact.** If `pitch_outcome_pre/v1/model.onnx`
is not on the box, the dry-run still validates the write path (pitches + status),
but predictions render "n/a" in the UI. Run `h1-box-training-trigger.md` and
stage the pre-head artifact before enabling if visible predictions are the goal.

---

## Gate P1 - confirm the deploy is additive (verify once before enabling)

Migrations V017 and V018 are both additive - verified in the production review:

- V017: `ADD COLUMN IF NOT EXISTS` on `prediction_log` - appends 3 nullable
  columns (`game_id`, `at_bat_index`, `pitch_number`) after `correlation_id`.
  The `ORDER BY (model_name, request_at)` sort key, partitioning, and TTL are
  untouched. Existing rows are safe.
- V018: `CREATE TABLE IF NOT EXISTS live_game_status` - new table, no existing
  table affected.

Confirm before deploying:

```bash
# On the box, check what the current schema looks like
docker exec bullpen-clickhouse clickhouse-client --password thebullpen \
  --query "DESCRIBE TABLE prediction_log"
# Should show existing columns unchanged. V017 adds 3 nullable columns.
```

---

## Gate P2 - pre-head artifact loaded? (determines prediction visibility)

```bash
# On the box
curl -s -o /dev/null -w "%{http_code}\n" -X POST http://localhost:8080/v1/predict/pitch \
  -H 'content-type: application/json' \
  -d '{"countBalls":1,"countStrikes":2,"outs":1,"inning":5,"baseState":0,
       "scoreDiff":0,"dow":3,"pitcherThrows":"R","batterStand":"L",
       "parkId":"BOS","pitcherId":1,"batterId":2}'
```

- **200** means the pre-head artifact is loaded - live predictions will render.
- **404** means the artifact is not staged on the box yet - the dry-run is still
  valid for the write path (pitches + status) but predictions will show "n/a".
  Stage the artifact and restart the services before expecting visible predictions.

---

## Step 1 - Snapshot (always, before deploy)

```bash
# On the box
infra/backup/clickhouse-snapshot.sh
# Confirm: .last_snapshot_ok mtime is today
ls -lh /var/lib/clickhouse-backup/.last_snapshot_ok
```

The snapshot is the rollback safety net. Do not skip it.

---

## Step 2 - Deploy the dormant build

The box may be stale relative to `origin/main`. Deploy first; the flag remains
`false` so the poller does not start yet - zero behavior change on deploy.

```bash
# On the box, from the repo root (do NOT edit files on the box - ADR-0006)
cd ~/code/the-bullpen
git pull --ff-only origin main   # confirm we are at the intended SHA
git log --oneline -3

./deploy.sh
# deploy.sh builds the JAR, stages it, swaps the symlink, restarts both units,
# and runs a 30-second health smoke. V017 + V018 apply via Flyway on boot.
```

Verify Flyway applied the migrations cleanly:

```bash
journalctl -u bullpen-worker -n 50 --no-pager | grep -iE "V017|V018|migration|Flyway"
journalctl -u bullpen-api    -n 50 --no-pager | grep -iE "V017|V018|migration|Flyway"
# Expect "Successfully applied 2 migrations"
```

---

## Step 3 - Dry-run (recommended before enabling against the live MLB API)

The fixture-replay server replays a committed real game feed from
`infra/live-replay/replay_server.py` without hitting the MLB API. It binds
`127.0.0.1` only, is deterministic, and writes synthetic rows under sentinel
gamePk `900000824` (floor `900000000`).

It now opens with a **pre-game phase** (C2): the schedule reports the game
`Scheduled` (no plays) for the first `--pregame-polls` schedule polls (default 3),
then transitions to `In Progress`, so the dry-run exercises the poller's pre-game
discovery + the `SCHEDULED -> Live -> Final` arc, not just live polling. Expect
the sentinel game to sit `SCHEDULED` in `live_game_status` for the first minute or
so before pitches start landing. Pass `--pregame-polls 0` for the old
live-immediately behaviour. The self-test asserts the full arc.

```bash
# Run the replay server's self-test first (asserts SCHEDULED -> Live -> Final)
python3 infra/live-replay/replay_server.py --self-test

# Start it backgrounded
nohup python3 infra/live-replay/replay_server.py --port 9099 >/tmp/replay.log 2>&1 &

# Point the worker at the replay and enable
# Use set-or-replace (NOT tee -a alone, which duplicates the line on re-run)
sudo sed -i '/^BULLPEN_INGEST_LIVE_BASE_URL=/d;/^BULLPEN_INGEST_LIVE_ENABLED=/d' /etc/default/bullpen
printf 'BULLPEN_INGEST_LIVE_BASE_URL=http://localhost:9099\nBULLPEN_INGEST_LIVE_ENABLED=true\n' \
  | sudo tee -a /etc/default/bullpen
sudo systemctl restart bullpen-worker
```

Verify: pitches land and dedup holds (one prediction per upcoming pitch, not
one per poll):

```bash
# After a few minutes of replay
docker exec bullpen-clickhouse clickhouse-client --password thebullpen --query \
  "SELECT count() AS pitches FROM pitches_live
   WHERE game_date = today()"

docker exec bullpen-clickhouse clickhouse-client --password thebullpen --query \
  "SELECT count() AS keyed_preds,
          uniqExact((game_id, at_bat_index, pitch_number)) AS distinct_pitches
   FROM prediction_log
   WHERE game_id IS NOT NULL AND request_at > now() - INTERVAL 1 HOUR"
# keyed_preds should approximately equal distinct_pitches (dedup working)

docker exec bullpen-clickhouse clickhouse-client --password thebullpen --query \
  "SELECT game_id, argMax(status, updated_at) AS status FROM live_game_status GROUP BY game_id"
# Should show the sentinel game_id with a status (IN_PROGRESS or similar)
```

**Synthetic-row contamination boundary:** every dry-run row satisfies
`game_id >= 900000000`. All drift / calibration / eval / training queries MUST
exclude `game_id >= 900000000` - these rows are permanently identifiable by range.

After validating, stop the dry-run and restore the BASE_URL:

```bash
# Kill the replay server
kill $(cat /tmp/replay.pid 2>/dev/null) 2>/dev/null || pkill -f replay_server.py

# Remove the BASE_URL override (keep ENABLED=false for now)
sudo sed -i '/^BULLPEN_INGEST_LIVE_BASE_URL=/d;/^BULLPEN_INGEST_LIVE_ENABLED=/d' /etc/default/bullpen
echo 'BULLPEN_INGEST_LIVE_ENABLED=false' | sudo tee -a /etc/default/bullpen
sudo systemctl restart bullpen-worker
```

To purge the dry-run's synthetic rows (take a snapshot first):

```bash
infra/backup/clickhouse-snapshot.sh
for t in pitches_live prediction_log live_game_status; do
  docker exec bullpen-clickhouse clickhouse-client --password thebullpen \
    --query "ALTER TABLE $t DELETE WHERE game_id >= 900000000"
done
```

---

## Step 4 - Enable against the live MLB API (off-window only)

Do this in a pre-game no-game window (rule 3). Then observe during the game; no
changes while the game is live.

```bash
# Final snapshot before enabling
infra/backup/clickhouse-snapshot.sh

# Enable (idempotent set-or-replace)
sudo sed -i '/^BULLPEN_INGEST_LIVE_ENABLED=/d' /etc/default/bullpen
echo 'BULLPEN_INGEST_LIVE_ENABLED=true' | sudo tee -a /etc/default/bullpen
sudo systemctl restart bullpen-worker
```

Capture the flag in `docs/runbooks/desktop-environment.md` as part of this
change so the restore drill does not regress it.

---

## Verify (observe-only during game)

```bash
# Worker starting the poller
journalctl -u bullpen-worker -n 100 --no-pager | \
  grep -iE "LivePolling|live poll|DataSource ready"

# Pitches landing
docker exec bullpen-clickhouse clickhouse-client --password thebullpen --query \
  "SELECT game_id, count() AS pitches, max(inning) AS inn
   FROM pitches_live FINAL
   WHERE game_date = today()
   GROUP BY game_id ORDER BY pitches DESC"

# Game status
docker exec bullpen-clickhouse clickhouse-client --password thebullpen --query \
  "SELECT game_id, argMax(status, updated_at) AS status FROM live_game_status GROUP BY game_id"

# API surface
curl -s http://localhost:8080/v1/games/today | \
  python3 -m json.tool | grep -E "gameId|status|detailedState"

# Dedup check (one prediction per pitch)
docker exec bullpen-clickhouse clickhouse-client --password thebullpen --query \
  "SELECT count() AS keyed_preds,
          uniqExact((game_id, at_bat_index, pitch_number)) AS distinct_pitches
   FROM prediction_log
   WHERE game_id IS NOT NULL
     AND game_id < 900000000
     AND request_at > now() - INTERVAL 2 HOUR"
```

Watch for: poll cadence (12s during LIVE status), no 429 responses from the MLB
API, sane insert rate, the dedup ratio holding.

---

## Rollback (abort - poller goes dormant, services keep running)

```bash
sudo sed -i 's/^BULLPEN_INGEST_LIVE_ENABLED=true/BULLPEN_INGEST_LIVE_ENABLED=false/' \
  /etc/default/bullpen
sudo systemctl restart bullpen-worker
journalctl -u bullpen-worker -n 20 --no-pager | grep -i "live"
# Confirm the polling log lines stop appearing
```

This is non-destructive: `pitches_live`, `prediction_log`, and `live_game_status`
retain the rows already written. The deploy-level rollback (JAR symlink swap) is
in `deploy.sh` - use it if the application itself needs to be rolled back.

---

## When this runbook should change

- MLB changes the StatsAPI schema: re-capture the fixture
  (`infra/live-replay/`), update the parser on the Mac, push, deploy.
- The `pitches_live -> pitches` overnight handoff lands: add a step confirming
  it excludes 2026 rows from any training split (rule 13).
- A top-level decision reverses one of decisions [143] or [144]: update the
  affected step and reference the new decision number.
