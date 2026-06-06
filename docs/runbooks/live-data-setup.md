# Live game data + predictions — setup runbook

> **Scope:** wire the **write side** of the live-game pipeline so
> `thebullpen.net` shows live pitches and the model's per-pitch prediction
> during a real game. The read/serve/display half (table, repo, API,
> frontend polling, state machine) is already built and tested — this
> runbook closes the gap from "endpoints return empty" to "live data
> flowing."
>
> **Where it runs:** the poller is a **worker-profile** bean on the
> **desktop** (ADR-0006). Authoring happens on the Mac; it's deployed via
> `git push` + `./deploy.sh`. The `api`-profile process never runs the
> poller.
>
> **Status at time of writing (2026-05-30):** not built. The pre-pitch head
> — which powers live pitch predictions — is trained, registered, and
> serving, so the model side is ready. This is a Phase 4d close (leaves
> 4d.1 / 4d.2); the README lists it under the v1.5 roadmap.

---

## Data flow (what exists, what's missing)

```
 MLB Stats API
      │  poll: schedule (gamePks + status) + per-game live feed (GUMBO)
      ▼
 LivePollingService  ◄── GameStateMachine (cadence + transition validation)   ✗ build
      ├─► INSERT new pitches ──────────────► pitches_live  (ClickHouse V015)   ✓ table exists
      └─► assemble PitchRequest, call A/B router (pre head)
              → AsyncPredictionLogger ─► prediction_log                        ✗ wire + key
                       keyed (game_id, at_bat_index, pitch_number)
                 ┌───────────────────────────────────┘
 pitches_live  LEFT JOIN  prediction_log   ◄── join + key columns              ✗ build
      ▼
 LivePitchesRepository.findPitchesSince ─► GET /v1/games/{id}/pitches?since=   ✓ built
      ▼
 useLivePitches (TanStack Query, cursor-delta polling) ─► game-page.tsx        ✓ built
```

### Already built ✓

- `pitches_live` table — `backend/.../db/migration/clickhouse/V015__pitches_live.sql`
  (sparse live columns, `ReplacingMergeTree(ingested_at)`, 14-day TTL).
