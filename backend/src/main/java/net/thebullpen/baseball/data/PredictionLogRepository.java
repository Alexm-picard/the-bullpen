package net.thebullpen.baseball.data;

import java.util.List;
import javax.sql.DataSource;
import net.thebullpen.baseball.api.dto.LatencyStat;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Read-side aggregate for the ClickHouse {@code prediction_log} table.
 *
 * <p>The write path is {@link net.thebullpen.baseball.inference.PredictionLogWriter} (api profile,
 * bulk INSERT). This repository owns the analytical reads so SQL against {@code prediction_log}
 * stays encapsulated behind a typed API rather than leaking raw {@code JdbcTemplate} into jobs —
 * the reconciliation job ({@link net.thebullpen.baseball.registry.ReconciliationJob}) used to build
 * its own {@code JdbcTemplate} and inline the query.
 *
 * <p>{@code @ConditionalOnBean(clickhouseDataSource)} so dev profiles without ClickHouse still
 * boot.
 */
@Repository
@ConditionalOnBean(name = "clickhouseDataSource")
public class PredictionLogRepository {

  private static final String DISTINCT_SERVED_PAIRS =
      "SELECT DISTINCT model_name, model_version FROM prediction_log"
          + " WHERE request_at > now() - INTERVAL ? DAY";

  private static final String LATENCY_QUANTILES =
      "SELECT model_name, model_version, count() AS n,"
          + " quantile(0.5)(latency_ms)   AS p50,"
          + " quantile(0.95)(latency_ms)  AS p95,"
          + " quantile(0.99)(latency_ms)  AS p99,"
          + " quantile(0.999)(latency_ms) AS p999"
          + " FROM prediction_log"
          + " WHERE request_at > now() - INTERVAL ? DAY"
          + " GROUP BY model_name, model_version"
          + " ORDER BY model_name, model_version";

  private final JdbcTemplate jdbc;

  public PredictionLogRepository(@Qualifier("clickhouseDataSource") DataSource clickhouse) {
    this.jdbc = new JdbcTemplate(clickhouse);
  }

  /**
   * Every distinct {@code (model_name, model_version)} pair that produced a logged prediction
   * within the last {@code lookbackDays} days. Used by the weekly registry-reconciliation job to
   * find orphan ids in {@code prediction_log} that the registry doesn't know about.
   *
   * @return list of {@code [model_name, model_version]} pairs
   */
  public List<String[]> distinctServedModelVersions(int lookbackDays) {
    return jdbc.query(
        DISTINCT_SERVED_PAIRS,
        (rs, n) -> new String[] {rs.getString(1), rs.getString(2)},
        lookbackDays);
  }

  /**
   * Per-model serving-latency percentiles (p50 / p95 / p99, in ms) over the last {@code
   * lookbackDays} days, one row per {@code (model_name, model_version)} that logged a prediction.
   * Backs {@code GET /v1/ops/latency} — the Ops dashboard's fleet p99 column + Latency Detail
   * table. Empty list when no predictions fall in the window (the UI then shows its no-data state).
   */
  public List<LatencyStat> latencyQuantiles(int lookbackDays) {
    return jdbc.query(
        LATENCY_QUANTILES,
        (rs, n) ->
            new LatencyStat(
                rs.getString("model_name"),
                rs.getString("model_version"),
                rs.getLong("n"),
                rs.getDouble("p50"),
                rs.getDouble("p95"),
                rs.getDouble("p99"),
                rs.getDouble("p999")),
        lookbackDays);
  }
}
