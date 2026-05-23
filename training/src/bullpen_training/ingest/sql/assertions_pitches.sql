-- Per-stage assertions for pitches. One query per assertion; the orchestrator
-- runs each and fails loud on any non-zero result (except expected_count which
-- has a tolerance band — orchestrator uses assert_row_count_in_range).
--
-- Bind variables: :year (int).
--
-- Sections are delimited by `-- @name: <id>` markers; the orchestrator splits
-- on those, runs each, and uses the id in the failure message.

-- @name: regular_season_count
-- Expected: ~700K ±5%. Compared via assert_row_count_in_range.
SELECT count(*) FROM pitches FINAL WHERE toYear(game_date) = :year;

-- @name: zero_id_rows
-- Must be 0: pitches with sentinel zero IDs are corrupt.
SELECT count(*) FROM pitches FINAL
WHERE toYear(game_date) = :year
  AND (game_id = 0 OR pitcher_id = 0 OR batter_id = 0);

-- @name: launch_speed_out_of_range
-- Must be 0: a batted ball outside 0-130 mph is sensor noise (negative speeds
-- are pure errors; >130 exceeds the all-time record). Weak fouls/bunts in
-- the 20-40 range are real Statcast values and not noise — kept inside.
SELECT count(*) FROM pitches FINAL
WHERE toYear(game_date) = :year
  AND launch_speed_mph IS NOT NULL
  AND launch_speed_mph NOT BETWEEN 0 AND 130;

-- @name: launch_angle_out_of_range
-- Must be 0: launch angle outside [-90, 90] degrees is geometrically impossible.
SELECT count(*) FROM pitches FINAL
WHERE toYear(game_date) = :year
  AND launch_angle_deg IS NOT NULL
  AND launch_angle_deg NOT BETWEEN -90 AND 90;

-- @name: unknown_description_excess
-- Must be < 100: more than that means our description multiIf is missing a case.
SELECT count(*) FROM pitches FINAL
WHERE toYear(game_date) = :year
  AND description = 'unknown';

-- @name: dedup_consistency
-- FINAL count must be <= raw INSERT count (only equal when no dupes); larger
-- means the engine isn't collapsing keys.
SELECT count(*) - countDistinct(game_id, at_bat_index, pitch_number) FROM pitches FINAL
WHERE toYear(game_date) = :year;
