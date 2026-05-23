-- V005 — Phase 2a.1
-- Feature store for the pre-pitch model. One row per labeled pitch per fold;
-- the fold partition lets us compute + serve per-fold features without
-- recomputing the whole table when rolling-origin CV advances.
--
-- Tier 1: cheap context (count, base state, score diff, handedness, park, dow).
-- Tier 2: identity (pitcher/batter target-encoded with strict pre-game cutoff).
-- Tier 3 (rolling form) lands in V006 (Phase 2a.2). Tier 4 (post-pitch) in V008
-- (Phase 2b.1).
--
-- `as_of_date` is the cutoff date used when computing Tier 2 TEs for this row
-- (always < game_date so leakage tests in CI can prove non-future contamination).
-- ReplacingMergeTree(ingested_at) makes per-fold rebuilds idempotent.

CREATE TABLE IF NOT EXISTS features (
    game_id              UInt64,
    at_bat_index         UInt16,
    pitch_number         UInt8,
    game_date            Date,
    as_of_date           Date,
    fold                 UInt8,

    pitcher_id           UInt32,
    batter_id            UInt32,

    -- Tier 1: cheap context
    count_balls          UInt8,
    count_strikes        UInt8,
    outs                 UInt8,
    inning               UInt8,
    base_state           UInt8,
    score_diff           Int16,
    pitcher_throws       FixedString(1),
    batter_stand         FixedString(1),
    park_id              LowCardinality(String),
    dow                  UInt8,

    -- Tier 2: identity target-encoded (one column per class label)
    pitcher_te_ball      Float32,
    pitcher_te_called_strike Float32,
    pitcher_te_swinging_strike Float32,
    pitcher_te_foul      Float32,
    pitcher_te_in_play   Float32,
    batter_te_ball       Float32,
    batter_te_called_strike Float32,
    batter_te_swinging_strike Float32,
    batter_te_foul       Float32,
    batter_te_in_play    Float32,

    -- 5-class label, set NULL for pitches with `description = 'unknown'`
    label                Enum8(
        'ball' = 0,
        'called_strike' = 1,
        'swinging_strike' = 2,
        'foul' = 3,
        'in_play' = 4
    ),

    ingested_at          DateTime DEFAULT now()
)
ENGINE = ReplacingMergeTree(ingested_at)
PARTITION BY (toYYYYMM(game_date), fold)
ORDER BY (fold, game_date, game_id, at_bat_index, pitch_number)
SETTINGS index_granularity = 8192;
