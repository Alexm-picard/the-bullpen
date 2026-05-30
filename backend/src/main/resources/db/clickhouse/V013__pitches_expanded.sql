-- V013 — Expanded columns on the cleaned pitches layer.
-- Propagated from raw_statcast V012 via transform_raw_to_pitches.
-- ALTER ADD COLUMN on ReplacingMergeTree is metadata-only.

-- Catcher & matchup
ALTER TABLE pitches
    ADD COLUMN IF NOT EXISTS catcher_id             Nullable(UInt32),
    ADD COLUMN IF NOT EXISTS times_through_order    Nullable(UInt8),
    ADD COLUMN IF NOT EXISTS at_bat_number_in_game  Nullable(UInt16),
    ADD COLUMN IF NOT EXISTS times_faced_today      Nullable(UInt8);

-- Score & leverage
ALTER TABLE pitches
    ADD COLUMN IF NOT EXISTS score_diff_live        Nullable(Int16),
    ADD COLUMN IF NOT EXISTS wpa_delta              Nullable(Float32),
    ADD COLUMN IF NOT EXISTS win_expectancy         Nullable(Float32);

-- Biomechanics
ALTER TABLE pitches
    ADD COLUMN IF NOT EXISTS effective_speed_mph    Nullable(Float32),
    ADD COLUMN IF NOT EXISTS release_extension_ft   Nullable(Float32),
    ADD COLUMN IF NOT EXISTS arm_angle_deg          Nullable(Float32);

-- Defensive alignment
ALTER TABLE pitches
    ADD COLUMN IF NOT EXISTS if_alignment           LowCardinality(String) DEFAULT '',
    ADD COLUMN IF NOT EXISTS of_alignment           LowCardinality(String) DEFAULT '';

-- Pitch zone
ALTER TABLE pitches
    ADD COLUMN IF NOT EXISTS pitch_zone             Nullable(UInt8);
