package net.thebullpen.baseball.data;

import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Writer for {@code pitcher_form_current} (V007): the denormalised current Tier-3 form snapshot the
 * live pitch path reads by a single point lookup, instead of re-scanning ~50M pitch rows per
 * request (V007 doc).
 *
 * <p>DP2 / WS3. The nightly refresh recomputes each active pitcher's 28-day form FRESH from {@code
 * pitches} (current as-of-today), NOT from the training {@code features} table: that table is the
 * per-fold historical store written during CV runs, so reading it would serve stale form. The
 * strike / swstrike / in-play definitions mirror {@code compute_tier3.sql} EXACTLY so the live
 * snapshot is consistent with what the model trained on.
 *
 * <p>Gated on {@code bullpen.clickhouse.enabled} (NOT
 * {@code @ConditionalOnBean(clickhouseDataSource)} - that bean-ordering guard crash-looped the
 * worker for ~4 days post-2026-05-31; see DriftMetricsRepository). On both {@code api} + {@code
 * worker} so the docker-gated IT can wire it under the api profile, but only the worker {@code
 * PitcherFormRefreshJob} actually calls it.
 */
@Repository
@Profile({"api", "worker"})
@ConditionalOnProperty(name = "bullpen.clickhouse.enabled", havingValue = "true")
public class PitcherFormRepository {

  /**
   * Current 28-day form per active pitcher, as of today. Notes:
   *
   * <ul>
   *   <li>{@code pitches FINAL} - pitches is a ReplacingMergeTree, so FINAL dedups a re-ingested
   *       pitch (DEF-H3) before it double-counts the window aggregates.
   *   <li>{@code days_since_last_appearance} is days since the pitcher's most recent game. It uses
   *       {@code max(game_date)}, NOT {@code lagInFrame}, so there is no 1970-01-01 epoch trap (the
   *       bug fixed in compute_tier3.sql) and it is never NULL for an active pitcher - one game is
   *       guaranteed inside the 28-day window by the WHERE clause.
   *   <li>{@code pitches_in_game = 0} in the nightly snapshot: in-game count is a live-poll concept
   *       the intra-day upsert (a documented follow-up) sets during a game.
   *   <li>This is a CURRENT aggregate over PAST games only ({@code game_date <= today}); there is
   *       no future data and no per-pitch streaming-cutoff surface, so it is not a leakage path.
   * </ul>
   */
  private static final String REFRESH =
      "INSERT INTO pitcher_form_current"
          + " (pitcher_id, as_of_date, pitches_in_game, pitches_last_28d,"
          + "  strike_rate_28d, swstrike_rate_28d, inplay_rate_28d, days_since_last_appearance)"
          + " SELECT pitcher_id, today() AS as_of_date, 0 AS pitches_in_game,"
          + "        toUInt32(count()) AS pitches_last_28d,"
          + "        toFloat32(countIf(description IN ('called_strike','swinging_strike','foul'))"
          + "                  / count()) AS strike_rate_28d,"
          + "        toFloat32(countIf(description = 'swinging_strike') / count())"
          + "                  AS swstrike_rate_28d,"
          + "        toFloat32(countIf(description = 'in_play') / count()) AS inplay_rate_28d,"
          + "        toUInt16(today() - max(game_date)) AS days_since_last_appearance"
          + " FROM pitches FINAL"
          // game_date <= today() makes the past-only contract LOCAL to this query (defense in
          // depth): pitches is historical-only today (live data lands in pitches_live), but an
          // explicit upper bound means a future backfill cannot make today()-max(game_date) wrap
          // negative through toUInt16. (ml-leakage-auditor note.)
          + " WHERE description != 'unknown'"
          + "   AND game_date >= today() - 28 AND game_date <= today()"
          + " GROUP BY pitcher_id";

  // Non-FINAL is correct here: the REFRESH above just wrote exactly one row per pitcher at today's
  // as_of_date, so today's partition has nothing to dedup yet. (Reads of older rows use FINAL.)
  private static final String COUNT_TODAY =
      "SELECT count(DISTINCT pitcher_id) FROM pitcher_form_current WHERE as_of_date = today()";

  private final JdbcTemplate jdbc;

  public PitcherFormRepository(@Qualifier("clickhouseDataSource") DataSource clickhouse) {
    this.jdbc = new JdbcTemplate(clickhouse);
  }

  /**
   * Recompute and insert today's current-form row for every active pitcher (a pitch in the last 28
   * days). Returns the number of distinct pitchers refreshed today. The ReplacingMergeTree dedups
   * on {@code pitcher_id} keeping the newest {@code ingested_at}, so the prior day's rows compact
   * away.
   */
  public long refreshCurrentForm() {
    jdbc.update(REFRESH);
    Long n = jdbc.queryForObject(COUNT_TODAY, Long.class);
    return n == null ? 0L : n;
  }
}
