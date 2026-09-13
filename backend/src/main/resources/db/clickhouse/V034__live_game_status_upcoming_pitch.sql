-- V034 - upcoming-pitch context and worker-computed predictions on the game-status row.
--
-- The game page currently derives the pre-pitch request client-side and POSTs for its own inference
-- every 12s. Decision [194] moves the served answer to the worker: the poller already runs both
-- heads per pitch, so the computed predictions land on this row and a lightweight GET /v1/games/
-- {id}/live serves them at 2s polling, removing the frontend inference round-trip entirely.
--
-- NULLABILITY: follows the V031 idiom exactly. All columns are NON-Nullable with 0/'' sentinels.
-- Group-level presence predicate: upcoming_pitch_number > 0 AND pre_prediction != ''. The api
-- checks that predicate on read; a row that fails it reports "no prediction available", which is
-- the truth about it (pre-game, between plays, game final, or the worker has not run yet).
--
-- upcoming_base_state uses the same 1/2/4 bitmask as pitches.base_state (V002), so 0 genuinely
-- means bases-empty - but that is indistinguishable from the sentinel only at the GROUP level,
-- where the pitch_number/prediction presence predicate already gates it. Within a populated row
-- 0 means what it means.
--
-- predicted_at is DateTime64(3,'UTC') - millisecond precision in explicit UTC, matching the
-- prediction_log.predicted_at convention. The sentinel is the epoch (1970-01-01 00:00:00.000).
--
-- model versions are LowCardinality(String) DEFAULT '' - the registry version label, not the
-- integer FK. The string is what the api returns and what the frontend renders; joining back to
-- the registry for per-version analysis goes through prediction_log (the worker still logs there).
--
-- Additive ALTER; no backfill DML. Pre-existing rows read the sentinels and the api reports no
-- prediction, which is true.
ALTER TABLE live_game_status ADD COLUMN IF NOT EXISTS upcoming_at_bat_index UInt16 DEFAULT 0 AFTER current_at_bat_index;
ALTER TABLE live_game_status ADD COLUMN IF NOT EXISTS upcoming_pitch_number UInt8 DEFAULT 0 AFTER upcoming_at_bat_index;
ALTER TABLE live_game_status ADD COLUMN IF NOT EXISTS upcoming_balls UInt8 DEFAULT 0 AFTER upcoming_pitch_number;
ALTER TABLE live_game_status ADD COLUMN IF NOT EXISTS upcoming_strikes UInt8 DEFAULT 0 AFTER upcoming_balls;
ALTER TABLE live_game_status ADD COLUMN IF NOT EXISTS upcoming_outs UInt8 DEFAULT 0 AFTER upcoming_strikes;
ALTER TABLE live_game_status ADD COLUMN IF NOT EXISTS upcoming_base_state UInt8 DEFAULT 0 AFTER upcoming_outs;
ALTER TABLE live_game_status ADD COLUMN IF NOT EXISTS pre_prediction String DEFAULT '' AFTER upcoming_base_state;
ALTER TABLE live_game_status ADD COLUMN IF NOT EXISTS pitch_type_prediction String DEFAULT '' AFTER pre_prediction;
ALTER TABLE live_game_status ADD COLUMN IF NOT EXISTS pre_model_version LowCardinality(String) DEFAULT '' AFTER pitch_type_prediction;
ALTER TABLE live_game_status ADD COLUMN IF NOT EXISTS pitch_type_model_version LowCardinality(String) DEFAULT '' AFTER pre_model_version;
ALTER TABLE live_game_status ADD COLUMN IF NOT EXISTS predicted_at DateTime64(3, 'UTC') DEFAULT toDateTime64('1970-01-01 00:00:00.000', 3, 'UTC') AFTER pitch_type_model_version;
