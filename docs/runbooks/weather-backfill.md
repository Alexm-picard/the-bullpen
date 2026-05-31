# Per-game weather backfill + weather-aware retrodiction re-run

> **Scope:** populate the `weather_observed` ClickHouse table with real
> per-game observed weather (MLB Stats API, decision [88] observed leg),
> then re-run the 2c retrodiction so every batted ball is retrodicted with
> its **actual** game-time wind instead of the per-park seasonal prevailing
> wind. This is the fix for the scrambled 2c.7 cross-park HR ranking (ATH
> predicted rank 2 vs real ~26, ATL 27 vs 5, PHI 29 vs 9, CHC 30 vs 17).
>
> **Why it was scrambled:** the retrodiction applied each park's single
> seasonal prevailing wind from `infra/park_geometry/<park>.json` to every
> BIP at that park. Oakland's constant ~10 mph out-blowing default made it
> look like a launching pad; bimodal/variable-wind parks (Wrigley, SF) were
> badly misrepresented by one seasonal average. Retrodicting each ball with
> its real wind, applied identically across all 30 counterfactual parks,
> isolates each park's physical HR factor against the true league-wide
> (launch, wind) distribution.
>
> **Where it runs:** the desktop (ADR-0006) — that's where the
> `bullpen-clickhouse` container and the full `pitches` history live, and
> where the MLB Stats API is reachable. Code is authored on the Mac and
> arrives via `git push` + `./deploy.sh`; only the data commands below run
> on the desktop.

---

## What changed in the code

- New table `weather_observed` — migration
  `backend/src/main/resources/db/clickhouse/V016__weather_observed.sql`.
- New ingest `bullpen_training.ingest.weather_backfill` (pull + insert) and
  `bullpen_training.ingest.weather` (fetch + parse the MLB feed).
- Retrodiction now joins weather: `retrodict.run_pipeline` bulk-loads
  `weather_observed`, passes each BIP's `Weather` to
  `retrodict_bip_at_all_parks`, and applies the field-relative wind at all
  30 parks (`retrodict._atmospheres.weather_to_atmosphere`). Games with no
  weather row fall back to **still air**, never the seasonal wind.
- `run_pipeline` gained `--min-weather-coverage` (fail fast if the backfill
  is too sparse). `scripts/run_2c_overnight.sh` passes
  `MIN_WEATHER_COVERAGE` (default `0.9`) to both retrodict stages.

---

## Prerequisites on the desktop

- Repo synced to `origin/main` past the weather-backfill commit; `uv` installed.
- ClickHouse container up: `docker compose -f infra/docker-compose.yml up -d`.
- `pitches` populated for 2015–2025 (same prereq as the overnight pipeline).
- **Apply V016.** Flyway-style runner applies it on Spring boot; or apply manually:
  ```bash
  docker exec -i bullpen-clickhouse clickhouse-client \
      < backend/src/main/resources/db/clickhouse/V016__weather_observed.sql
  ```
- Network reachable to `statsapi.mlb.com`.

---

## Procedure (from `training/`)

### 1. Backfill weather for 2015–2025

The retrodiction covers the training range **and** the val season
(`run_2c_overnight.sh` retrodicts `VAL_SEASON`, default 2025), so back-fill
the full 2015–2025 span:

```bash
# smoke first: 50 games, confirm rows land
uv run python -m bullpen_training.ingest.weather_backfill --season 2024 --limit 50

# full backfill (resumable; ~tens of minutes, network-bound)
nohup uv run python -m bullpen_training.ingest.weather_backfill \
    --season-from 2015 --season-to 2025 \
    --report data/weather_backfill_2015_2025.json \
    > logs/weather-backfill.log 2>&1 &
```

Resumable + idempotent: games already in `weather_observed` are skipped, so
a re-run only fetches the unwritten tail and never double-counts
(ReplacingMergeTree on `game_id`). A transient API failure is logged and
the game is counted `missing` — re-run to pick it up.

### 2. Verify coverage

```bash
docker exec bullpen-clickhouse clickhouse-client --query \
"SELECT toYear(game_date) y, count() games, sum(is_indoor) indoor,
        round(avgIf(wind_speed_mph, is_indoor=0),1) avg_wind_mph
 FROM weather_observed GROUP BY y ORDER BY y"
```

Expect ~2,400 games/season, single-digit average wind, a handful of indoor
games (domes). Cross-check against the pitches game count:

```bash
docker exec bullpen-clickhouse clickhouse-client --query \
"SELECT count(DISTINCT game_id) FROM pitches WHERE toYear(game_date) BETWEEN 2015 AND 2025"
```

Coverage should be ≥ 90% (the `--min-weather-coverage 0.9` guard).

### 3. Snapshot ClickHouse before the re-write

The retrodiction re-run rewrites `bbip_retrodicted_labels`. **Never touch
live ClickHouse without a snapshot first** (hard rule):

```bash
bash infra/backup/clickhouse-snapshot.sh
```

### 4. Re-run the overnight pipeline

The orchestrator now retrodicts with weather and fails fast (at 2c.4) if
coverage is below `MIN_WEATHER_COVERAGE`:

```bash
bash scripts/run_2c_overnight.sh --dry-run     # confirm resolved args first
nohup bash scripts/run_2c_overnight.sh \
    > logs/2c-overnight/orchestrator.log 2>&1 &
```

The 2c.4 stage prints `weather_observed: N games with weather / M games in
pitches (XX% coverage)` at the top — confirm it's ≥ 90% before letting it
run overnight. (To re-run just the labels without the GPU stages, run
`retrodict.run_pipeline --season-from 2015 --season-to 2025
--min-weather-coverage 0.9` directly.)

### 5. Verify the gate passes

```bash
cat logs/2c-overnight/2c.7-sanity-gate.log
```

Expect Spearman ρ ≥ 0.80 and the COL − ATH P(HR) gap ≥ 0.05. Spot-check
that the previously-scrambled parks now track
`training/data/published_hr_factors.json`: ATH near the bottom (0.92), ATL
upper third (1.06), CHC mid (0.99), PHI upper-mid (1.04). If the gate still
fails, read the per-park gap diagnostics in the saved
`data/cross_park_sanity_report.json` before registering anything.

---

## Follow-up

- Record a `docs/decisions.md` entry (via `decision-recorder`): implements
  decision [88]'s observed leg into retrodiction; field-relative observed
  wind applied across all 30 parks; references [131]/[132]. With per-game
  wind now in place, the [131] re-validation target (tighten the physics
  gate back to ≥95% / ±5% / ±15 ft and reintroduce fly/LD fixtures) is
  unblocked.
- Future (decision [88]'s other leg): a Java `ingest/` pre-game **forecast**
  pull, plus a shared wind-label parser so live serving and training stay in
  sync. Out of scope here — this runbook covers the observed pull only.
