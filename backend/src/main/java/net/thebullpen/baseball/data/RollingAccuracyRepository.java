package net.thebullpen.baseball.data;

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
 * Rolling realized-accuracy truth join for the /accuracy Live Scorecard (Alex's ask): per-day top-1
 * accuracy of champion-served live predictions against what actually happened.
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
 *   <li><b>Scorability:</b> truth must be inside the model's vocabulary and the prediction row must
 *       carry a scorable winner; unscorable rows leave BOTH numerator and denominator.
 * </ul>
 *
 * <p><b>Buckets key on the TRUTH side's {@code game_date}</b>, not the kept prediction's {@code
 * request_at}: a poll re-log that crosses ET midnight would otherwise migrate the pitch between
 * daily buckets (window total stable, sparkline not - the review's A1 demonstration), and {@code
 * game_date} is both restart-invariant and the correct baseball day for extra-inning / west-coast
 * games. The window is likewise anchored to ET calendar days ({@code game_date >= today_ET -
 * (days-1)}), so {@code days=7} yields at most 7 buckets, none partial-by-clock; the prediction
 * side keeps a {@code request_at >= now() - (days+1)d} bound purely as a scan limit (one day of
 * slack for the UTC-vs-ET skew). In practice truth exists only ~14 days back (the {@code
 * pitches_live} TTL): a 30-day request is honest but its older days have nothing to join.
 *
 * <p><b>The y5 winner is the PERSISTED one</b> ({@code JSONExtractString(prediction, 'winner')}) -
 * the serving path writes the argmax it actually showed ({@code PitchPredictionService}), so
 * re-deriving it here would be a second implementation that could disagree on exact ties and score
 * a class the user was never shown. The y7 arm has no persisted winner BY DESIGN ([183]: the
 * pitch-type serialization refuses to write an argmax), so it computes one from the probabilities
 * map, sorted by {@code (-value, key)} for a deterministic tie-break; the repository IT pins the
 * SQL argmax against untied fixtures.
 */
@Repository
@ConditionalOnProperty(name = "bullpen.clickhouse.enabled", havingValue = "true")
public class RollingAccuracyRepository {

  /** The two families whose realized truth is {@code pitches_live.description} (y5 vocabulary). */
  public static final Set<String> PITCH_OUTCOME_MODELS =
      Set.of("pitch_outcome_pre", "pitch_outcome_post");

  // Shared query scaffold. Holes: %1$s = the prediction-side winner projection, %2$s = the
  // scorable predicate over alias p, %3$s = the winner expression over alias p, %4$s = the truth
  // subquery (must project the join key + game_date + truth_class). Binds, in order:
  // model_name, days+1 (prediction-side scan bound), days-1 (ET calendar-day window). The truth
  // side is also upper-bounded at today-ET: the parser's rare officialDate-absent fallback stamps
  // the UTC first-pitch date, which for a late PT game is TOMORROW - without the bound that row
  // would render a future sparkline bucket.
  // Temporal guard: p.request_at < t.ingested_at enforces the pre-pitch claim: the prediction
  // was logged BEFORE the truth row was ingested. Worker rows satisfy this by construction (the
  // poller predicts the UPCOMING pitch, then ingests it when the feed delivers it); the guard
  // exists so the metric's pre-pitch claim is enforced, not assumed.
  private static final String SHAPE =
      "SELECT t.game_date AS d,"
          + " countIf(%2$s) AS n,"
          + " countIf(%2$s AND %3$s = t.truth_class) AS hits"
          + " FROM ("
          + "   SELECT game_id, at_bat_index, pitch_number, request_at, %1$s"
          + "   FROM prediction_log"
          + "   WHERE model_name = ? AND role = 'champion' AND game_id IS NOT NULL"
          + "     AND request_at >= now() - toIntervalDay(?)"
          + "   ORDER BY request_at DESC"
          + "   LIMIT 1 BY game_id, at_bat_index, pitch_number"
          + " ) AS p"
          + " INNER JOIN ("
          + "%4$s"
          + " ) AS t"
          + " ON p.game_id = t.game_id AND p.at_bat_index = t.at_bat_index"
          + "    AND p.pitch_number = t.pitch_number"
          + "    AND p.request_at < t.ingested_at"
          + " GROUP BY d ORDER BY d ASC";

  /**
   * y5 truth side: the parser writes {@code description} in exactly the locked 5-class vocabulary
   * ({@code ClickHouseTruthJoinedPredictionFetcher.OUTCOME_CLASSES}); anything else (feed oddity)
   * is out-of-vocabulary and unscorable.
   */
  private static final String PITCH_OUTCOME_SQL =
      SHAPE.formatted(
          "JSONExtractString(prediction, 'winner') AS w",
          "p.w != ''",
          "p.w",
          "   SELECT game_id, at_bat_index, pitch_number, game_date, ingested_at,"
              + "     description AS truth_class"
              + "   FROM pitches_live FINAL"
              + "   WHERE description IN"
              + "     ('ball', 'called_strike', 'swinging_strike', 'foul', 'in_play')"
              + "     AND game_date >= toDate(now('America/New_York')) - ?"
              + " AND game_date <= toDate(now('America/New_York'))");

  /**
   * y7 truth side: the realized Statcast {@code pitch_type} folded through the SAME canonical y7
   * expression training and the arsenal deriver use ({@code
   * PitcherPitchTypePriorSnapshotSql.CANONICAL_Y7}) - a second fold implementation here would be
   * the C1 divergence class. {@code pitch_type != ''} first: the live parser defaults unknown pitch
   * types to {@code ''}, and letting the fold map that to OFF would score the model against
   * fabricated truth.
   */
  private static final String PITCH_TYPE_SQL =
      SHAPE.formatted(
          "arraySort(x -> (-tupleElement(x, 2), tupleElement(x, 1)),"
              + " JSONExtractKeysAndValues(JSONExtractRaw(prediction, 'probabilities'),"
              + " 'Float64')) AS kv",
          "length(p.kv) > 0",
          "tupleElement(p.kv[1], 1)",
          "   SELECT game_id, at_bat_index, pitch_number, game_date, ingested_at, "
              + PitcherPitchTypePriorSnapshotSql.CANONICAL_Y7
              + " AS truth_class"
              + "   FROM pitches_live FINAL"
              + "   WHERE pitch_type != ''"
              + "     AND game_date >= toDate(now('America/New_York')) - ?"
              + " AND game_date <= toDate(now('America/New_York'))");

  private static final RowMapper<RollingAccuracyBucket> BUCKET_MAPPER =
      (rs, i) ->
          new RollingAccuracyBucket(
              rs.getDate("d").toLocalDate(), rs.getLong("n"), rs.getLong("hits"));

  private final JdbcTemplate jdbc;

  public RollingAccuracyRepository(@Qualifier("clickhouseDataSource") DataSource clickhouse) {
    this.jdbc = new JdbcTemplate(clickhouse);
  }

  /**
   * Per-ET-game-day realized top-1 buckets for one of the two pitch-outcome heads over the trailing
   * {@code days} calendar days. The model-name allowlist is enforced here (not merely upstream)
   * because the SQL pins the y5 truth vocabulary - scoring any other family against {@code
   * description} would produce a plausible-looking 0%.
   */
  public List<RollingAccuracyBucket> pitchOutcomeDaily(String modelName, int days) {
    if (!PITCH_OUTCOME_MODELS.contains(modelName)) {
      throw new IllegalArgumentException(
          "pitchOutcomeDaily scores the y5 description vocabulary; got " + modelName);
    }
    return jdbc.query(PITCH_OUTCOME_SQL, BUCKET_MAPPER, modelName, days + 1, days - 1);
  }

  /** Per-ET-game-day realized top-1 buckets for pitch_type_pre (y7 fold truth). */
  public List<RollingAccuracyBucket> pitchTypeDaily(int days) {
    return jdbc.query(PITCH_TYPE_SQL, BUCKET_MAPPER, "pitch_type_pre", days + 1, days - 1);
  }
}
