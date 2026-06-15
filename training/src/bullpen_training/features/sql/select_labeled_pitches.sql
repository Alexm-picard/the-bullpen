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
    -- score_diff: the BATTING team's lead going INTO the pitch (bat_score - fld_score),
    -- a pre-pitch game state with no leakage (it flips sign on top/bottom of the inning,
    -- which is the point - "is the batter's side ahead or behind"). Sourced from
    -- pitches.score_diff_live, which transform_raw_to_pitches propagates from
    -- raw_statcast.bat_score_diff (a native pre-pitch Statcast field; the pull selects no
    -- post_* score column). coalesce to 0 for rows predating the score_diff_live backfill.
    toInt16(coalesce(score_diff_live, 0)) AS score_diff,
    p_throws                      AS pitcher_throws,
    stand                         AS batter_stand,
    park_id,
    toUInt8(toDayOfWeek(game_date)) AS dow,

    toString(description)         AS label
-- FINAL: pitches is a ReplacingMergeTree; without it a re-ingested (corrected) pitch double-counts
-- the per-row label, the target-encoding numerators, and every Tier-3 rolling-window count (DEF-H3).
FROM pitches FINAL
WHERE game_date BETWEEN :start_date AND :end_date
  AND description IN ('ball', 'called_strike', 'swinging_strike', 'foul', 'in_play')
SETTINGS max_memory_usage = 0
