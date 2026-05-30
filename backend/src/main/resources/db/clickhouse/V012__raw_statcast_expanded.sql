-- V012 — Expanded Statcast columns for advanced pitch prediction.
-- Adds catcher, fatigue, leverage, biomechanics, defensive alignment,
-- and batter zone columns to raw_statcast. All available from pybaseball
-- with high coverage (95-100% non-null for 2024+).
--
-- ALTER ADD COLUMN on MergeTree is metadata-only; idempotent via IF NOT EXISTS.

-- Catcher & matchup context
ALTER TABLE raw_statcast
    ADD COLUMN IF NOT EXISTS fielder_2              Nullable(UInt32),
    ADD COLUMN IF NOT EXISTS n_thruorder_pitcher    Nullable(UInt8),
    ADD COLUMN IF NOT EXISTS at_bat_number          Nullable(UInt16),
    ADD COLUMN IF NOT EXISTS n_priorpa_thisgame     Nullable(UInt8);

-- Score & leverage
ALTER TABLE raw_statcast
    ADD COLUMN IF NOT EXISTS home_score             Nullable(Int16),
    ADD COLUMN IF NOT EXISTS away_score             Nullable(Int16),
    ADD COLUMN IF NOT EXISTS bat_score_diff         Nullable(Int16),
    ADD COLUMN IF NOT EXISTS delta_home_win_exp     Nullable(Float32),
    ADD COLUMN IF NOT EXISTS delta_run_exp          Nullable(Float32),
    ADD COLUMN IF NOT EXISTS home_win_exp           Nullable(Float32);

-- Biomechanics & effective velocity
ALTER TABLE raw_statcast
    ADD COLUMN IF NOT EXISTS effective_speed        Nullable(Float32),
    ADD COLUMN IF NOT EXISTS release_extension      Nullable(Float32),
    ADD COLUMN IF NOT EXISTS arm_angle              Nullable(Float32);

-- Defensive alignment
ALTER TABLE raw_statcast
    ADD COLUMN IF NOT EXISTS if_fielding_alignment  LowCardinality(String) DEFAULT '',
    ADD COLUMN IF NOT EXISTS of_fielding_alignment  LowCardinality(String) DEFAULT '';

-- Pitch zone & strike zone
ALTER TABLE raw_statcast
    ADD COLUMN IF NOT EXISTS zone                   Nullable(UInt8);
