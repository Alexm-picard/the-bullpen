-- V009 — Phase 2b.1
-- Tier 4 (post-pitch) columns on the cleaned pitches layer. Movement,
-- spin, and release-position pass through from raw_statcast (V008
-- added them there); the transform in transform_raw_to_pitches.sql
-- propagates them.
--
-- Naming follows the existing _in / _mph / _deg / _rpm suffix convention.
-- ALTER ADD COLUMN on ReplacingMergeTree is metadata-only.

ALTER TABLE pitches
    ADD COLUMN IF NOT EXISTS pfx_x_in         Nullable(Float32),
    ADD COLUMN IF NOT EXISTS pfx_z_in         Nullable(Float32),
    ADD COLUMN IF NOT EXISTS spin_rate_rpm    Nullable(Float32),
    ADD COLUMN IF NOT EXISTS spin_axis_deg    Nullable(Float32),
    ADD COLUMN IF NOT EXISTS release_pos_x_in Nullable(Float32),
    ADD COLUMN IF NOT EXISTS release_pos_z_in Nullable(Float32);
