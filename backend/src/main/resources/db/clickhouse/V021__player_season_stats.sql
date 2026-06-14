-- V021 - season-level player quality stats (Phase 2b of the matchup feature). One row per
-- (player_id, season, stat_group): pitchers carry ERA, hitters carry a COMPUTED wOBA. The MLB
-- Stats API returns the hitting COMPONENTS but not wOBA, so wOBA is computed at ingest from
-- uBB/HBP/1B/2B/3B/HR over PA with fixed modern linear weights (a documented approximation -
-- exact FanGraphs year-constants aren't needed for the relative duel ranking, only consistency).
-- Refreshed by the ~3:45 ET morning job. Feeds the matchup classification: ERA (pitcher strength,
-- lower = stronger) and wOBA (hitter strength, higher = stronger).
--
-- ReplacingMergeTree(updated_at) keyed on (player_id, season, stat_group): each refresh supersedes
-- the prior row; an argMax / FINAL read takes the latest.
CREATE TABLE IF NOT EXISTS player_season_stats (
    player_id   UInt32,
    season      UInt16,
    stat_group  LowCardinality(String),   -- 'pitching' | 'hitting'
    era         Nullable(Float32),         -- pitchers
    woba        Nullable(Float32),         -- hitters (computed)
    sample      Nullable(UInt32),          -- PA (hitters) or batters faced (pitchers): qualification
    updated_at  DateTime DEFAULT now()
)
ENGINE = ReplacingMergeTree(updated_at)
PARTITION BY season
ORDER BY (player_id, season, stat_group);
