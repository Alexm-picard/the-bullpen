-- V016 — Phase 2c.4 (weather upgrade)
-- Per-game observed weather, the "post-game observed pull" half of decision [88].
-- One row per game (game_id == Statcast game_pk == MLB Stats API gamePk, the same
-- key transform_raw_to_pitches emits as pitches.game_id). The retrodiction pipeline
-- joins this on game_id and retrodicts each BIP with its actual game-time wind +
-- temperature instead of the per-park seasonal prevailing wind baked into
-- infra/park_geometry/<park>.json, which scrambled the 2c.7 cross-park HR ranking
-- (a constant out-blowing wind made Oakland look like a launching pad — see the
-- weather-backfill runbook).
--
-- Wind is stored field-relative exactly as the MLB feed reports it
-- (wind_dir_label, e.g. "Out To CF" / "In From LF" / "L To R" / "Calm"). The
-- label -> stadium-frame unit-vector mapping lives once in Python
-- (battedball/retrodict/_atmospheres.parse_wind_label) so it is tested in one
-- place rather than duplicated in SQL. Humidity is not in the feed; the retrodiction
-- path falls back to the target park's seasonal humidity (small density effect).
--
-- Idempotency: ReplacingMergeTree on ingested_at; the backfill is resumable and a
-- re-pull of the same gamePk de-dupes on the natural key (game_id) at merge time.

CREATE TABLE IF NOT EXISTS weather_observed (
    game_id         UInt64,
    game_date       Date,
    park_id         LowCardinality(String),
    condition       LowCardinality(String),       -- "Clear", "Partly Cloudy", "Dome", "Roof Closed", ...
    temp_f          Nullable(Int16),              -- as reported by the feed (degrees F); NULL when absent
    wind_speed_mph  Nullable(UInt16),             -- NULL when absent; 0 for Calm / Indoors
    wind_dir_label  LowCardinality(String),       -- normalized: "Out To CF" | "In From LF" | "L To R" | "Calm" | "Indoors" | ...
    is_indoor       UInt8,                        -- 1 = roof closed / dome -> wind forced to 0 downstream
    source          LowCardinality(String) DEFAULT 'mlb_statsapi',
    ingested_at     DateTime DEFAULT now()
)
ENGINE = ReplacingMergeTree(ingested_at)
PARTITION BY toYYYYMM(game_date)
ORDER BY (game_id)
SETTINGS index_granularity = 8192;
