package net.thebullpen.baseball.data;

import java.util.List;
import javax.sql.DataSource;
import net.thebullpen.baseball.domain.ReliabilityBinRow;
import net.thebullpen.baseball.domain.ReliabilityWindow;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Read + write side of {@code live_reliability_daily} (V035): the per-(model, ET game day, bin)
 * live top-label reliability rollup behind the /accuracy calibration charts.
 *
 * <p><b>Write.</b> {@link #rollup} recomputes the trailing N ET game days (yesterday back) from the
 * SAME truth join the rolling-accuracy scorecard runs ({@link
 * RollingAccuracyRepository#reliabilitySql}, embedded verbatim - one subject, no second join) and
 * writes the FULL 10-bin grid for every recomputed day, zero rows included. The zero grid is
 * load-bearing: {@code ReplacingMergeTree(computed_at)} only replaces rows that are re-written, so
 * a feed correction that empties a bin would otherwise leave its stale non-zero count behind
 * forever. Reads {@code pitches_live} only; it is not a live-to-historical promotion job ([186])
 * and writes nothing to {@code prediction_log} ([188]).
 *
 * <p><b>Read.</b> {@link #window} serves "through yesterday" (today's games land in the next
 * morning's run). FINAL is applied in an inner subquery that filters on ORDER BY key columns only;
 * {@code n > 0} is filtered OUTSIDE it, so a zero row that replaced a stale count could not let the
 * stale count leak even if a future server pushed a non-key filter ahead of the FINAL merge (24.12
 * does not - verified by mutation in {@code LiveReliabilityRollupIT}; this is defence in depth).
 */
@Repository
@ConditionalOnProperty(name = "bullpen.clickhouse.enabled", havingValue = "true")
public class LiveReliabilityRepository {

  /** Ten equal-width top-label confidence bins. */
  public static final int BINS = 10;

  private static final String TODAY_ET = "toDate(now('America/New_York'))";

  // Binds: model_name (literal), grid days, then the reliability aggregate's own three binds
  // (model_name, prediction scan days, truth offset days). The grid is days [today-k, today-1] x
  // bins 0..9; the aggregate's truth side spans [today-k, today], and the LEFT JOIN onto the grid
  // drops any today row, so "through yesterday" holds on the write side too.
  private static final String ROLLUP_PREFIX =
      "INSERT INTO live_reliability_daily"
          + " (model_name, game_date, bin, n, hits, sum_confidence, computed_at)"
          + " SELECT ? AS model_name, g.d AS game_date, g.bin AS bin,"
          + "   ifNull(a.n, 0), ifNull(a.hits, 0), ifNull(a.sum_confidence, 0),"
          + "   now64(3, 'UTC')"
          + " FROM ("
          + "   SELECT "
          + TODAY_ET
          + " - off AS d, toUInt8(b) AS bin"
          + "   FROM (SELECT arrayJoin(range(1, toUInt64(?) + 1)) AS off,"
          + "                arrayJoin(range("
          + BINS
          + ")) AS b)"
          + " ) AS g"
          + " LEFT JOIN (";

  private static final String ROLLUP_SUFFIX = ") AS a ON g.d = a.d AND g.bin = a.bin";

  /** FINAL-deduped, zero-filtered cells for one model with {@code game_date < today ET}. */
  private static final String LIVE_CELLS =
      "SELECT game_date AS d, bin, n, hits, sum_confidence FROM ("
          + "   SELECT game_date, bin, n, hits, sum_confidence"
          + "   FROM live_reliability_daily FINAL"
          + "   WHERE model_name = ? AND game_date < "
          + TODAY_ET
          + " ) WHERE n > 0";

  // Binds: model_name, calibrationDays. [today-K, today-1] = exactly K ET calendar days.
  private static final String CALENDAR_SQL =
      LIVE_CELLS + " AND d >= " + TODAY_ET + " - ? ORDER BY d, bin";

  // Binds: model_name, model_name, K. The K most recent ET game days that carry graded calls.
  private static final String DAYS_OF_PLAY_SQL =
      LIVE_CELLS
          + " AND d >= (SELECT min(gd) FROM ("
          + "   SELECT DISTINCT d AS gd FROM ("
          + LIVE_CELLS
          + "   ) ORDER BY gd DESC LIMIT ?))"
          + " ORDER BY d, bin";

  // Binds: model_name. Days since the newest rolled-up day (zero rows count: a written zero grid
  // means "computed"), and whether the model has any row at all.
  private static final String COVERAGE_SQL =
      "SELECT count() AS c, dateDiff('day', max(game_date), "
          + TODAY_ET
          + ") AS gap FROM live_reliability_daily WHERE model_name = ?";

  private final JdbcTemplate jdbc;

  public LiveReliabilityRepository(@Qualifier("clickhouseDataSource") DataSource clickhouse) {
    this.jdbc = new JdbcTemplate(clickhouse);
  }

  /**
   * Recompute and (re)write the full 10-bin grid for {@code modelName} over the {@code days} ET
   * calendar days ending yesterday. Idempotent: re-running yields identical FINAL sums.
   */
  public void rollup(String modelName, int days) {
    if (days < 1) {
      throw new IllegalArgumentException("rollup days must be >= 1; got " + days);
    }
    String sql =
        ROLLUP_PREFIX + RollingAccuracyRepository.reliabilitySql(modelName) + ROLLUP_SUFFIX;
    // Prediction-side scan bound days+2: one day for the UTC-vs-ET skew, one for the run time
    // (06:30 ET) sitting past midnight of the newest recomputed day.
    jdbc.update(sql, modelName, days, modelName, days + 2, days);
  }

  /**
   * Days since the newest day already rolled up for {@code modelName}, or {@code -1} when the model
   * has no row at all (first run).
   */
  public long daysSinceLastRollup(String modelName) {
    List<Long> gap =
        jdbc.query(
            COVERAGE_SQL, (rs, i) -> rs.getLong("c") == 0 ? -1L : rs.getLong("gap"), modelName);
    // An aggregate without GROUP BY always yields exactly one row; empty is defensive only.
    return gap.isEmpty() ? -1L : gap.getFirst();
  }

  /**
   * The cells behind one model's calibration chart: the trailing {@code days} ET calendar days
   * through yesterday; when that window holds no graded call, the {@code days} most recent ET game
   * days that do ({@link ReliabilityWindow.Kind#LAST_DAYS_OF_PLAY}). Both empty -> an empty
   * CALENDAR window, never a fabricated cell.
   *
   * <p>Daily edge: between 00:00 ET and the 06:30 ET run, yesterday is not rolled up yet, so the
   * calendar window holds at most K-1 graded days and {@code truthThrough} is two days back. That
   * is reported honestly through the dates, not hidden.
   */
  public ReliabilityWindow window(String modelName, int days) {
    List<ReliabilityBinRow> calendar =
        jdbc.query(CALENDAR_SQL, RollingAccuracyRepository.RELIABILITY_MAPPER, modelName, days);
    if (!calendar.isEmpty()) {
      return new ReliabilityWindow(ReliabilityWindow.Kind.CALENDAR, calendar);
    }
    List<ReliabilityBinRow> played =
        jdbc.query(
            DAYS_OF_PLAY_SQL,
            RollingAccuracyRepository.RELIABILITY_MAPPER,
            modelName,
            modelName,
            days);
    return played.isEmpty()
        ? new ReliabilityWindow(ReliabilityWindow.Kind.CALENDAR, List.of())
        : new ReliabilityWindow(ReliabilityWindow.Kind.LAST_DAYS_OF_PLAY, played);
  }
}
