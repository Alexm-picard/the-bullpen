package net.thebullpen.baseball.data;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import net.thebullpen.baseball.domain.RollingAccuracyBucket;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Rolling realized-accuracy truth join for the /accuracy Live Scorecard (Alex's ask): per-ET-day
 * top-1 accuracy of champion-served live predictions against what actually happened.
 *
 * <p>Join discipline is inherited wholesale from the display + drift paths, not reinvented:
 *
 * <ul>
 *   <li><b>Dedup:</b> {@code prediction_log} ACCUMULATES (a worker restart re-logs the same
 *       upcoming pitch key on every poll), so the prediction side collapses to one row per {@code
 *       (game_id, at_bat_index, pitch_number)} keeping the LATEST by {@code request_at} - the
 *       {@code LIMIT 1 BY} idiom of {@code ClickHouseSegmentedTruthJoinedPredictionFetcher} (the
 *       #341/#342 dedup class). Without it, a restart double-counts pitches and the accuracy %
 *       moves on restarts instead of on games.
 *   <li><b>Truth:</b> {@code pitches_live FINAL} (ReplacingMergeTree - the feed corrects rows),
 *       INNER JOIN so orphan predictions (a predicted pitch that never landed) stay out of the
 *       denominator, mirroring {@code PredictionLogRepository.SELECT_CALIBRATION_SET}'s V017
 *       contract.
 *   <li><b>Scorability:</b> truth must be inside the model's vocabulary and the prediction JSON
 *       must parse; unscorable rows leave BOTH numerator and denominator.
 * </ul>
 *
 * <p>The top-1 winner is computed IN SQL as the argmax over the stored {@code {"probabilities":
 * {...}}} map: sort the key/value pairs by {@code (-value, key)} and take the first key. The
 * secondary key makes an exact-tie deterministic (alphabetical); the Java scorers ({@code
 * ClickHouseTruthJoinedPredictionFetcher.probsOf}) break exact ties by vocabulary order instead -
 * real served probabilities never tie exactly, and the repository IT pins SQL-vs-Java agreement on
 * untied fixtures so the two implementations cannot drift silently on the cases that occur.
 *
 * <p>Day buckets are ET ({@code toDate(request_at, 'America/New_York')}) - the project's day
 * boundary everywhere else (the TODAY_ET lesson); a UTC bucket would split evening games across two
 * "days". The window is bounded in practice by the 14-day {@code pitches_live} TTL: a 30-day
 * request is honest but its older days simply have no joinable truth.
 */
@Repository
@ConditionalOnProperty(name = "bullpen.clickhouse.enabled", havingValue = "true")
public class RollingAccuracyRepository {

  /** The two families whose realized truth is {@code pitches_live.description} (y5 vocabulary). */
  public static final Set<String> PITCH_OUTCOME_MODELS =
      Set.of("pitch_outcome_pre", "pitch_outcome_post");

  // The deduped, winner-annotated prediction side. kv is Array(Tuple(String, Float64)) sorted by
  // (-probability, class) so element 1 is the deterministic top-1; an unparseable prediction
  // yields an empty array and is excluded from n and hits alike.
  private static final String PREDICTION_SIDE =
      "   SELECT game_id, at_bat_index, pitch_number, request_at,"
          + "      arraySort(x -> (-tupleElement(x, 2), tupleElement(x, 1)),"
          + "        JSONExtractKeysAndValues(JSONExtractRaw(prediction, 'probabilities'),"
          + "          'Float64')) AS kv"
          + "   FROM prediction_log"
          + "   WHERE model_name = ? AND role = 'champion' AND game_id IS NOT NULL"
          + "     AND request_at >= now() - toIntervalDay(?)"
          + "   ORDER BY request_at DESC"
          + "   LIMIT 1 BY game_id, at_bat_index, pitch_number";

  private static final String SELECT_SHAPE =
      "SELECT toDate(p.request_at, 'America/New_York') AS d,"
          + " countIf(length(p.kv) > 0) AS n,"
          + " countIf(length(p.kv) > 0 AND tupleElement(p.kv[1], 1) = t.truth_class) AS hits"
          + " FROM ("
          + PREDICTION_SIDE
          + " ) AS p"
          + " INNER JOIN ("
          + "%s"
          + " ) AS t"
          + " ON p.game_id = t.game_id AND p.at_bat_index = t.at_bat_index"
          + "    AND p.pitch_number = t.pitch_number"
          + " GROUP BY d ORDER BY d ASC";

  /**
   * y5 truth side: the parser writes {@code description} in exactly the locked 5-class vocabulary
   * ({@code ClickHouseTruthJoinedPredictionFetcher.OUTCOME_CLASSES}); anything else (feed oddity)
   * is out-of-vocabulary and unscorable.
   */
  private static final String PITCH_OUTCOME_SQL =
      SELECT_SHAPE.formatted(
          "   SELECT game_id, at_bat_index, pitch_number, description AS truth_class"
              + "   FROM pitches_live FINAL"
              + "   WHERE description IN"
              + "     ('ball', 'called_strike', 'swinging_strike', 'foul', 'in_play')");

  /**
   * y7 truth side: the realized Statcast {@code pitch_type} folded through the SAME canonical y7
   * expression training and the arsenal deriver use ({@code
   * PitcherPitchTypePriorSnapshotSql.CANONICAL_Y7}) - a second fold implementation here would be
   * the C1 divergence class. {@code pitch_type != ''} first: the live parser defaults unknown pitch
   * types to {@code ''}, and letting the fold map that to OFF would score the model against
   * fabricated truth.
   */
  private static final String PITCH_TYPE_SQL =
      SELECT_SHAPE.formatted(
          "   SELECT game_id, at_bat_index, pitch_number, "
              + PitcherPitchTypePriorSnapshotSql.CANONICAL_Y7
              + " AS truth_class"
              + "   FROM pitches_live FINAL"
              + "   WHERE pitch_type != ''");

  private static final RowMapper<RollingAccuracyBucket> BUCKET_MAPPER =
      (rs, i) ->
          new RollingAccuracyBucket(
              rs.getDate("d").toLocalDate(), rs.getLong("n"), rs.getLong("hits"));

  private final JdbcTemplate jdbc;

  public RollingAccuracyRepository(@Qualifier("clickhouseDataSource") DataSource clickhouse) {
    this.jdbc = new JdbcTemplate(clickhouse);
  }

  /**
   * Per-ET-day realized top-1 buckets for one of the two pitch-outcome heads over the trailing
   * {@code days}. The model-name allowlist is enforced here (not merely upstream) because the SQL
   * pins the y5 truth vocabulary - scoring any other family against {@code description} would
   * produce a plausible-looking 0%.
   */
  public List<RollingAccuracyBucket> pitchOutcomeDaily(String modelName, int days) {
    if (!PITCH_OUTCOME_MODELS.contains(modelName)) {
      throw new IllegalArgumentException(
          "pitchOutcomeDaily scores the y5 description vocabulary; got " + modelName);
    }
    return jdbc.query(PITCH_OUTCOME_SQL, BUCKET_MAPPER, modelName, days);
  }

  /** Per-ET-day realized top-1 buckets for pitch_type_pre (y7 fold truth). */
  public List<RollingAccuracyBucket> pitchTypeDaily(int days) {
    return jdbc.query(PITCH_TYPE_SQL, BUCKET_MAPPER, "pitch_type_pre", days);
  }

  /** Totals across buckets; a windowed weighted sum, never an average of daily percentages. */
  public static long totalN(List<RollingAccuracyBucket> buckets) {
    return buckets.stream().mapToLong(RollingAccuracyBucket::n).sum();
  }

  public static long totalHits(List<RollingAccuracyBucket> buckets) {
    return buckets.stream().mapToLong(RollingAccuracyBucket::hits).sum();
  }

  /** Earliest bucket date, for labelling the effective (TTL-bounded) window. */
  public static LocalDate earliest(List<RollingAccuracyBucket> buckets) {
    return buckets.isEmpty() ? null : buckets.getFirst().date();
  }
}
