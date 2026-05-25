package net.thebullpen.baseball.drift;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Bulk INSERTer + windowed reader for {@code drift_metrics} (V013, leaf 3c.1) on the analytical
 * (ClickHouse) DataSource. Same {@code @ConditionalOnBean(name = "clickhouseDataSource")} pattern
 * as {@link net.thebullpen.baseball.inference.PredictionLogWriter} — when ClickHouse isn't wired in
 * this env (dev without docker-compose), the bean is absent and the 3c.2–3c.5 batch jobs treat that
 * as "drift detection is off."
 *
 * <p>Active on both {@code api} (Ops dashboard reads via this repo) and {@code worker} (batch jobs
 * write) profiles.
 */
@Repository
@Profile({"api", "worker"})
@ConditionalOnBean(name = "clickhouseDataSource")
public class DriftMetricsRepository {

  private static final Logger log = LoggerFactory.getLogger(DriftMetricsRepository.class);

  private static final String INSERT_SQL =
      "INSERT INTO drift_metrics "
          + "(computed_at, model_name, model_version_id, metric_type, feature_or_segment,"
          + " metric_value, sample_size, window_start, window_end) "
          + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

  private static final String SELECT_ALL =
      "SELECT computed_at, model_name, model_version_id, metric_type, feature_or_segment,"
          + " metric_value, sample_size, window_start, window_end FROM drift_metrics";

  private final DataSource clickhouse;
  private final JdbcTemplate jdbc;

  public DriftMetricsRepository(@Qualifier("clickhouseDataSource") DataSource clickhouse) {
    this.clickhouse = clickhouse;
    this.jdbc = new JdbcTemplate(clickhouse);
  }

  /**
   * Bulk insert via JDBC {@code addBatch} — same pattern as PredictionLogWriter. Holds the
   * connection for the duration of the batch; flushes in one round-trip. Caller is responsible for
   * batching (the batch jobs accumulate metrics in memory then call this once per run).
   */
  public void insertBatch(List<DriftMetric> metrics) throws SQLException {
    if (metrics.isEmpty()) {
      return;
    }
    try (var conn = clickhouse.getConnection();
        PreparedStatement stmt = conn.prepareStatement(INSERT_SQL)) {
      for (DriftMetric m : metrics) {
        stmt.setTimestamp(1, Timestamp.from(m.computedAt()));
        stmt.setString(2, m.modelName());
        stmt.setLong(3, m.modelVersionId());
        stmt.setString(4, m.metricType().dbValue());
        stmt.setString(5, m.featureOrSegment());
        stmt.setDouble(6, m.metricValue());
        stmt.setLong(7, m.sampleSize());
        stmt.setTimestamp(8, Timestamp.from(m.windowStart()));
        stmt.setTimestamp(9, Timestamp.from(m.windowEnd()));
        stmt.addBatch();
      }
      stmt.executeBatch();
      log.debug("drift_metrics inserted size={}", metrics.size());
    }
  }

  /**
   * Find every row of {@code (modelName, metricType, featureOrSegment)} with {@code computed_at >=
   * now - window}, ordered newest-first. Used by the Ops dashboard's sparkline + by 3c.7's alert
   * threshold check.
   */
  public List<DriftMetric> findRecent(
      String modelName, MetricType metricType, String featureOrSegment, Duration window) {
    Instant cutoff = Instant.now().minus(window);
    return jdbc.query(
        SELECT_ALL
            + " WHERE model_name = ? AND metric_type = ? AND feature_or_segment = ?"
            + " AND computed_at >= ? ORDER BY computed_at DESC",
        DRIFT_METRIC_MAPPER,
        modelName,
        metricType.dbValue(),
        featureOrSegment,
        Timestamp.from(cutoff));
  }

  /** Latest row for one (modelName, metricType, featureOrSegment) triple — empty if none. */
  public Optional<DriftMetric> findLatest(
      String modelName, MetricType metricType, String featureOrSegment) {
    List<DriftMetric> hits =
        jdbc.query(
            SELECT_ALL
                + " WHERE model_name = ? AND metric_type = ? AND feature_or_segment = ?"
                + " ORDER BY computed_at DESC LIMIT 1",
            DRIFT_METRIC_MAPPER,
            modelName,
            metricType.dbValue(),
            featureOrSegment);
    return hits.isEmpty() ? Optional.empty() : Optional.of(hits.get(0));
  }

  /** All rows for one model — admin view + drift export. */
  public List<DriftMetric> findAllForModel(String modelName) {
    return jdbc.query(
        SELECT_ALL + " WHERE model_name = ? ORDER BY computed_at DESC",
        DRIFT_METRIC_MAPPER,
        modelName);
  }

  private static final RowMapper<DriftMetric> DRIFT_METRIC_MAPPER =
      (ResultSet rs, int n) ->
          new DriftMetric(
              rs.getTimestamp("computed_at").toInstant(),
              rs.getString("model_name"),
              rs.getLong("model_version_id"),
              MetricType.fromDbValue(rs.getString("metric_type")),
              rs.getString("feature_or_segment"),
              rs.getDouble("metric_value"),
              rs.getLong("sample_size"),
              rs.getTimestamp("window_start").toInstant(),
              rs.getTimestamp("window_end").toInstant());
}
