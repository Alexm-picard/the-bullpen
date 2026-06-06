-- V017 - issue #1 (Phase 4d) live-game prediction truth-join key
-- Add the (game_id, at_bat_index, pitch_number) natural key to prediction_log so a live-game
-- prediction (the LivePollingService predict-on-live-pitch path, step 4) can LEFT JOIN to its
-- pitch in pitches_live (step 5) and render on the game page.
--
-- Nullable + additive: HTTP-path and shadow predictions write NULL (they correspond to no live
-- pitch), so existing prediction logging is unaffected and the join simply skips them. Decision
-- [143] (predict-the-next-pitch on the live count) keys each live prediction to the pitch it
-- predicts; orphan predictions (a predicted pitch that never lands - intentional walk, pitch-clock
-- auto ball/strike, mid-PA suspension) carry these columns but find no pitches_live match and are
-- excluded from calibration.
--
-- Existing partitions are NOT backfilled: rows written before this column keep NULL, which is
-- correct (they map to no live pitch). Additive ALTER (not DROP/TRUNCATE), so the
-- block-destructive-ch hook does not apply; on prod it still lands under the standard
-- snapshot-before-deploy discipline.
--
-- Single multi-action ALTER (one statement) so it is safe regardless of the runner's
-- statement-splitting; IF NOT EXISTS keeps it idempotent under the V*.sql re-run model.
ALTER TABLE prediction_log
    ADD COLUMN IF NOT EXISTS game_id Nullable(UInt64) AFTER correlation_id,
    ADD COLUMN IF NOT EXISTS at_bat_index Nullable(UInt16) AFTER game_id,
    ADD COLUMN IF NOT EXISTS pitch_number Nullable(UInt8) AFTER at_bat_index;
