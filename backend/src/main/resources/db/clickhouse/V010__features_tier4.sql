-- V010 — Phase 2b.1
-- Tier 4 (post-pitch) columns on the features store. Populated by the
-- tier-4 builder (bullpen_training.features.tier_4_postpitch) from the
-- pitches table.
--
-- These columns are read by `pitch_outcome_post` ONLY. The pre-pitch
-- pipeline (canonical /contracts/feature_pipeline.json) deliberately does
-- not list them — the leakage tests in CI enforce that any model whose
-- feature_pipeline.json lists a Tier 4 column is named `*_post` and never
-- `*_pre`. Catastrophic leakage if violated.
--
-- ALTER ADD COLUMN on ReplacingMergeTree is metadata-only; idempotent
-- via IF NOT EXISTS.

ALTER TABLE features
    ADD COLUMN IF NOT EXISTS pitch_type       LowCardinality(String),
    ADD COLUMN IF NOT EXISTS release_speed_mph Nullable(Float32),
    ADD COLUMN IF NOT EXISTS plate_x_in       Nullable(Float32),
    ADD COLUMN IF NOT EXISTS plate_z_in       Nullable(Float32),
    ADD COLUMN IF NOT EXISTS pfx_x_in         Nullable(Float32),
    ADD COLUMN IF NOT EXISTS pfx_z_in         Nullable(Float32),
    ADD COLUMN IF NOT EXISTS spin_rate_rpm    Nullable(Float32),
    ADD COLUMN IF NOT EXISTS spin_axis_deg    Nullable(Float32),
    ADD COLUMN IF NOT EXISTS release_pos_x_in Nullable(Float32),
    ADD COLUMN IF NOT EXISTS release_pos_z_in Nullable(Float32);
