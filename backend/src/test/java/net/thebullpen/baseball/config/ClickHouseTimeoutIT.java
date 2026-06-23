package net.thebullpen.baseball.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * S4 - proves the client {@code socket_timeout} makes a stuck ClickHouse query fail fast instead of
 * hanging the calling thread indefinitely. Runs a server-side {@code SELECT sleep(3)} against a
 * real ClickHouse container with a 1s socket timeout and asserts the read aborts well before the 3s
 * sleep completes.
 *
 * <p>Docker-gated like the other ClickHouse ITs ({@code -Dbullpen.it.docker=true}, i.e. CI).
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

  private static DataSource shortSocketTimeoutDataSource() throws Exception {
    ClickHouseConfig cfg = new ClickHouseConfig();
    ReflectionTestUtils.setField(cfg, "url", CH.getJdbcUrl());
    ReflectionTestUtils.setField(cfg, "user", CH.getUsername());
    ReflectionTestUtils.setField(cfg, "password", CH.getPassword());
    ReflectionTestUtils.setField(cfg, "socketTimeoutMs", 1_000);
    ReflectionTestUtils.setField(cfg, "connectTimeoutMs", 5_000);
    ReflectionTestUtils.setField(cfg, "poolMaxSize", 2);
    ReflectionTestUtils.setField(cfg, "poolConnectionTimeoutMs", 3_000L);
    ReflectionTestUtils.setField(cfg, "poolValidationTimeoutMs", 2_000L);
    ReflectionTestUtils.setField(cfg, "poolMaxLifetimeMs", 1_800_000L);
    return cfg.clickhouseDataSource();
  }

  @Test
  void slowQueryAbortsBeforeItWouldComplete() throws Exception {
    try (HikariDataSource ds = (HikariDataSource) shortSocketTimeoutDataSource()) {
      long startNanos = System.nanoTime();
      assertThatThrownBy(
              () -> {
                try (Connection conn = ds.getConnection();
                    Statement st = conn.createStatement()) {
                  // sleep(3) blocks the response for 3s server-side; the 1s socket_timeout must
                  // fire.
                  st.executeQuery("SELECT sleep(3)");
                }
              })
          .as("a 1s socket_timeout must abort a 3s server-side sleep")
          .isInstanceOf(Exception.class);
      long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
      assertThat(elapsedMs)
          .as("must fail fast on the ~1s timeout, not wait out the full 3s sleep")
          .isLessThan(2_500);
    }
  }
}
