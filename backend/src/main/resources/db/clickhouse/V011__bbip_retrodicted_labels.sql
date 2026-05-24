-- V011 — Phase 2c.4
-- Retrodicted batted-ball outcome labels: one row per (BIP, park) pair.
-- 30 rows per BIP (every BIP simulated at every park's default atmosphere).
-- The MLP in 2c.5 trains on this table as the label source.
--
-- bbip identity matches pitches' natural key (game_id, at_bat_index,
-- pitch_number) plus game_date for partitioning. observed_outcome is
-- populated ONLY on the row where park_id == the BIP's home park —
-- the home-park row is the supervised label, the other 29 are
-- counterfactuals (what would have happened at every other park).
--
-- Idempotency: ReplacingMergeTree on ingested_at. The pipeline writes
-- the full 30-row block per BIP atomically and re-runs are de-duped on
-- the natural key by the engine merge.

CREATE TABLE IF NOT EXISTS bbip_retrodicted_labels (
    game_date         Date,
    game_id           UInt64,
    at_bat_index      UInt16,
    pitch_number      UInt8,
    park_id           LowCardinality(String),
    is_home_park      UInt8,                    -- 1 where park_id == BIP's home park
    prob_out          Float32,
    prob_1b           Float32,
    prob_2b           Float32,
    prob_3b           Float32,
    prob_hr           Float32,
    observed_outcome  Nullable(Enum8(
        'out' = 0,
        '1b'  = 1,
        '2b'  = 2,
        '3b'  = 3,
        'hr'  = 4
    )),                                          -- non-NULL only on the home-park row
    n_mc              UInt8,                     -- Monte Carlo sample count (for audit)
    ingested_at       DateTime DEFAULT now()
)
ENGINE = ReplacingMergeTree(ingested_at)
PARTITION BY toYYYYMM(game_date)
ORDER BY (park_id, game_date, game_id, at_bat_index, pitch_number)
SETTINGS index_granularity = 8192;
