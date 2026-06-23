package net.thebullpen.baseball.data;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import net.thebullpen.baseball.api.dto.CalibrationBin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Reliability-diagram bin builder for a single player (leaf 4b.3).
 *
 * <p>For now the binning runs over {@code prediction_log} alone — pulls every prediction made
 * for/against the player + its winner probability, then assigns each row to one of {@link
 * #BIN_COUNT} equal-width bins. The {@code actual} (empirical outcome frequency) column is null
 * until truth-joining lands in a follow-up leaf; this matches the {@link
 * net.thebullpen.baseball.data.PlayerPredictionsRepository} pattern of "surface the seam, light it
 * up when prediction_log has paired truth data."
 *
 * <p>Bin doing it in Java instead of pushing into ClickHouse keeps the SQL simple (one column
 * pulled, parsed in stream) and the per-call cost trivial — a single player generates at most
 * O(predictions-per-player) rows, capped by the {@code limit} on the query.
 */
@Repository
@Profile("api")
@ConditionalOnBean(name = "clickhouseDataSource")
public class CalibrationRepository {

  /** Number of equal-width [0, 1] bins on the predicted-probability axis. */
  public static final int BIN_COUNT = 10;

  private static final Logger log = LoggerFactory.getLogger(CalibrationRepository.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  /**
   * Known single-probability payload keys (the batted-ball HR head logs {@code {"prob_hr": x}}).
   * Looked up by name so a sibling numeric field can't be misread as the winner probability
   * (DEF-M4).
   */
  private static final List<String> SINGLE_PROB_KEYS = List.of("prob_hr", "probHr", "prob");

  /**
   * Pull predictions for this player + this model — winners only. Capped at a reasonable upper
   * bound to keep per-request cost predictable; the 5000 ceiling is well past the "<50 predictions"
   * threshold the UI uses to decide to render at all.
   */
  private static final String SELECT_PREDICTIONS_SQL =
      "SELECT prediction"
          + " FROM prediction_log"
          + " WHERE model_name = ?"
          + " AND (JSONExtractInt(features, 'pitcherId') = ?"
          + "      OR JSONExtractInt(features, 'batterId') = ?)"
          + " ORDER BY request_at DESC"
          + " LIMIT 5000";

  private final JdbcTemplate jdbc;

  public CalibrationRepository(@Qualifier("clickhouseDataSource") DataSource clickhouse) {
    this.jdbc = new JdbcTemplate(clickhouse);
  }

  /**
   * Build up to {@link #BIN_COUNT} reliability bins for one player + one model. Empty list when no
   * predictions exist (UI shows the "insufficient data" placeholder).
   */
  public List<CalibrationBin> computePlayerBins(String modelName, long playerId) {
    List<Double> winnerProbs =
        jdbc.query(SELECT_PREDICTIONS_SQL, predictionProbMapper(), modelName, playerId, playerId);

    if (winnerProbs.isEmpty()) {
      return List.of();
    }

    long[] counts = new long[BIN_COUNT];
    double[] sumPredicted = new double[BIN_COUNT];
    for (Double p : winnerProbs) {
      if (p == null || Double.isNaN(p)) continue;
      int idx = binIndexFor(p);
      counts[idx]++;
      sumPredicted[idx] += p;
    }

    List<CalibrationBin> bins = new ArrayList<>(BIN_COUNT);
    for (int i = 0; i < BIN_COUNT; i++) {
      if (counts[i] == 0) continue;
      double binStart = i / (double) BIN_COUNT;
      double binEnd = (i + 1) / (double) BIN_COUNT;
      double predicted = sumPredicted[i] / counts[i];
      // No truth-join behind this endpoint yet (it bins predicted probabilities from prediction_log
      // only), so `actual` is genuinely absent: emit null. Earlier this surfaced the bin MIDPOINT
      // as
      // a placeholder, which the reliability diagram then plotted as a perfect on-diagonal point -
      // a fabricated "perfectly calibrated" lie over un-truth-joined data. null serializes cleanly
      // to JSON (no NaN problem) and the UI renders it as a predicted-only view.
      bins.add(new CalibrationBin(binStart, binEnd, predicted, null, counts[i]));
    }
    return bins;
  }

  /** Map p ∈ [0, 1] to bin index ∈ [0, BIN_COUNT − 1]. p == 1.0 lands in the last bin. */
  static int binIndexFor(double p) {
    if (Double.isNaN(p)) return 0;
    if (p >= 1.0) return BIN_COUNT - 1;
    if (p <= 0.0) return 0;
    return (int) Math.floor(p * BIN_COUNT);
  }

  private static RowMapper<Double> predictionProbMapper() {
    return (ResultSet rs, int n) -> parseWinnerProb(rs.getString("prediction"));
  }

  /** Parse the winner probability out of the JSON-encoded prediction; null on any failure. */
  static Double parseWinnerProb(String predJson) {
    if (predJson == null || predJson.isBlank()) return null;
    try {
      JsonNode root = MAPPER.readTree(predJson);
      JsonNode winner = root.path("winner");
      JsonNode probs = root.path("probabilities");
      if (winner.isTextual() && probs.isObject()) {
        JsonNode p = probs.path(winner.asText());
        if (p.isNumber()) return p.doubleValue();
      }
      // Single-prob payloads like {"prob_hr": 0.87}. Look the probability up by a KNOWN key, not
      // "first numeric field" - a sibling numeric (a version, a latency_ms) ahead of the prob in
      // the JSON would otherwise be misread as the winner probability (DEF-M4).
      if (probs.isMissingNode()) {
        for (String key : SINGLE_PROB_KEYS) {
          JsonNode p = root.path(key);
          if (p.isNumber()) return p.doubleValue();
        }
      }
    } catch (JsonProcessingException ex) {
      log.debug("could not parse prediction JSON for calibration bin", ex);
    }
    return null;
  }
}
