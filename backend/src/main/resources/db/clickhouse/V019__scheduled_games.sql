-- V019 - the day's scheduled slate, persisted so /v1/games/today can surface games
-- BEFORE first pitch. Today /v1/games/today is driven by `pitches_live`, so a game only
-- appears once it has thrown pitches (~first pitch); the full day's card is invisible until
-- then, and team names/start times don't exist anywhere pre-game (pitches_live carries them,
-- but it's empty until the game starts). This table is the schedule of record: the poller
-- upserts every game on each schedule refresh (~15 min), so the complete day's slate is
-- present well before the earliest first pitch (the "populate the day at ~11:00 ET" ask).
--
-- Carries team names (abbreviation + full) + scheduled first pitch. Probable pitchers land
-- here in Phase 2 (additive columns) to feed the matchup classification.
--
-- ReplacingMergeTree(ingested_at) keyed on game_id: each refresh supersedes the prior row;
-- an argMax / FINAL read takes the latest. One row per game per day - tiny.
CREATE TABLE IF NOT EXISTS scheduled_games (
    game_id        UInt64,
    game_date      Date,
    game_time_utc  Nullable(DateTime),       -- scheduled first pitch (UTC), from schedule gameDate
    home_team      LowCardinality(String),   -- abbreviation (e.g. BOS); '' when the feed omits it
    away_team      LowCardinality(String),
    home_name      String,                   -- full name (e.g. Boston Red Sox)
    away_name      String,
    status         LowCardinality(String) DEFAULT 'SCHEDULED',
    ingested_at    DateTime DEFAULT now()
)
ENGINE = ReplacingMergeTree(ingested_at)
PARTITION BY toYYYYMM(game_date)
ORDER BY (game_id);
