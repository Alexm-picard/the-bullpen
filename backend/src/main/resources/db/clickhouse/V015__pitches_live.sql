-- V015 — Phase 4d.1
-- Live-game pitches captured from MLB Stats API. Separate from `pitches`
-- (V003) because:
--   - the live feed is sparse: only the fields the StatsAPI exposes pre-Statcast
--     official import are populated; Statcast fields backfill overnight via the
--     handoff job that moves rows into the canonical `pitches` table.
--   - latency: the live table is the only thing the /v1/games/:id/pitches
--     endpoint touches, so we don't pay deduplication / FINAL costs on the hot
--     read path.
--   - retention: 14 days here vs the full historical window on `pitches`.
--
-- ReplacingMergeTree(ingested_at) on the natural pitch key — same key as
-- `pitches`. Re-inserts from polling overwrite the older row (the MLB feed
-- sometimes corrects pitch_type / velocity after the initial event).

CREATE TABLE IF NOT EXISTS pitches_live (
    game_id           UInt64,
    at_bat_index      UInt16,
    pitch_number      UInt8,
    game_date         Date,
    ingested_at       DateTime DEFAULT now(),

    pitcher_id        UInt32,
    batter_id         UInt32,
    description       LowCardinality(String),
    pitch_type        LowCardinality(String) DEFAULT '',
    release_speed_mph Nullable(Float32),
    plate_x_in        Nullable(Float32),
    plate_z_in        Nullable(Float32),

    balls             UInt8,
    strikes           UInt8,
    outs              UInt8,
    inning            UInt8,
    home_score        UInt8,
    away_score        UInt8,

    home_team         LowCardinality(String),
    away_team         LowCardinality(String)
)
ENGINE = ReplacingMergeTree(ingested_at)
PARTITION BY toYYYYMM(game_date)
ORDER BY (game_id, at_bat_index, pitch_number)
TTL toDate(ingested_at) + INTERVAL 14 DAY;
