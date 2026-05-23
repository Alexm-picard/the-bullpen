-- Cleaning transform: raw_statcast -> pitches.
-- Bind variables: :year (int).
--
-- Filters to regular-season only (game_type='R'). Spring training and
-- postseason rows stay in raw_statcast but never reach the cleaned layer
-- in v1. Dedup is handled by the pitches ReplacingMergeTree(ingested_at)
-- engine — re-running INSERTs the same keys and the engine's merge picks
-- the latest ingested_at per key. FINAL queries always see the deduped state.

INSERT INTO pitches
SELECT
    game_pk                                    AS game_id,
    game_date,
    at_bat_index,
    pitch_number,
    pitcher                                    AS pitcher_id,
    batter                                     AS batter_id,
    upper(pitch_type)                          AS pitch_type,
    multiIf(
        description IN ('ball', 'blocked_ball', 'intent_ball', 'pitchout', 'automatic_ball'),         'ball',
        description IN ('called_strike', 'automatic_strike'),                                          'called_strike',
        description IN ('swinging_strike', 'swinging_strike_blocked', 'missed_bunt'),                  'swinging_strike',
        description IN ('foul', 'foul_tip', 'foul_bunt', 'foul_pitchout', 'bunt_foul_tip'),            'foul',
        description IN ('hit_into_play', 'hit_into_play_no_out', 'hit_into_play_score'),               'in_play',
        description = 'hit_by_pitch',                                                                   'hit_by_pitch',
        'unknown'
    )                                          AS description,
    events,
    release_speed                              AS release_speed_mph,
    plate_x                                    AS plate_x_in,
    plate_z                                    AS plate_z_in,
    launch_speed                               AS launch_speed_mph,
    launch_angle                               AS launch_angle_deg,
    hc_x,
    hc_y,
    hit_distance_sc                            AS hit_distance_ft,
    bb_type,
    home_team,
    away_team,
    home_team                                  AS park_id,
    stand,
    p_throws,
    coalesce(balls, 0)                         AS balls,
    coalesce(strikes, 0)                       AS strikes,
    coalesce(outs_when_up, 0)                  AS outs,
    coalesce(inning, 0)                        AS inning,
    toUInt8(
        if(on_1b IS NULL OR on_1b = 0, 0, 1)
      + if(on_2b IS NULL OR on_2b = 0, 0, 2)
      + if(on_3b IS NULL OR on_3b = 0, 0, 4)
    )                                          AS base_state,
    now()                                      AS ingested_at
FROM raw_statcast
WHERE toYear(game_date) = :year
  AND game_type = 'R';
