package net.thebullpen.baseball.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * S4 - the analytical ClickHouse access used a raw {@code ClickHouseDataSource} (a new connection
 * per call, no pool, no timeouts). This unit test pins the fix: {@link ClickHouseConfig} now
 * returns a bounded HikariCP pool (connection reuse + a bounded wait for a free connection) and
 * refuses a blank password. The "slow ClickHouse fails fast" behavior driven by the client {@code
 * socket_timeout} is covered by the Docker-gated {@code ClickHouseTimeoutIT}.
 *
 * <p>Pure unit test - no ClickHouse contact. {@code initializationFailTimeout(-1)} in the bean
 * means constructing the {@link HikariDataSource} does not open a connection, so this runs without
 * Docker.
 */
class ClickHouseConfigPoolingTest {

  private static ClickHouseConfig configWithDefaults(String password) {
    ClickHouseConfig cfg = new ClickHouseConfig();
    ReflectionTestUtils.setField(cfg, "url", "jdbc:ch:http://localhost:8123/default");
    ReflectionTestUtils.setField(cfg, "user", "default");
    ReflectionTestUtils.setField(cfg, "password", password);
    ReflectionTestUtils.setField(cfg, "socketTimeoutMs", 30_000);
    ReflectionTestUtils.setField(cfg, "connectTimeoutMs", 10_000);
    ReflectionTestUtils.setField(cfg, "poolMaxSize", 8);
    ReflectionTestUtils.setField(cfg, "poolConnectionTimeoutMs", 3_000L);
    ReflectionTestUtils.setField(cfg, "poolValidationTimeoutMs", 2_000L);
    ReflectionTestUtils.setField(cfg, "poolMaxLifetimeMs", 1_800_000L);
    return cfg;
  }

  @Test
  void wrapsClickHouseInABoundedHikariPool() throws Exception {
    DataSource ds = configWithDefaults("test").clickhouseDataSource();

    assertThat(ds).isInstanceOf(HikariDataSource.class);
    try (HikariDataSource hikari = (HikariDataSource) ds) {
      assertThat(hikari.getPoolName()).isEqualTo("clickhouse-pool");
      assertThat(hikari.getMaximumPoolSize()).isEqualTo(8);
      assertThat(hikari.getConnectionTimeout()).isEqualTo(3_000L);
      assertThat(hikari.getValidationTimeout()).isEqualTo(2_000L);
    }
  }

  @Test
  void rejectsBlankPassword() {
    ClickHouseConfig cfg = configWithDefaults("");
    assertThatThrownBy(cfg::clickhouseDataSource).isInstanceOf(IllegalStateException.class);
  }
}
