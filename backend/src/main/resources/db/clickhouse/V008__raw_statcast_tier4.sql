-- V008 — Phase 2b.1
-- Tier 4 (post-pitch) columns surface to raw_statcast. The original Phase 1.1
-- pybaseball pull captured release_pos + plate location + release_speed but
-- not the actual movement vectors or spin metrics. Adding them here so the
-- next pybaseball pull (for 2024+ only, per user choice — pre-2024 stays
-- sparse and LightGBM handles the NULLs natively) lands them.
--
-- Column names match pybaseball's canonical schema 1:1 (no SOURCE_ALIASES
-- needed): pfx_x, pfx_z, release_spin_rate, spin_axis.
--
-- ALTER ADD COLUMN on a MergeTree is metadata-only (no rewrite); idempotent
-- via IF NOT EXISTS.

ALTER TABLE raw_statcast
    ADD COLUMN IF NOT EXISTS pfx_x             Nullable(Float32),
    ADD COLUMN IF NOT EXISTS pfx_z             Nullable(Float32),
    ADD COLUMN IF NOT EXISTS release_spin_rate Nullable(Float32),
    ADD COLUMN IF NOT EXISTS spin_axis         Nullable(Float32);
