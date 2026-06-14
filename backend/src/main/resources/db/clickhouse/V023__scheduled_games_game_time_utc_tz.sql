-- V023 - pin scheduled_games.game_time_utc to UTC.
--
-- The column was Nullable(DateTime) with NO timezone. The write path formats the scheduled
-- start as a UTC wall-clock string ('yyyy-MM-dd HH:mm:ss'), but a tz-less DateTime is parsed
-- AND rendered in the SERVER timezone - the box runs America/New_York - so a 7:20 PM ET
-- (23:20Z) game was stored as 03:20Z (next day) and surfaced on the site as 11:20 PM ET, a
-- clean +4h (EDT offset) skew on every game. Confirmed against the MLB schedule: our stored
-- times were exactly +4h ahead of the authoritative gameDate.
--
-- Pinning the column to UTC makes ClickHouse parse the naive UTC wall-clock as UTC and read it
-- back as UTC, matching the column's name + intent. DateTime always stores a UInt32 epoch
-- regardless of tz, so adding the 'UTC' annotation is a metadata-only change (no data rewrite).
-- Existing rows keep their shifted epochs until the next schedule upsert supersedes them via
-- ReplacingMergeTree - the poller re-upserts the full slate every ~15 min, so the live slate
-- self-heals within one refresh of deploy.
ALTER TABLE scheduled_games
    MODIFY COLUMN game_time_utc Nullable(DateTime('UTC'));
