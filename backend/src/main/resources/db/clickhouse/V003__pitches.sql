-- V003 — Phase 1.2
-- Cleaned pitches layer. Dedup via ReplacingMergeTree on the natural pitch
-- key (game_id, at_bat_index, pitch_number). Cleaning runs out-of-band
-- against raw_statcast (no live writes here — that's pitches_live in 4d).
--
-- description is an Enum8 because the set is tiny and frozen; the source
-- has ~12 raw descriptions that collapse to 6 categories (see
-- transform_raw_to_pitches.sql multiIf).
--
-- park_id == home_team in v1; refines into a real park dimension when
-- ball-flight physics needs ballpark geometry (Phase 2c).

CREATE TABLE IF NOT EXISTS pitches (
    game_id           UInt64,
    game_date         Date,
    at_bat_index      UInt16,
    pitch_number      UInt8,
    pitcher_id        UInt32,
    batter_id         UInt32,
    pitch_type        LowCardinality(String),
    description       Enum8(
        'ball' = 0,
        'called_strike' = 1,
        'swinging_strike' = 2,
        'foul' = 3,
        'in_play' = 4,
        'hit_by_pitch' = 5,
        'unknown' = 99
    ),
    events            LowCardinality(String),
    release_speed_mph Nullable(Float32),
    plate_x_in        Nullable(Float32),
    plate_z_in        Nullable(Float32),
    launch_speed_mph  Nullable(Float32),
    launch_angle_deg  Nullable(Float32),
    hc_x              Nullable(Float32),
    hc_y              Nullable(Float32),
    hit_distance_ft   Nullable(Float32),
    bb_type           LowCardinality(String),
    home_team         LowCardinality(String),
    away_team         LowCardinality(String),
    park_id           LowCardinality(String),
    stand             FixedString(1),
    p_throws          FixedString(1),
    balls             UInt8,
    strikes           UInt8,
    outs              UInt8,
    inning            UInt8,
    base_state        UInt8,
    ingested_at       DateTime DEFAULT now()
)
ENGINE = ReplacingMergeTree(ingested_at)
PARTITION BY toYYYYMM(game_date)
ORDER BY (game_date, game_id, at_bat_index, pitch_number)
SETTINGS index_granularity = 8192;
