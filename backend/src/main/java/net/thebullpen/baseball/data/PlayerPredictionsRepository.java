package net.thebullpen.baseball.data;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import net.thebullpen.baseball.api.dto.PlayerPredictionRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Reader over {@code prediction_log} (V004) for the player profile page (leaf 4b.2).
 *
 * <p>Selects rows whose JSON-encoded {@code features} blob carries this player either as the
 * pitcher ({@code pitcherId}) or batter ({@code batterId}). The JSON path uses ClickHouse's {@code
 * JSONExtractInt} against camelCase keys because the Jackson-serialized Java record components
 * ({@code PitchRequest.pitcherId} / {@code batterId}) use those names — confirmed by {@code
 * PredictPitchController.serializeFeatures()}.
 *
 * <p>The {@code prediction} column is also JSON: parsed in Java (cheap, runs at most {@code limit}
 * times) so the wire format stays small ({@code winnerClass} + {@code winnerProb}) and the UI
 * doesn't have to re-parse.
 *
 * <p>Truth-joining to {@code pitches} is intentionally deferred — see {@link PlayerPredictionRow}'s
 * javadoc. {@code observedOutcome} + {@code agreed} return null today.
 */
@Repository
@Profile("api")
@ConditionalOnBean(name = "clickhouseDataSource")
public class PlayerPredictionsRepository {

  private static final Logger log = LoggerFactory.getLogger(PlayerPredictionsRepository.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final String FIND_RECENT_SQL =
      "SELECT request_at, model_name, model_version, role, prediction"
          + " FROM prediction_log"
          + " WHERE JSONExtractInt(features, 'pitcherId') = ?"
          + "    OR JSONExtractInt(features, 'batterId') = ?"
          + " ORDER BY request_at DESC"
          + " LIMIT ?";

  private final JdbcTemplate jdbc;

  public PlayerPredictionsRepository(@Qualifier("clickhouseDataSource") DataSource clickhouse) {
    this.jdbc = new JdbcTemplate(clickhouse);
  }

  /** Up to {@code limit} most-recent prediction rows involving this player. Empty if none. */
  public List<PlayerPredictionRow> findRecentForPlayer(long playerId, int limit) {
    return jdbc.query(FIND_RECENT_SQL, playerRowMapper(), playerId, playerId, limit);
  }

  private static RowMapper<PlayerPredictionRow> playerRowMapper() {
    return (ResultSet rs, int n) -> {
      String predJson = rs.getString("prediction");
      WinnerSummary winner = parseWinner(predJson);
      return new PlayerPredictionRow(
          rs.getTimestamp("request_at").toInstant(),
          rs.getString("model_name"),
          rs.getString("model_version"),
          rs.getString("role"),
          winner.cls,
          winner.prob,
          null,
          null);
    };
  }

  /**
   * Extract {@code winner} + {@code probabilities[winner]} from the JSON-encoded prediction.
   * Returns nulls on any parse failure — the JSON shape is stable today but defensive code keeps
   * historical rows readable across schema drift.
   */
  private static WinnerSummary parseWinner(String predJson) {
    if (predJson == null || predJson.isBlank()) {
      return WinnerSummary.EMPTY;
    }
    try {
      JsonNode root = MAPPER.readTree(predJson);
      JsonNode winnerNode = root.path("winner");
      JsonNode probs = root.path("probabilities");
      if (winnerNode.isTextual() && probs.isObject()) {
        String cls = winnerNode.asText();
        JsonNode p = probs.path(cls);
        Double prob = p.isNumber() ? p.doubleValue() : null;
        return new WinnerSummary(cls, prob);
      }
      // Single-prob payloads (toy batted-ball: {"probHr": 0.87}) — surface the lone class as
      // winner.
      if (probs.isMissingNode()) {
        for (Map.Entry<String, JsonNode> entry : root.properties()) {
          if (entry.getValue().isNumber()) {
            return new WinnerSummary(entry.getKey(), entry.getValue().doubleValue());
          }
        }
      }
    } catch (JsonProcessingException ex) {
      log.debug("could not parse prediction JSON, returning empty winner", ex);
    }
    return WinnerSummary.EMPTY;
  }

  private record WinnerSummary(String cls, Double prob) {
    static final WinnerSummary EMPTY = new WinnerSummary(null, null);
  }
}
