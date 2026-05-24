-- Tier 3 rolling-form features for one fold's test window.
-- Bind variables: :test_start, :test_end (inclusive).
--
-- Single-scan version: all 12 window functions defined over the same
-- source SELECT to avoid materializing 3 separate CTEs from `pitches`
-- (which OOM'd at the 11-season scale).
--
-- The 90-day look-back before :test_start guarantees the 28-day and
-- season-to-date windows have enough warm-up history for pitch #1 of
-- the test window onward. Filter to `description != 'unknown'` at the
-- top to keep the windowed scan as narrow as the data allows.
--
-- `days_since_last_appearance` is computed from the pitcher's distinct
-- date series via a self-anti-join (cheaper than building yet another
-- CTE that joins back row-by-row).

WITH
    base AS (
        SELECT *
        FROM pitches
        WHERE description != 'unknown'
          AND game_date >= toDate(:test_start) - 90
          AND game_date <= toDate(:test_end)
    ),
    windowed AS (
        SELECT
            game_id, at_bat_index, pitch_number, game_date, pitcher_id, batter_id,
            count() OVER w_pitch_28d AS pp_last_28d,
            countIf(description IN ('called_strike', 'swinging_strike', 'foul')) OVER w_pitch_28d AS pp_strikes_28d,
            countIf(description = 'swinging_strike') OVER w_pitch_28d AS pp_swstrike_28d,
            countIf(description = 'in_play') OVER w_pitch_28d AS pp_inplay_28d,
            count() OVER w_pitch_std AS pp_std,
            countIf(description IN ('called_strike', 'swinging_strike', 'foul')) OVER w_pitch_std AS pp_strikes_std,
            count() OVER w_pitch_ingame AS pp_in_game,
            count() OVER w_bat_28d AS bp_last_28d,
            countIf(description IN ('called_strike', 'swinging_strike', 'foul')) OVER w_bat_28d AS bp_strikes_28d,
            countIf(description = 'in_play') OVER w_bat_28d AS bp_inplay_28d,
            countIf(description = 'ball') OVER w_bat_28d AS bp_balls_28d,
            count() OVER w_bat_std AS bp_std,
            countIf(description = 'in_play') OVER w_bat_std AS bp_inplay_std
        FROM base
        WINDOW
            w_pitch_28d AS (
                PARTITION BY pitcher_id ORDER BY game_date
                RANGE BETWEEN 28 PRECEDING AND 1 PRECEDING
            ),
            w_pitch_std AS (
                PARTITION BY pitcher_id, toYear(game_date) ORDER BY game_date
                RANGE BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING
            ),
            w_pitch_ingame AS (
                PARTITION BY pitcher_id, game_id ORDER BY at_bat_index, pitch_number
                ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING
            ),
            w_bat_28d AS (
                PARTITION BY batter_id ORDER BY game_date
                RANGE BETWEEN 28 PRECEDING AND 1 PRECEDING
            ),
            w_bat_std AS (
                PARTITION BY batter_id, toYear(game_date) ORDER BY game_date
                RANGE BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING
            )
    ),
    appearances AS (
        SELECT
            pitcher_id, game_date,
            lagInFrame(game_date) OVER (
                PARTITION BY pitcher_id ORDER BY game_date
                ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW
            ) AS prev_appearance_date
        FROM (
            SELECT DISTINCT pitcher_id, game_date
            FROM base
        )
    )
SELECT
    w.game_id, w.at_bat_index, w.pitch_number,
    nullIf(w.pp_last_28d, 0)                                       AS pitcher_pitches_last_28d,
    toUInt32(w.pp_in_game)                                          AS pitcher_pitches_in_game,
    if(a.prev_appearance_date IS NULL, NULL,
       toUInt16(w.game_date - a.prev_appearance_date))              AS days_since_last_appearance,
    if(w.pp_last_28d = 0, NULL,
       toFloat32(w.pp_strikes_28d) / w.pp_last_28d)                 AS pitcher_strike_rate_28d,
    if(w.pp_last_28d = 0, NULL,
       toFloat32(w.pp_swstrike_28d) / w.pp_last_28d)                AS pitcher_swstrike_rate_28d,
    if(w.pp_last_28d = 0, NULL,
       toFloat32(w.pp_inplay_28d) / w.pp_last_28d)                  AS pitcher_inplay_rate_28d,
    if(w.pp_std = 0, NULL,
       toFloat32(w.pp_strikes_std) / w.pp_std)                      AS pitcher_strike_rate_std,
    if(w.bp_last_28d = 0, NULL,
       toFloat32(w.bp_strikes_28d) / w.bp_last_28d)                 AS batter_strike_rate_28d,
    if(w.bp_last_28d = 0, NULL,
       toFloat32(w.bp_inplay_28d) / w.bp_last_28d)                  AS batter_inplay_rate_28d,
    if(w.bp_last_28d = 0, NULL,
       toFloat32(w.bp_balls_28d) / w.bp_last_28d)                   AS batter_ball_rate_28d,
    if(w.bp_std = 0, NULL,
       toFloat32(w.bp_inplay_std) / w.bp_std)                       AS batter_inplay_rate_std
FROM windowed AS w
LEFT JOIN appearances AS a
    ON a.pitcher_id = w.pitcher_id AND a.game_date = w.game_date
WHERE w.game_date BETWEEN toDate(:test_start) AND toDate(:test_end)
ORDER BY w.game_date, w.game_id, w.at_bat_index, w.pitch_number
SETTINGS max_memory_usage = 0
