package net.thebullpen.baseball.data;

import java.util.List;
import javax.sql.DataSource;
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
}
