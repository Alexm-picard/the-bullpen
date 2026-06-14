-- V020 - probable pitchers on the day's slate (Phase 2a of the matchup feature). Additive columns
-- on V019 scheduled_games, populated from the schedule's probablePitcher hydrate. id 0 / name ''
-- mean "not yet announced" (probables usually post the night before, but can be TBD or a late
-- scratch - the ~1-2h-before-first-pitch refresh re-confirms them). Feeds the matchup
-- classification's pitcher-strength (ERA) side.
ALTER TABLE scheduled_games
    ADD COLUMN IF NOT EXISTS home_pitcher_id   UInt32 DEFAULT 0,
    ADD COLUMN IF NOT EXISTS home_pitcher_name String DEFAULT '',
    ADD COLUMN IF NOT EXISTS away_pitcher_id   UInt32 DEFAULT 0,
    ADD COLUMN IF NOT EXISTS away_pitcher_name String DEFAULT '';
