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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Bulk INSERTer + windowed reader for {@code drift_metrics} (V013, leaf 3c.1) on the analytical
 * (ClickHouse) DataSource.
 *
 * <p>Gated on {@code @ConditionalOnProperty("bullpen.clickhouse.enabled")} — the SAME condition
 * {@link net.thebullpen.baseball.config.ClickHouseConfig} uses for the {@code clickhouseDataSource}
 * bean, so the data source and this repo are created together deterministically. This replaced an
 * earlier {@code @ConditionalOnBean(name = "clickhouseDataSource")}, which is order-sensitive
 * (Spring warns against it outside auto-config): under the {@code worker} profile it could evaluate
 * before the data source was registered, leaving this bean absent — which crash-looped the worker
 * for ~4 days post-2026-05-31 because the {@code @Profile("worker")} drift jobs (CalibrationJob,
 * PsiFeatureJob, PsiPredictionJob, WeeklySegmentJob) hard-require it. Keying both on the property
 * removes the ordering dependency.
 *
 * <p>Active on both {@code api} (Ops dashboard reads via this repo, tolerantly — {@code
 * OpsController} injects it {@code required=false}) and {@code worker} (drift jobs write, hard
 * dependency) profiles. When ClickHouse is disabled the property is false, neither this repo nor
 * the worker drift jobs are wired, and "drift detection is off" — but in prod the worker REQUIRES
 * {@code bullpen.clickhouse.enabled=true}.
 */
@Repository
@Profile({"api", "worker"})
@ConditionalOnProperty(name = "bullpen.clickhouse.enabled", havingValue = "true")
public class DriftMetricsRepository {

  private static final Logger log = LoggerFactory.getLogger(DriftMetricsRepository.class);

  private static final String INSERT_SQL =
      "INSERT INTO drift_metrics "
          + "(computed_at, model_name, model_version_id, metric_type, feature_or_segment,"
          + " metric_value, sample_size, window_start, window_end, tag) "
          + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

  private static final String SELECT_ALL =
      "SELECT computed_at, model_name, model_version_id, metric_type, feature_or_segment,"
          + " metric_value, sample_size, window_start, window_end FROM drift_metrics";

  private final DataSource clickhouse;
  private final JdbcTemplate jdbc;
  private final String defaultTag;

  public DriftMetricsRepository(
      @Qualifier("clickhouseDataSource") DataSource clickhouse,
      // The [175] drill window switch: '' (the default) in normal operation, so every
      // production job's rows land untagged. The box exports BULLPEN_DRIFT_TAG (e.g.
      // "induced-drill-2026-07") ONLY for the E-2 induced window, which tags every drift
      // job's output at this single choke point with zero job-code changes - then unsets
      // it. Exclusion predicate for organic baselines: WHERE tag = ''.
      @Value("${bullpen.drift.tag:}") String defaultTag) {
    this.clickhouse = clickhouse;
    this.jdbc = new JdbcTemplate(clickhouse);
    this.defaultTag = defaultTag == null ? "" : defaultTag;
    // Self-announcing: a lingering BULLPEN_DRIFT_TAG after a drill would silently tag
    // every ORGANIC row and exclude it from WHERE tag = '' baselines - the inverse of
    // the contamination [175] guards against. Make it impossible to miss in the logs;
    // the drill's closing step is unset + worker restart.
    if (!this.defaultTag.isEmpty()) {
      log.warn(
          "drift_metrics TAGGING ACTIVE: every drift row this process writes carries"
              + " tag='{}' (bullpen.drift.tag). Correct DURING an induced-drill window"
              + " ([175]); if a drill is not running, unset BULLPEN_DRIFT_TAG and restart"
              + " the worker.",
          this.defaultTag);
    }
  }

  /**
   * Bulk insert via JDBC {@code addBatch} - same pattern as PredictionLogWriter. Holds the
   * connection for the duration of the batch; flushes in one round-trip. Caller is responsible for
   * batching (the batch jobs accumulate metrics in memory then call this once per run).
   *
   * <p>Rows land with the configured {@code bullpen.drift.tag} (V027, decision [175]), which is ''
   * in normal operation: '' marks live/organic rows, which every production drift job emits via
   * this overload. During the E-2 induced window the box sets {@code BULLPEN_DRIFT_TAG} so all four
   * jobs' rows are tagged at this choke point; {@link #insertBatch(List, String)} is the
   * explicit-tag variant for direct callers. The natural-baseline exclusion predicate is {@code
   * WHERE tag = ''}. The paired {@code prediction_log} side carries no tag column - the drill
   * scopes its synthetic prediction rows via the existing {@code correlation_id} column with a
   * {@code drill:} prefix (no schema change).
   */
  public void insertBatch(List<DriftMetric> metrics) throws SQLException {
    insertBatch(metrics, defaultTag);
  }

  /**
   * Tag-carrying variant of {@link #insertBatch(List)}: every row in the batch is written with the
   * given {@code tag}. Used only to scope synthetic/drill windows (V027, decision [175]) so they
   * are excludable from an organic baseline via {@code WHERE tag = ''}; production writers call the
   * no-tag overload, which defaults the column to ''. A null {@code tag} is normalised to ''.
   */
  public void insertBatch(List<DriftMetric> metrics, String tag) throws SQLException {
    if (metrics.isEmpty()) {
      return;
    }
    String rowTag = tag == null ? "" : tag;
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
        stmt.setString(10, rowTag);
        stmt.addBatch();
      }
      stmt.executeBatch();
      log.debug("drift_metrics inserted size={} tag={}", metrics.size(), rowTag);
    }
  }

  /**
   * Find every row of {@code (modelName, metricType, featureOrSegment)} with {@code computed_at >=
   * now - window}, ordered newest-first. Used by the Ops dashboard's sparkline + by 3c.7's alert
   * threshold check.
   */
  public List<DriftMetric> findRecent(
      String modelName, MetricType metricType, String featureOrSegment, Duration window) {
    // Bind the cutoff as epoch SECONDS through ClickHouse's fromUnixTimestamp() rather than as a
    // JDBC Timestamp: clickhouse-jdbc inlines a Timestamp param into the SQL *unquoted*
    // (`... >= 2026-05-31 01:32:23.8...`), which is a syntax error (Code 62). A numeric param
    // inlines cleanly, fromUnixTimestamp() yields a DateTime (exact type match for computed_at),
    // and epoch is timezone-unambiguous. (Surfaced once DriftMetricsRepositoryIT ran in CI.)
    long cutoffEpochSeconds = Instant.now().minus(window).getEpochSecond();
    return jdbc.query(
        SELECT_ALL
            + " WHERE model_name = ? AND metric_type = ? AND feature_or_segment = ?"
            + " AND computed_at >= fromUnixTimestamp(?) ORDER BY computed_at DESC",
        DRIFT_METRIC_MAPPER,
        modelName,
        metricType.dbValue(),
        featureOrSegment,
        cutoffEpochSeconds);
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
