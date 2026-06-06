package net.thebullpen.baseball.inference;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Bulk INSERTer for prediction_log on the analytical (ClickHouse) DataSource.
 *
 * <p>Gated on a {@code @Qualifier("clickhouseDataSource")} bean — if the analytical DataSource
 * isn't wired in this environment (e.g. unit-test scope without Testcontainers), the bean is absent
 * and {@link AsyncPredictionLogger} flips to noop mode rather than crashing the app at startup.
 */
@Component
@Profile("api")
@ConditionalOnBean(name = "clickhouseDataSource")
public class PredictionLogWriter {

  private static final Logger log = LoggerFactory.getLogger(PredictionLogWriter.class);

  private static final String INSERT_SQL =
      "INSERT INTO prediction_log "
          + "(request_id, request_at, model_name, model_version, model_version_id, role, "
          + " feature_hash, features, prediction, latency_ms, correlation_id, "
          + " game_id, at_bat_index, pitch_number) "
          + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

  private final DataSource clickhouse;

  public PredictionLogWriter(
      @org.springframework.beans.factory.annotation.Qualifier("clickhouseDataSource")
          DataSource clickhouse) {
    this.clickhouse = clickhouse;
  }

  public void writeBatch(List<PredictionLogEvent> batch) throws SQLException {
    if (batch.isEmpty()) return;
    try (var conn = clickhouse.getConnection();
        PreparedStatement stmt = conn.prepareStatement(INSERT_SQL)) {
      for (PredictionLogEvent ev : batch) {
        stmt.setString(1, ev.requestId().toString());
        stmt.setTimestamp(2, Timestamp.from(ev.requestAt()));
        stmt.setString(3, ev.modelName());
        stmt.setString(4, ev.modelVersion());
        if (ev.modelVersionId() == null) {
          stmt.setNull(5, java.sql.Types.BIGINT);
        } else {
          stmt.setLong(5, ev.modelVersionId());
        }
        stmt.setString(6, ev.role().dbValue());
        stmt.setString(7, ev.featureHash());
        stmt.setString(8, ev.features());
        stmt.setString(9, ev.prediction());
        stmt.setFloat(10, ev.latencyMs());
        stmt.setString(11, ev.correlationId() == null ? "" : ev.correlationId());
        // Live-game truth-join key (issue #1 step 3); null for HTTP-path + shadow predictions.
        if (ev.gameId() == null) {
          stmt.setNull(12, java.sql.Types.BIGINT);
        } else {
          stmt.setLong(12, ev.gameId());
        }
        if (ev.atBatIndex() == null) {
          stmt.setNull(13, java.sql.Types.INTEGER);
        } else {
          stmt.setInt(13, ev.atBatIndex());
        }
        if (ev.pitchNumber() == null) {
          stmt.setNull(14, java.sql.Types.INTEGER);
        } else {
          stmt.setInt(14, ev.pitchNumber());
        }
        stmt.addBatch();
      }
      stmt.executeBatch();
      log.debug("prediction_log batch flushed size={}", batch.size());
    }
  }
}
