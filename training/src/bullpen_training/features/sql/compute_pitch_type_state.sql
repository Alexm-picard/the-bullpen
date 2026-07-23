-- Pitch-TYPE feature store: y7 label + Tier S state + Tier SEQ sequencing for one
-- fold's test window. Bind variables: :test_start, :test_end (inclusive).
-- Companion of compute_pitch_type_arsenal.sql (Tier ARS); the loader joins the two
-- on (game_id, at_bat_index, pitch_number). Phase 1a; spec is
-- docs/research/2026-07-23_pitch_type_architecture_bakeoff.md section 2 (candidate A).
--
-- STREAMING TEMPORAL CUTOFF (rule 10): every SEQ feature is computed over a window
-- frame that EXCLUDES the current pitch (lagInFrame looks strictly backward; the
-- pitches_into_outing count uses ... AND 1 PRECEDING). Tier S columns are pre-pitch
-- state read straight from the row (no window). y7 is the target, never a feature.
--
-- SEQ is outing-scoped (game_id, pitcher_id). A game never spans a calendar year (or
-- day), so per-year loader chunking returns rows IDENTICAL to a full-window scan - no
-- cross-window lookback is needed here (unlike the ARS career window). Hence the base
-- scan is just [test_start, test_end], no warm-up floor.
--
-- Labeled set (mirrors select_labeled_pitches.sql's description filter): a pitch is in
-- the set iff pitch_type is a real thrown type. '' (the LowCardinality default /
-- missing), PO (pitchout) and IN (intentional ball) are excluded. Every remaining
-- fine-grained code folds into y7 (unmatched codes such as FS/FO/KN/EP/SC/GY -> OFF,
-- report section 3). SEQ shifts over this labeled base, so a skipped PO/IN is never a
-- "previous pitch" and pitches_into_outing counts labeled pitches only.

WITH
    base AS (
        SELECT
            game_id, game_date, at_bat_index, pitch_number, pitcher_id, batter_id,
            balls, strikes, outs, inning, base_state,
            stand, p_throws, park_id,
            times_through_order, at_bat_number_in_game, times_faced_today,
            multiIf(
                pitch_type = 'FF', 'FF',
                pitch_type = 'SI', 'SI',
                pitch_type = 'FC', 'FC',
                pitch_type IN ('SL', 'ST', 'SV'), 'SL',
                pitch_type IN ('CU', 'KC', 'CS'), 'CU',
                pitch_type = 'CH', 'CH',
                'OFF'
            ) AS y7
        -- FINAL: pitches is a ReplacingMergeTree; without it a re-ingested (corrected)
        -- pitch double-counts the SEQ windows below (DEF-H3, same as compute_tier3.sql).
        FROM pitches FINAL
        WHERE game_date >= toDate(:test_start)
          AND game_date <= toDate(:test_end)
          AND pitch_type NOT IN ('', 'PO', 'IN')
    ),
    encoded AS (
        SELECT
            *,
            -- FIXED fold-independent SEQ vocab: FF=0,SI=1,FC=2,SL=3,CU=4,CH=5,OFF=6
            -- (identical to the label_pitch_type Enum8 codes in V029).
            multiIf(
                y7 = 'FF', 0, y7 = 'SI', 1, y7 = 'FC', 2, y7 = 'SL', 3,
                y7 = 'CU', 4, y7 = 'CH', 5, 6
            ) AS y7_int
        FROM base
    )
SELECT
    game_id, at_bat_index, pitch_number, game_date, pitcher_id, batter_id,
    y7 AS label_pitch_type,
    balls, strikes, outs, inning, base_state,
    stand, p_throws, park_id,
    times_through_order, at_bat_number_in_game, times_faced_today,
    -- Tier SEQ. lagInFrame(x, n, -1) yields the y7 int n pitches back within the
    -- outing, or the -1 outing-start sentinel when fewer than n prior pitches exist.
    toInt8(lagInFrame(y7_int, 1, -1) OVER w_seq)   AS prev1_pt_i,
    toInt8(lagInFrame(y7_int, 2, -1) OVER w_seq)   AS prev2_pt_i,
    -- prev1_missing / pitches_into_outing count the pitcher's PRIOR pitches this
    -- outing (frame ends at 1 PRECEDING, current pitch excluded); 0 at outing start.
    toUInt8(count() OVER w_seq_prior = 0)          AS prev1_missing,
    toUInt16(count() OVER w_seq_prior)             AS pitches_into_outing
FROM encoded
WINDOW
    w_seq AS (
        PARTITION BY game_id, pitcher_id ORDER BY at_bat_index, pitch_number
        ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW
    ),
    w_seq_prior AS (
        PARTITION BY game_id, pitcher_id ORDER BY at_bat_index, pitch_number
        ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING
    )
ORDER BY game_date, game_id, at_bat_index, pitch_number
SETTINGS max_memory_usage = 0
