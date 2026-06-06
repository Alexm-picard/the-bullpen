-- V018 - issue #1 step 7b: live-game status surfaced to the api read path.
-- The poller (worker) tracks each game's GameStatus in memory, but /v1/games/today reads from
-- ClickHouse in the api profile - the two processes can't share memory. This tiny table is the
-- bridge: the poller upserts a game's status (only on a transition) and findGamesForDate / findGame
-- LEFT JOIN it, replacing the hardcoded "UNKNOWN".
--
-- ReplacingMergeTree(updated_at) keyed on game_id: each upsert supersedes the prior row; an
-- argMax(status, updated_at) read takes the latest. Tiny - one row per game per status transition.
CREATE TABLE IF NOT EXISTS live_game_status (
    game_id     UInt64,
    game_date   Date,
    status      LowCardinality(String),   -- GameStatus enum name: IN_PROGRESS, SCHEDULED, FINAL...
    updated_at  DateTime DEFAULT now()
)
ENGINE = ReplacingMergeTree(updated_at)
PARTITION BY toYYYYMM(game_date)
ORDER BY (game_id);
