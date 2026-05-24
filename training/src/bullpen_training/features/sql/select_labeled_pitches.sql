-- Pull labeled pitches in a date window, with Tier 1 columns ready and the
-- 5-class label projected from the existing `pitches.description` enum.
-- Bind variables: :start_date, :end_date (inclusive).
-- Used by tier_1_2.build_features_t12 for both the TE training window
-- (label-only projection) and the to-encode window (full Tier 1 + identity).

SELECT
    game_id,
    at_bat_index,
    pitch_number,
    game_date,
    pitcher_id,
    batter_id,

    coalesce(balls, 0)            AS count_balls,
    coalesce(strikes, 0)          AS count_strikes,
    coalesce(outs, 0)             AS outs,
    coalesce(inning, 0)           AS inning,
    base_state,
    -- score_diff: home minus away regardless of who is batting.
    -- pitches table doesn't carry score yet; placeholder 0 here, real value
    -- lands when V003 grows score columns (Phase 2a.2 backfill candidate).
    toInt16(0)                    AS score_diff,
    p_throws                      AS pitcher_throws,
    stand                         AS batter_stand,
    park_id,
    toUInt8(toDayOfWeek(game_date)) AS dow,

    toString(description)         AS label
FROM pitches
WHERE game_date BETWEEN :start_date AND :end_date
  AND description IN ('ball', 'called_strike', 'swinging_strike', 'foul', 'in_play')
SETTINGS max_memory_usage = 0
