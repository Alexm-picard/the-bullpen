-- V002 — Phase 1.1
-- Landing table for the historical Statcast pull (pybaseball).
-- Stays untouched after ingest; cleaning into `pitches` happens in V003 (Phase 1.2).
--
-- Ordering: (game_date, game_pk, at_bat_index, pitch_number) is the natural
-- replay order and matches the dedup key used downstream in `pitches`.
-- Partitioning by month keeps the idempotent "drop partition + reload month"
-- pattern cheap (see ingest/statcast_pull.py docstring).

CREATE TABLE IF NOT EXISTS raw_statcast (
    game_pk           UInt64,
    game_date         Date,
    game_type         LowCardinality(String),
    home_team         LowCardinality(String),
    away_team         LowCardinality(String),
    at_bat_index      UInt16,
    pitch_number      UInt8,
    pitcher           UInt32,
    batter            UInt32,
    stand             LowCardinality(String),
    p_throws          LowCardinality(String),
    balls             Nullable(UInt8),
    strikes           Nullable(UInt8),
    inning            Nullable(UInt8),
    inning_topbot     LowCardinality(String),
    outs_when_up      Nullable(UInt8),
    on_1b             Nullable(UInt32),
    on_2b             Nullable(UInt32),
    on_3b             Nullable(UInt32),
    pitch_type        LowCardinality(String),
    release_speed     Nullable(Float32),
    release_pos_x     Nullable(Float32),
    release_pos_z     Nullable(Float32),
    plate_x           Nullable(Float32),
    plate_z           Nullable(Float32),
    sz_top            Nullable(Float32),
    sz_bot            Nullable(Float32),
    description       LowCardinality(String),
    events            LowCardinality(String),
    type              LowCardinality(String),
    bb_type           LowCardinality(String),
    launch_speed      Nullable(Float32),
    launch_angle      Nullable(Float32),
    hit_distance_sc   Nullable(Float32),
    hc_x              Nullable(Float32),
    hc_y              Nullable(Float32),
    ingested_at       DateTime DEFAULT now()
)
ENGINE = MergeTree()
PARTITION BY toYYYYMM(game_date)
ORDER BY (game_date, game_pk, at_bat_index, pitch_number)
SETTINGS index_granularity = 8192;