- `LivePitchesRepository` — `findGamesForDate`, `findGame`, `findPitchesSince`.
- `GameController` — `/v1/games/today`, `/{id}`, `/{id}/pitches?since=`
  (returns empty, not error, when the table's empty).
- `GameStateMachine` + `GameStatus` — transition validator + per-status poll
  cadence (`shouldPoll`, `pollIntervalFor`). Complete + tested.
- Frontend — `frontend/src/api/games.ts` (`useTodaysGames`, `useGame`,
  `useLivePitches` cursor-delta polling); `LivePitchRow` already has
  `predictedClasses` / `predictedWinner` slots.

### The gap — all on the write side

1. **No `MlbStatsApiClient`** — nothing fetches the schedule or game feed.
2. **No `LivePollingService`** — nothing writes `pitches_live` or invokes
   inference on live pitches.
3. **`prediction_log` has no natural key.** It stores `prediction` /
   `features` as JSON blobs (the V0xx DDL even notes "Phase 3b.5 will add
   structured columns"). So the planned `LEFT JOIN` can't resolve until the
   key columns exist — this is the same truth-join `observations.md`
   (2026-05-25, backend) flagged.
4. **The repo join isn't written** — `LivePitchesRepository`'s pitch mapper
   hardcodes `null, null` for the prediction columns.
5. **Status is hardcoded `"UNKNOWN"`** in `findGamesForDate`/`findGame`
   until the poller maintains a status store.

### Why pre-pitch prediction is the easy part

`PitchRequest` (the predict input) needs only **Tier 1+2** fields as
required — count, outs, inning, base state, score diff, day-of-week,
pitcher/batter throws/stand + ids, park. **Every one of those is in the
live feed's pre-pitch state.** Tier 3 (rolling form) is optional → `null`
→ LightGBM treats as NaN; Tier 4 (pitch physics) is post-pitch only and
only the post head needs it. So live pre-pitch prediction is "assemble a
`PitchRequest` from feed state and call the existing
`PitchInferenceService`" — no new feature-engineering subsystem.

---

## Prerequisites

- Pre-pitch head registered + serving (done). Verify it answers:
  ```bash
  curl -s -X POST localhost:8080/v1/predict/pitch -H 'content-type: application/json' \
    -d '{"countBalls":1,"countStrikes":2,"outs":1,"inning":5,"baseState":0,
         "scoreDiff":0,"dow":3,"pitcherThrows":"R","batterStand":"L","parkId":"BOS",
         "pitcherId":1,"batterId":2}' | jq .
  ```
- `pitches_live` (V015) migrated — Flyway applies on `api`-profile boot.
- ClickHouse + the worker profile reachable from the desktop.

---

## Build (Mac, authored + tested with mocked HTTP)

Order matters — each step's gate must pass before the next. Build numbers
map 1:1 to the tracking issue's task checklist.

### 1. `MlbStatsApiClient` — new, `ingest/`

- **Schedule:** `GET https://statsapi.mlb.com/api/v1/schedule?sportId=1&date=YYYY-MM-DD`
  → `dates[].games[]` (`gamePk`, `status.detailedState`, teams).
- **Live feed (GUMBO):** `GET https://statsapi.mlb.com/api/v1.1/game/{gamePk}/feed/live`
  → `liveData.plays.allPlays[]` → `about` (atBatIndex, inning), `matchup`
  (batter/pitcher ids, batSide, pitchHand), and `playEvents[]` where
  `isPitch` (pre-pitch `count`, `pitchNumber`, `details.type`,
  `pitchData.startSpeed`, `pitchData.coordinates`).
- Public, no auth. Be a good citizen: set a `User-Agent`, honour the
  per-state poll cadence, exponential backoff on 429/5xx, short timeouts.
- Map `status.detailedState` strings → `GameStatus`.
- **Gate:** parser unit tests against a **captured real game feed** (record
  one game's `feed/live` JSON to `src/test/resources/`, replay it). The MLB
  HTTP boundary is the one place mocking is allowed (CLAUDE.md testing
  posture). Confirm field paths against the fixture — GUMBO is large.

### 2. `pitches_live` writer — extend `LivePitchesRepository` (or `LivePitchesWriter`)

- Batch `INSERT INTO pitches_live (...)`; idempotent via
  `ReplacingMergeTree` (re-insert of a corrected pitch overwrites).
- **Gate:** Testcontainers ClickHouse IT — insert fixture pitches, read
  back via `findPitchesSince`.

### 3. `prediction_log` natural-key migration — use the `add-schema-change` skill

- Add nullable, defaulted `game_id UInt64`, `at_bat_index UInt16`,
  `pitch_number UInt8` columns. Defaulted so historical/shadow rows are
  untouched; only live-game predictions populate them.
- Update `PredictionLogEvent` + the writer to carry the optional key.
- Coordinated change (ClickHouse DDL + the contract/repo) per the
  schema-migration discipline.
- **Gate:** migration applies; existing predict logging is unaffected (key
  columns null on the HTTP path); a live-keyed write round-trips.

### 4. Predict-on-live-pitch wiring

- For each new pre-pitch context: assemble a `PitchRequest` (Tier 1+2 from
  the feed; Tier 3 `null` for v1) → call `PitchInferenceService` (pre head)
  → log to `prediction_log` **with the step-3 key**.
- (Optional, see Open Questions) run the post head when a pitch's Tier 4
  physics land in the feed.
- **Gate:** replay a fixture game → `prediction_log` rows appear keyed to
  the right `(game_id, at_bat_index, pitch_number)`.

### 5. The repo join — `LivePitchesRepository`

- `FIND_PITCHES_SINCE` gains `LEFT JOIN prediction_log` on the natural key;
  the mapper parses the prediction JSON → `Map<String,Double>` and argmax →
  `predictedWinner`, replacing the hardcoded `null, null`.
- **Gate:** IT — `pitches_live` + `prediction_log` fixtures →
  `/v1/games/{id}/pitches` returns rows carrying predictions; pitches
  without a logged prediction return `null` (the frontend's "n/a" path).

### 6. `LivePollingService` — new, `ingest/`, `@Profile("worker") @Scheduled`

- The loop tying 1→2→4 together: fetch today's schedule → for each
  non-terminal game, poll at `GameStateMachine.pollIntervalFor(status)` →
  `transition()`-validate → parse new pitches → write `pitches_live` →
  predict + log. Keep an in-memory `gameId → status` map.
- Config in `application-worker.yml`: `bullpen.ingest.live.enabled`, base
  URL, HTTP timeouts, cadence overrides. Model it on the existing
  worker-profile `@Scheduled` jobs (`drift/jobs/PsiFeatureJob`, etc.).
- **Gate:** end-to-end IT — a replayed feed drives the whole chain; assert
  `pitches_live` + `prediction_log` fill and the API returns predicted
  pitches.

### 7. Status decoration (small)

- Wire the poller's real status into `findGamesForDate`/`findGame`
  (replaces hardcoded `"UNKNOWN"`).
- Confirm `frontend/src/pages/game-page.tsx` renders the prediction column
  (the `LivePitchRow` type already carries it; verify the JSX uses it).

---

## Verify → deploy → operate (desktop)

> **Production-reviewed 2026-06-06** (Dev/Prod cross-environment pass). The build
> (steps 1-7) is on `origin/main`; the loop is OFF by default
> (`bullpen.ingest.live.enabled=false`). What follows is the reviewed enable
> procedure. Migrations V017 (`prediction_log` natural-key columns) + V018
> (`live_game_status`) are both **additive** (`ADD COLUMN IF NOT EXISTS` /
> `CREATE TABLE IF NOT EXISTS`) - verified against the live `prediction_log` sort
> key - so no recreate, no data loss.

### Pre-checks (confirm before running)

- **P1 - migrations additive.** Verified: V017 only appends 3 nullable columns
  after `correlation_id`; the `ORDER BY (model_name, request_at)` sort key,
  partitioning, and TTL are untouched. Existing rows are safe.
- **P2 (GATE) - is the pitch pre-head loaded?** `predictedWinner` comes from the
  read-path LEFT JOIN to `prediction_log`, whose rows the worker writes via
  `LivePitchPredictor` - which is gated on `PitchInferenceService`, which is gated
  on `Files.exists(.../pitch_outcome_pre/v1/model.onnx)`. The **same** condition
  gates `/v1/predict/pitch`. So:
  ```bash
  curl -s -o /dev/null -w "%{http_code}\n" -X POST http://localhost:8080/v1/predict/pitch \
    -H 'content-type: application/json' \
    -d '{"countBalls":1,"countStrikes":2,"outs":1,"inning":5,"baseState":0,"scoreDiff":0,"dow":3,"pitcherThrows":"R","batterStand":"L","parkId":"BOS","pitcherId":1,"batterId":2}'
  ```
  **200** = pre-head loaded → live predictions will render. **404** = pre-head
  artifact not on the box → the dry-run validates the **write path only** (pitches
  - status); predictions stay "n/a" until the artifact lands. Deploy the pre-head
    artifact first if visible predictions are the goal.

### 8. Dry-run (recommended FIRST: fixture replay - no live API, no game window)

Point the poller at a local server replaying the committed fixtures, so writes +
the predict-next dedup are validated against a saved feed at any time.

```bash
# 0. SNAPSHOT first - the replay still DEPLOYS 885a868, which applies V017/V018 on boot.
infra/backup/clickhouse-snapshot.sh          # verify a fresh snapshot lands (hard rule)

# deploy the dormant build
./deploy.sh

# start the fixture-replay server (infra/live-replay/replay_server.py): pure fixture server -
# never hits the real API, binds 127.0.0.1 only, deterministic. It progressively reveals at-bats
# from the committed real game so the loop writes new pitches + predicts one new upcoming pitch per
# poll. Verify its invariants first, then run it backgrounded:
python3 infra/live-replay/replay_server.py --self-test
nohup python3 infra/live-replay/replay_server.py --port 9099 >/tmp/replay.log 2>&1 &

# point the worker at the replay + enable (idempotent set-or-replace, NOT tee -a alone)
sudo sed -i '/^BULLPEN_INGEST_LIVE_BASE_URL=/d;/^BULLPEN_INGEST_LIVE_ENABLED=/d' /etc/default/bullpen
printf 'BULLPEN_INGEST_LIVE_BASE_URL=http://localhost:9099\nBULLPEN_INGEST_LIVE_ENABLED=true\n' | sudo tee -a /etc/default/bullpen
sudo systemctl restart bullpen-worker
```

Validate: pitches land, status surfaces, and crucially the dedup holds -
`keyed_preds ≈ distinct_pitches` (one prediction per upcoming pitch, not one per
poll):

```bash
docker exec bullpen-clickhouse clickhouse-client --query \
  "SELECT count() AS keyed_preds, uniqExact((game_id,at_bat_index,pitch_number)) AS distinct_pitches \
   FROM prediction_log WHERE game_id IS NOT NULL AND request_at > now() - INTERVAL 1 HOUR"
```

### 9. Deploy + enable for a live game - **off-window only** (rule 3)

Do the deploy + enable + worker restart in a **pre-game no-game window**; only
_observe_ during the game.

```bash
infra/backup/clickhouse-snapshot.sh           # step 0: snapshot before migrations
./deploy.sh                                   # poller ships DORMANT (flag off, zero behavior change)

# enable idempotently (set-or-replace, NOT tee -a which duplicates the line)
sudo sed -i '/^BULLPEN_INGEST_LIVE_BASE_URL=/d;/^BULLPEN_INGEST_LIVE_ENABLED=/d' /etc/default/bullpen
echo 'BULLPEN_INGEST_LIVE_ENABLED=true' | sudo tee -a /etc/default/bullpen
sudo systemctl restart bullpen-worker
```

> Capture `BULLPEN_INGEST_LIVE_ENABLED` (and `BULLPEN_CLICKHOUSE_ENABLED`) in
> `desktop-environment.md` as part of this change so the restore drill doesn't
> regress them (closes the env-regression class).

**Abort** (poller goes dormant, all services keep running):

```bash
sudo sed -i 's/^BULLPEN_INGEST_LIVE_ENABLED=true/BULLPEN_INGEST_LIVE_ENABLED=false/' /etc/default/bullpen
sudo systemctl restart bullpen-worker
```

### 10. Operate (observe-only during the game)

```bash
journalctl -u bullpen-worker -n 100 --no-pager | grep -iE "DataSource ready|V017|V018|LivePolling|live poll"
docker exec bullpen-clickhouse clickhouse-client --query \
  "SELECT game_id, count() AS pitches, max(inning) AS inn FROM pitches_live FINAL WHERE game_date = today() GROUP BY game_id ORDER BY pitches DESC"
docker exec bullpen-clickhouse clickhouse-client --query \
  "SELECT game_id, argMax(status, updated_at) AS status FROM live_game_status GROUP BY game_id"
curl -s http://localhost:8080/v1/games/today | jq '.[] | {gameId, status, detailedState}'
```

- Watch poll cadence (12s live), no `429`s, sane insert rate, the dedup ratio.
- Append surprises to `docs/hardening/observations.md`.
- **Holdout (rule 13):** the overnight `pitches_live → pitches` handoff
  (separate, later job - see V015 header comment) backfills Statcast fields into
  the canonical table; it must keep 2026 rows out of any training split. Live
  display is fine; training ingestion is the line.

---

## Open design questions (settle via `/decide` before build)

These are genuine choices, not implementation detail — lock them first:

1. **Live-prediction semantics.** Do we log the pre-pitch prediction keyed
   to each _completed_ pitch (clean calibration data, joins to outcome), or
   predict the _next_ pitch on the current live count (better "live" UX,
   harder to truth-join)? Proposal: log per-completed-pitch for v1 — it's
   the calibration-honest version and the join is exact.
2. **Post head live?** Run only the pre head live (Tier 1+2, always
   available), or also the post head once Tier 4 physics arrive in the
   feed? Proposal: pre head only for v1; post head is a fast follow.
3. **Tier 3 form at serve time.** Leave Tier 3 `null` (degraded but valid),
   or look up a `pitcher_form_current` table? Proposal: `null` for v1; the
   DTO + model already handle it; the form-lookup job is a separate item.

---

## Constraints (recap)

- **ADR-0006** — poller is worker-profile, authored on the Mac, runs on the
  desktop. No editing on the prod box.
- **Rule 3** — no deploys during live games (evenings Apr–Oct).
- **Rule 13** — 2026 is holdout-only; live display never feeds a training
  split.
- **Testing posture** — mock only the MLB HTTP boundary; ClickHouse via
  Testcontainers, real inference path.

## When this runbook should change

- MLB changes the StatsAPI schema → re-capture the fixture, update the
  parser.
- The `pitches_live → pitches` overnight handoff lands → add it as step 11.
- A top-level decision reverses one of the Open Questions → update the
  affected build step and reference the decision number.
