-- V022 - the computed matchup per game (Phase 2c of the matchup feature). One row per game per
-- day, holding the classification the home Featured panel + Tonight's board read: the LEAN
-- (pitching | hitters | mixed), the two FEATURED people (id/name/role on each side), a
-- BATTLE_SCORE for ranking (the "best battle of the bunch" = highest score -> Featured panel), and
-- the STAGE. The ~3:45 ET morning job writes stage='default' = pitcher vs pitcher (no lineup yet);
-- the ~1-2h-before-first-pitch job re-classifies to stage='lineup' once the lineup's wOBA is known
-- (Phase 3), which can flip the lean to hitters/mixed and change the featured people.
--
-- ReplacingMergeTree(updated_at) keyed on game_id: each re-classification supersedes the prior row.
CREATE TABLE IF NOT EXISTS game_matchups (
    game_id           UInt64,
    game_date         Date,
    lean              LowCardinality(String),               -- pitching | hitters | mixed
    home_player_id    UInt32 DEFAULT 0,
    home_player_name  String DEFAULT '',
    home_role         LowCardinality(String) DEFAULT '',     -- pitcher | hitter
    away_player_id    UInt32 DEFAULT 0,
    away_player_name  String DEFAULT '',
    away_role         LowCardinality(String) DEFAULT '',
    battle_score      Float32 DEFAULT 0,
    stage             LowCardinality(String) DEFAULT 'default', -- default | lineup
    updated_at        DateTime DEFAULT now()
)
ENGINE = ReplacingMergeTree(updated_at)
PARTITION BY toYYYYMM(game_date)
ORDER BY (game_id);
