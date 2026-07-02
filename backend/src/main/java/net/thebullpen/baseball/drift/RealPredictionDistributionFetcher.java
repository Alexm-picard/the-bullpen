package net.thebullpen.baseball.drift;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Real {@link PredictionDistributionFetcher} (WS2-ii): pivots the per-class predicted probabilities
 * out of {@code prediction_log} for one model version over a time window, so {@link
 * net.thebullpen.baseball.drift.jobs.PsiPredictionJob} can run PSI between each class's live
 * distribution and its training-time reference - for the champion AND the SHADOW versions the C3
 * change now iterates.
 *
 * <p>Parses the {@code {"probabilities": {class -> p, ...}, "winner": ...}} payload that the pitch
 * predict path logs (PredictPitchController / LivePitchPredictor). Rows whose {@code prediction}
 * JSON carries no {@code probabilities} object - e.g. the batted-ball single-park {@code
 * {"prob_hr": x}} or the all-parks per-park payload - are skipped: those families need their own
 * per-class drift definition (a documented follow-up), and skipping is safer than coercing them
 * into garbage class samples. Empty result => the job writes no rows.
 *
 * <p>Gated on {@code bullpen.clickhouse.enabled} (same property as the datasource, deterministic
 * wiring) and {@link Primary} so it supersedes {@link StubPredictionDistributionFetcher} whenever
 * ClickHouse is configured; the stub remains the no-ClickHouse fallback.
 *
 * <p>Leakage-clean: a read of already-logged predictions inside an explicit {@code [windowStart,
 * windowEnd]} request_at window. No future data, no per-pitch cutoff surface.
 */
@Component
@Primary
@ConditionalOnProperty(name = "bullpen.clickhouse.enabled", havingValue = "true")
public class RealPredictionDistributionFetcher implements PredictionDistributionFetcher {

  private static final Logger log =
      LoggerFactory.getLogger(RealPredictionDistributionFetcher.class);

  // Window bounds are bound as epoch-millis longs and reconstructed server-side with
  // fromUnixTimestamp64Milli, NOT as bound java.sql.Timestamps. clickhouse-jdbc mishandles a
  // Timestamp param in a DateTime64 WHERE comparison (the existing CH queries all use the relative
  // `now() - INTERVAL ? DAY` form, never a bound absolute timestamp), so this keeps every bind a
  // String or a long.
  private static final String SELECT_PREDICTIONS =
      "SELECT prediction FROM prediction_log"
          + " WHERE model_name = ? AND model_version_id = ?"
          + "   AND request_at >= fromUnixTimestamp64Milli(?, 'UTC')"
          + "   AND request_at <= fromUnixTimestamp64Milli(?, 'UTC')";

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  public RealPredictionDistributionFetcher(
      @Qualifier("clickhouseDataSource") DataSource clickhouse, ObjectMapper objectMapper) {
    this.jdbc = new JdbcTemplate(clickhouse);
    this.objectMapper = objectMapper;
  }

  @Override
  public Map<String, List<Double>> fetchPerClassProbabilities(
      String modelName, long modelVersionId, Instant windowStart, Instant windowEnd) {
    List<String> rows =
        jdbc.query(
            SELECT_PREDICTIONS,
            (rs, n) -> rs.getString(1),
            modelName,
            modelVersionId,
            windowStart.toEpochMilli(),
            windowEnd.toEpochMilli());

    // Insertion-ordered so the class order is stable for logging / tests.
    Map<String, List<Double>> perClass = new LinkedHashMap<>();
    int skipped = 0;
    for (String json : rows) {
      JsonNode probs = probabilitiesOf(json);
      if (probs == null) {
        skipped++;
        continue;
      }
      for (Map.Entry<String, JsonNode> e : probs.properties()) {
        if (e.getValue().isNumber()) {
          perClass.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue().asDouble());
        }
      }
    }
    if (skipped > 0) {
      log.debug(
          "PredictionDistributionFetcher: {}/{} skipped {} row(s) with no 'probabilities' object"
              + " (non-pitch payload) over [{}, {}]",
          modelName,
          modelVersionId,
          skipped,
          windowStart,
          windowEnd);
    }
    return perClass;
  }

  /**
   * The {@code probabilities} object node, or null if the payload is unparseable / a non-pitch
   * shape. Instance rather than static so it parses with the injected Spring {@link ObjectMapper}.
   */
  JsonNode probabilitiesOf(String predictionJson) {
    if (predictionJson == null || predictionJson.isBlank()) {
      return null;
    }
    try {
      JsonNode probs = objectMapper.readTree(predictionJson).get("probabilities");
      return (probs != null && probs.isObject()) ? probs : null;
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      return null;
    }
  }
}
