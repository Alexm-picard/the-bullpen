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
 * <p>Understands two payload shapes:
 *
 * <ol>
 *   <li><b>Pitch-family</b>: {@code {"probabilities": {class -> p, ...}, "winner": ...}} — the
 *       classes and probabilities are read directly.
 *   <li><b>Batted-ball all-parks</b>: {@code {"NYY": [p_out, p_1b, p_2b, p_3b, p_hr], ...}} — a
 *       park-keyed map of positional arrays. The REQUESTED park's 5-vector is extracted via the
 *       {@code features} column's {@code parkId} field, giving the prediction the user actually
 *       received (the other 29 are counterfactual display). The positional array is zipped with
 *       {@link #BATTED_BALL_OUTCOME_CLASSES} to produce the class-keyed map the PSI job expects.
 * </ol>
 *
 * <p>Any payload matching neither shape is skipped and counted (fail-loud on parse miss, not silent
 * drop). A model whose payload is an unrecognized third shape will produce a WARN with its skip
 * count, so a new payload format cannot silently starve the drift detector.
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

  /**
   * Batted-ball outcome class names in positional order, matching Python's {@code OUTCOME_NAMES} in
   * {@code battedball/features_shared.py} and the calibrator's {@code outcome_order}. To
   * regenerate:
   *
   * <pre>
   *   uv run python -c \
   *     "from bullpen_training.battedball.features_shared import OUTCOME_NAMES; print(list(OUTCOME_NAMES))"
   * </pre>
   */
  static final List<String> BATTED_BALL_OUTCOME_CLASSES = List.of("out", "1b", "2b", "3b", "hr");

  // Window bounds are bound as epoch-millis longs and reconstructed server-side with
  // fromUnixTimestamp64Milli, NOT as bound java.sql.Timestamps. clickhouse-jdbc mishandles a
  // Timestamp param in a DateTime64 WHERE comparison (the existing CH queries all use the relative
  // `now() - INTERVAL ? DAY` form, never a bound absolute timestamp), so this keeps every bind a
  // String or a long.
  private static final String SELECT_PREDICTIONS =
      "SELECT prediction, features, game_id FROM prediction_log"
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
    List<Object[]> rows =
        jdbc.query(
            SELECT_PREDICTIONS,
            (rs, n) -> new Object[] {rs.getString(1), rs.getString(2), rs.getObject(3)},
            modelName,
            modelVersionId,
            windowStart.toEpochMilli(),
            windowEnd.toEpochMilli());

    Map<String, List<Double>> perClass = new LinkedHashMap<>();
    int skipped = 0;
    int legacyUnresolved = 0;
    for (Object[] row : rows) {
      String predictionJson = (String) row[0];
      String featuresJson = (String) row[1];
      Long gameId = row[2] instanceof Number n ? n.longValue() : null;

      // Shape 1: pitch-family {"probabilities": {class -> p}}
      JsonNode probs = pitchProbabilitiesOf(predictionJson);
      if (probs != null) {
        for (Map.Entry<String, JsonNode> e : probs.properties()) {
          if (e.getValue().isNumber()) {
            perClass
                .computeIfAbsent(e.getKey(), k -> new ArrayList<>())
                .add(e.getValue().asDouble());
          }
        }
        continue;
      }

      // Shape 2: batted-ball park-keyed map {"NYY": [p0, p1, ...], ...}
      int result = tryBattedBallParkExtraction(predictionJson, featuresJson, gameId, perClass);
      if (result == 1) {
        continue;
      }
      if (result == -1) {
        legacyUnresolved++;
        continue;
      }

      skipped++;
    }
    if (skipped > 0) {
      log.warn(
          "PredictionDistributionFetcher: {}/{} skipped {} row(s) matching neither pitch nor"
              + " batted-ball payload shape over [{}, {}]",
          modelName,
          modelVersionId,
          skipped,
          windowStart,
          windowEnd);
    }
    if (legacyUnresolved > 0) {
      log.info(
          "PredictionDistributionFetcher: {}/{} {} pre-enrichment batted-ball row(s) with no"
              + " parkContext (will age out as new rows carry it)",
          modelName,
          modelVersionId,
          legacyUnresolved);
    }
    return perClass;
  }

  /**
   * The {@code probabilities} object node from a pitch-family payload, or null if the payload is
   * unparseable or not a pitch shape.
   */
  JsonNode pitchProbabilitiesOf(String predictionJson) {
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

  /**
   * Try to extract the requested park's 5-class outcome vector from a batted-ball all-parks
   * payload.
   *
   * @return 1 if extraction succeeded, 0 if the payload is not a park-keyed map, -1 if it IS a
   *     park-keyed map but the park could not be resolved (pre-enrichment row or explorer call)
   */
  int tryBattedBallParkExtraction(
      String predictionJson, String featuresJson, Long gameId, Map<String, List<Double>> perClass) {
    if (predictionJson == null || predictionJson.isBlank()) {
      return 0;
    }
    try {
      JsonNode prediction = objectMapper.readTree(predictionJson);
      if (!prediction.isObject() || prediction.isEmpty()) {
        return 0;
      }
      JsonNode firstValue = prediction.elements().next();
      if (!firstValue.isArray()) {
        return 0;
      }

      String parkId = extractParkContext(featuresJson, gameId);
      if (parkId == null || parkId.isEmpty()) {
        return -1;
      }
      JsonNode parkArray = prediction.get(parkId);
      if (parkArray == null || !parkArray.isArray()) {
        log.debug(
            "PredictionDistributionFetcher: batted-ball prediction has no entry for park {}",
            parkId);
        return -1;
      }
      if (parkArray.size() != BATTED_BALL_OUTCOME_CLASSES.size()) {
        log.warn(
            "PredictionDistributionFetcher: park {} array size {} != expected {}; skipping",
            parkId,
            parkArray.size(),
            BATTED_BALL_OUTCOME_CLASSES.size());
        return -1;
      }
      for (int i = 0; i < BATTED_BALL_OUTCOME_CLASSES.size(); i++) {
        String className = BATTED_BALL_OUTCOME_CLASSES.get(i);
        perClass
            .computeIfAbsent(className, k -> new ArrayList<>())
            .add(parkArray.get(i).asDouble());
      }
      return 1;
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      return 0;
    }
  }

  /**
   * Resolve the park for a batted-ball prediction row. Tries two sources:
   *
   * <ol>
   *   <li>{@code parkContext} in the enriched features (post-enrichment rows). Empty string = an
   *       explorer call with no real park (skip by design).
   *   <li>Fallback for pre-enrichment rows: resolve {@code game_id} against {@code pitches_live} to
   *       get the home team. This join partner has a 14-day TTL, so it works only for recent rows
   *       and will age out as enriched rows replace them.
   * </ol>
   */
  private String extractParkContext(String featuresJson, Long gameId) {
    if (featuresJson != null && !featuresJson.isBlank()) {
      try {
        JsonNode features = objectMapper.readTree(featuresJson);
        JsonNode parkCtx = features.get("parkContext");
        if (parkCtx != null) {
          String park = parkCtx.asText("");
          return park;
        }
      } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
        // fall through to game_id fallback
      }
    }
    // Legacy fallback: resolve game_id -> home_team via pitches_live (TTL-bounded)
    if (gameId != null) {
      try {
        List<String> parks =
            jdbc.query(
                "SELECT DISTINCT home_team FROM pitches_live WHERE game_id = ? LIMIT 1",
                (rs, n) -> rs.getString(1),
                gameId);
        if (!parks.isEmpty()) {
          return parks.getFirst();
        }
      } catch (Exception e) {
        log.debug("PredictionDistributionFetcher: game_id fallback failed for {}", gameId, e);
      }
    }
    return null;
  }
}
