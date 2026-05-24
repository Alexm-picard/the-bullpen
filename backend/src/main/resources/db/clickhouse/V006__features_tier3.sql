-- V006 — Phase 2a.2
-- Adds Tier 3 (rolling form) columns to `features`. All Nullable so the
-- first ~28 days of each season are explicitly "no warm-up data" rather
-- than silently 0.
--
-- Naming convention: `*_last_28d` means strictly the 28 days immediately
-- preceding `game_date` (exclusive of game_date itself — leakage-safe).
-- `*_std` means season-to-date as of `game_date - 1`.
-- `*_in_game` means count from this same game up to (but not including) the
-- current pitch's at_bat_index + pitch_number.
--
-- Rates are pitch-level (numerator and denominator both counted in
-- pitches): `pitcher_strike_rate_28d` = (called+swinging+foul) / total.
-- For batter rates this matches: `batter_strike_rate_28d` is the fraction
-- of pitches the batter faced that were strikes (foul included).

ALTER TABLE features
    ADD COLUMN IF NOT EXISTS pitcher_pitches_last_28d         Nullable(UInt32),
    ADD COLUMN IF NOT EXISTS pitcher_pitches_in_game          UInt32 DEFAULT 0,
    ADD COLUMN IF NOT EXISTS days_since_last_appearance       Nullable(UInt16),
    ADD COLUMN IF NOT EXISTS pitcher_strike_rate_28d          Nullable(Float32),
    ADD COLUMN IF NOT EXISTS pitcher_swstrike_rate_28d        Nullable(Float32),
    ADD COLUMN IF NOT EXISTS pitcher_inplay_rate_28d          Nullable(Float32),
    ADD COLUMN IF NOT EXISTS pitcher_strike_rate_std          Nullable(Float32),
    ADD COLUMN IF NOT EXISTS batter_strike_rate_28d           Nullable(Float32),
    ADD COLUMN IF NOT EXISTS batter_inplay_rate_28d           Nullable(Float32),
    ADD COLUMN IF NOT EXISTS batter_ball_rate_28d             Nullable(Float32),
    ADD COLUMN IF NOT EXISTS batter_inplay_rate_std           Nullable(Float32);
