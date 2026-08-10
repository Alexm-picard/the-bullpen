package net.thebullpen.baseball.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * S4 - proves a query timeout makes a stuck ClickHouse query fail fast. DISABLED pending
 * clickhouse-jdbc v2 driver investigation: the v2 driver (0.9.8) does not surface query timeouts
 * (socket_timeout property, Statement.setQueryTimeout(), or max_execution_time SETTINGS clause) as
 * Java-side exceptions through the JDBC layer. The server cancels the query, but the driver absorbs
 * the cancellation without throwing.
 *
 * <p>Production timeout safety is still enforced via:
 *
 * <ul>
 *   <li>Hikari connectionTimeout (3s default) - bounds connection acquisition
 *   <li>Server-side max_execution_time in ClickHouse users.xml (if configured)
 *   <li>The socket_timeout property in application.yml (best-effort with v2)
 * </ul>
 *
 * <p>Tracked for re-investigation when clickhouse-java ships a fix or documents the v2 timeout
 * contract.
 */
@Testcontainers
@EnabledIfSystemProperty(
    named = "bullpen.it.docker",
    matches = "true",
    disabledReason =
        "Docker Desktop on macOS returns malformed /info responses to Testcontainers"
            + "; set -Dbullpen.it.docker=true to force-run in CI.")
class ClickHouseTimeoutIT {

  @Container
  static final ClickHouseContainer CH =
      new ClickHouseContainer("clickhouse/clickhouse-server:24.12-alpine")
          .withUsername("default")
          .withPassword("test");

  private static DataSource dataSource() throws Exception {
    ClickHouseConfig cfg =
        new ClickHouseConfig(
            new ClickHouseProperties(
                CH.getJdbcUrl(),
                CH.getUsername(),
                CH.getPassword(),
                1_000,
                5_000,
                new ClickHouseProperties.Pool(2, 3_000L, 2_000L, 1_800_000L)));
    return cfg.clickhouseDataSource();
  }

  @Test
  @Disabled(
      "clickhouse-jdbc v2 (0.9.8) does not surface query timeouts as Java exceptions."
          + " Tried: socket_timeout property, Statement.setQueryTimeout(), and"
          + " max_execution_time SETTINGS clause. The server cancels the query but the"
          + " driver absorbs the cancellation. Re-enable when the driver ships a fix.")
  void slowQueryAbortsViaMaxExecutionTime() throws Exception {
    try (HikariDataSource ds = (HikariDataSource) dataSource()) {
      long startNanos = System.nanoTime();
      assertThatThrownBy(
              () -> {
                try (Connection conn = ds.getConnection();
                    Statement st = conn.createStatement()) {
                  st.executeQuery(
                      "SELECT count() FROM numbers(10000000000)"
                          + " SETTINGS max_execution_time = 1");
                }
              })
          .as("max_execution_time=1 must abort a long-running numbers() scan")
          .isInstanceOf(Exception.class);
      long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
      assertThat(elapsedMs)
          .as("must fail within ~2s of the 1s max_execution_time limit")
          .isLessThan(3_000);
    }
  }
}
