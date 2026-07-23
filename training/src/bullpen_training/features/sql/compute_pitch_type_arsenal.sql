-- Pitch-TYPE arsenal features (Tier ARS): per-pitcher CAREER-EXPANDING frequency of
-- each y7 class, strictly before the current pitch, for one fold's test window.
-- Bind variables: :corpus_start (career floor), :test_start, :test_end (inclusive).
-- Companion of compute_pitch_type_state.sql; the loader joins on
-- (game_id, at_bat_index, pitch_number). Spec: report section 2, Tier ARS.
--
-- STREAMING TEMPORAL CUTOFF (rule 10): the window frame is
-- ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING, which EXCLUDES the current pitch.
-- countIf(y7 = <class>) over that frame IS (cumsum(class) - current-pitch), so
-- ars_<class> = countIf(class) / prior_n is the UNSMOOTHED expanding frequency.
-- NULL (NaN at serve) at the pitcher's first career pitch where prior_n = 0 - correct
-- and expected (~0.17% of rows, report section 2); LightGBM handles NaN natively.
--
-- Unlike compute_tier3.sql's 90-day warm-up floor, this base scan reaches back to
-- :corpus_start (the pitcher's first pitch; the caller passes 2015-01-01 - the training
-- corpus floor) so every test-year row sees its WHOLE career-to-date. Rule 13: the
-- caller never passes a 2026 window, so 2026 never enters the scan. Per-year loader
-- chunking stays EXACT: a row's frame depends only on rows ordered before it, all of
-- which are inside the [corpus_start, :test_end] scan; output is then filtered to
-- [test_start, test_end].
--
-- Same labeled-set filter as compute_pitch_type_state.sql (exclude '' / PO / IN), so
-- prior_n counts prior labeled pitches and the class counts are over y7 classes only.

WITH
    base AS (
        SELECT
            game_id, game_date, at_bat_index, pitch_number, pitcher_id,
            balls, strikes,
            multiIf(
                pitch_type = 'FF', 'FF',
                pitch_type = 'SI', 'SI',
                pitch_type = 'FC', 'FC',
                pitch_type IN ('SL', 'ST', 'SV'), 'SL',
                pitch_type IN ('CU', 'KC', 'CS'), 'CU',
                pitch_type = 'CH', 'CH',
                'OFF'
            ) AS y7
        -- FINAL: dedup the ReplacingMergeTree so a re-ingested pitch is not
        -- double-counted in the expanding arsenal frame (DEF-H3).
        FROM pitches FINAL
        WHERE game_date >= toDate(:corpus_start)
          AND game_date <= toDate(:test_end)
          AND pitch_type NOT IN ('', 'PO', 'IN')
    ),
    windowed AS (
        SELECT
            game_id, at_bat_index, pitch_number, game_date,
            count()             OVER w_ars        AS prior_n,
            countIf(y7 = 'FF')  OVER w_ars        AS n_ff,
            countIf(y7 = 'SI')  OVER w_ars        AS n_si,
            countIf(y7 = 'FC')  OVER w_ars        AS n_fc,
            countIf(y7 = 'SL')  OVER w_ars        AS n_sl,
            countIf(y7 = 'CU')  OVER w_ars        AS n_cu,
            countIf(y7 = 'CH')  OVER w_ars        AS n_ch,
            countIf(y7 = 'OFF') OVER w_ars        AS n_off,
            count()             OVER w_ars_count  AS prior_n_by_count,
            countIf(y7 = 'FF')  OVER w_ars_count  AS n_ff_by_count
        FROM base
        WINDOW
            w_ars AS (
                PARTITION BY pitcher_id
                ORDER BY game_date, game_id, at_bat_index, pitch_number
                ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING
            ),
            w_ars_count AS (
                PARTITION BY pitcher_id, balls, strikes
                ORDER BY game_date, game_id, at_bat_index, pitch_number
                ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING
            )
    )
SELECT
    game_id, at_bat_index, pitch_number,
    if(prior_n = 0, NULL, toFloat32(n_ff)  / prior_n)  AS ars_FF,
    if(prior_n = 0, NULL, toFloat32(n_si)  / prior_n)  AS ars_SI,
    if(prior_n = 0, NULL, toFloat32(n_fc)  / prior_n)  AS ars_FC,
    if(prior_n = 0, NULL, toFloat32(n_sl)  / prior_n)  AS ars_SL,
    if(prior_n = 0, NULL, toFloat32(n_cu)  / prior_n)  AS ars_CU,
    if(prior_n = 0, NULL, toFloat32(n_ch)  / prior_n)  AS ars_CH,
    if(prior_n = 0, NULL, toFloat32(n_off) / prior_n)  AS ars_OFF,
    if(prior_n_by_count = 0, NULL, toFloat32(n_ff_by_count) / prior_n_by_count) AS ars_FF_by_count,
    toUInt32(prior_n)                                  AS pitcher_prior_n
FROM windowed
WHERE game_date BETWEEN toDate(:test_start) AND toDate(:test_end)
ORDER BY game_date, game_id, at_bat_index, pitch_number
SETTINGS max_memory_usage = 0
