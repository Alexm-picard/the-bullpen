-- V031 - the LIVE current-play matchup on the game-status row (dynamic current batter).
--
-- The game page derives "who is batting" from the last THROWN pitch, so at every at-bat rollover
-- it shows the previous batter until the new batter's first pitch lands - one plate appearance
-- behind at exactly the moment a viewer looks - and between half-innings it shows a batter from
-- the team now in the field. MlbFeedParser already parses currentPlay.matchup per poll into
-- LiveNextPitch (batter, pitcher, batSide, pitchHand, atBatIndex); GUMBO updates it the moment the
-- next batter steps in. The poller consumed it only for LivePitchPredictor - it never reached the
-- api read path, because live_game_status carried nothing but (game_id, game_date, status).
-- These five columns are that bridge, written by the same per-tick status adopt.
--
-- NULLABILITY, following V028's split on the sibling table verbatim:
--   * the two ids and at_bat_index are Nullable, NOT DEFAULT 0 - the same reasoning V028 applied
--     to base_state. 0 is a REAL-LOOKING value here (at-bat 0 is the first at-bat of a game, and
--     MlbFeedParser's asLong() yields 0L for a missing matchup id), so DEFAULT 0 would falsely
--     assert "at-bat 0, batter #0" on both pre-migration rows and every early-GUMBO tick. NULL
--     reads back honestly and the api surfaces the whole matchup as absent.
--   * bat_side / pitch_hand mirror V028's pitch_hand / bat_side idiom exactly:
--     LowCardinality(String) DEFAULT '', where '' marks a pre-migration or unpopulated row and the
--     row mapper leaves it as ''. bat_side may be 'S' (switch hitter) - resolved to L|R downstream
--     against the CURRENT pitcher, which is the thing a last-pitch lookup cannot do.
--
-- WRITE CADENCE: live_game_status is a ReplacingMergeTree(updated_at) ORDER BY (game_id), so each
-- upsert supersedes the prior row and the argMax(col, updated_at) reads take the latest. The
-- matchup changes ~once per at-bat while the status changes a handful of times per game, so the
-- poller's write condition widens from status-transition-only to "status transitioned OR the
-- matchup changed" - roughly 80 rows per game rather than one per 5s tick.
--
-- Additive ALTER; no backfill DML - rows written before this migration read NULL/'' and the api
-- reports the matchup as absent, which is the truth about them.
--
-- SNAPSHOT PRECONDITION (CLAUDE.md hard rule): any DROP/ALTER against prod ClickHouse must be
-- preceded by a snapshot. This is additive ALTER ADD COLUMN only, but the precondition still
-- applies before it first runs against the prod box. The bullpen least-priv grant (decision [171])
-- includes ALTER ADD COLUMN for exactly this boot-time migration path (verified in the users.d
-- grants block, not assumed).
--
-- ClickHouseMigrationRunner keys applied migrations by filename and checksums them; this is a NEW
-- V*.sql file (never an edit to an applied one).
ALTER TABLE live_game_status ADD COLUMN IF NOT EXISTS current_batter_id Nullable(UInt32) AFTER status;
ALTER TABLE live_game_status ADD COLUMN IF NOT EXISTS current_pitcher_id Nullable(UInt32) AFTER current_batter_id;
ALTER TABLE live_game_status ADD COLUMN IF NOT EXISTS current_bat_side LowCardinality(String) DEFAULT '' AFTER current_pitcher_id;
ALTER TABLE live_game_status ADD COLUMN IF NOT EXISTS current_pitch_hand LowCardinality(String) DEFAULT '' AFTER current_bat_side;
ALTER TABLE live_game_status ADD COLUMN IF NOT EXISTS current_at_bat_index Nullable(Int32) AFTER current_pitch_hand;
