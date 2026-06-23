package net.thebullpen.baseball.config;

import com.clickhouse.jdbc.ClickHouseDataSource;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.SQLException;
import java.util.Properties;
import javax.sql.DataSource;
import net.thebullpen.baseball.inference.ClickHouseMigrationRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Analytical DataSource wiring (Phase 1.7).
 *
 * <p>Gated on {@code bullpen.clickhouse.enabled=true} (default {@code true} when the property is
 * present; absent means dev/test scope without ClickHouse and {@link
 * net.thebullpen.baseball.inference.PredictionLogWriter} won't materialize).
 */
@Configuration
@Profile({"api", "worker"})
@ConditionalOnProperty(
    name = "bullpen.clickhouse.enabled",
    havingValue = "true",
    matchIfMissing = false)
public class ClickHouseConfig {

  private static final Logger log = LoggerFactory.getLogger(ClickHouseConfig.class);

  @Value("${bullpen.clickhouse.url:jdbc:ch:http://localhost:8123/default}")
  private String url;

  @Value("${bullpen.clickhouse.user:default}")
  private String user;

  @Value("${bullpen.clickhouse.password:}")
  private String password;

  // S4 - client-side timeouts (ms) so a slow/stuck ClickHouse fails fast instead of hanging the
  // flusher / drift / inference-adjacent threads indefinitely. These are clickhouse-jdbc client
  // options: socket_timeout bounds a single response read, connection_timeout bounds connect.
  @Value("${bullpen.clickhouse.socket-timeout-ms:30000}")
  private int socketTimeoutMs;

  @Value("${bullpen.clickhouse.connect-timeout-ms:10000}")
  private int connectTimeoutMs;

  // S4 - HikariCP pool around the raw ClickHouseDataSource (which opened/closed a connection per
  // call before). Bounded pool + bounded wait for a free connection so a saturated pool also fails
  // fast rather than blocking forever.
  @Value("${bullpen.clickhouse.pool.max-size:8}")
  private int poolMaxSize;

  @Value("${bullpen.clickhouse.pool.connection-timeout-ms:3000}")
  private long poolConnectionTimeoutMs;

  @Value("${bullpen.clickhouse.pool.validation-timeout-ms:2000}")
  private long poolValidationTimeoutMs;

  @Value("${bullpen.clickhouse.pool.max-lifetime-ms:1800000}")
  private long poolMaxLifetimeMs;

  @Bean(name = "clickhouseDataSource")
  public DataSource clickhouseDataSource() throws SQLException {
    if (password == null || password.isBlank()) {
      throw new IllegalStateException(
          "bullpen.clickhouse.password is unset - set BULLPEN_CLICKHOUSE_PASSWORD in the runtime"
              + " environment (or override @DynamicPropertySource in tests). Refusing to fall back"
              + " to a known default credential.");
    }
    Properties props = new Properties();
    props.setProperty("user", user);
    props.setProperty("password", password);
    props.setProperty("socket_timeout", Integer.toString(socketTimeoutMs));
    props.setProperty("connection_timeout", Integer.toString(connectTimeoutMs));
    ClickHouseDataSource chDataSource = new ClickHouseDataSource(url, props);

    HikariConfig hikari = new HikariConfig();
    hikari.setDataSource(chDataSource);
    hikari.setPoolName("clickhouse-pool");
    hikari.setMaximumPoolSize(poolMaxSize);
    hikari.setMinimumIdle(Math.min(2, poolMaxSize));
    hikari.setConnectionTimeout(poolConnectionTimeoutMs);
    hikari.setValidationTimeout(poolValidationTimeoutMs);
    hikari.setMaxLifetime(poolMaxLifetimeMs);
    hikari.setConnectionTestQuery("SELECT 1");
    // Preserve the prior behavior where a ClickHouse outage at startup did NOT block app boot (the
    // raw ClickHouseDataSource connected lazily): skip the eager initial-connection probe. The pool
    // fills on first use; socket_timeout bounds any stuck query thereafter.
    hikari.setInitializationFailTimeout(-1);
    log.info(
        "ClickHouse DataSource ready url={} pool(max={}, connTimeout={}ms) client(socket={}ms,"
            + " connect={}ms)",
        url,
        poolMaxSize,
        poolConnectionTimeoutMs,
        socketTimeoutMs,
        connectTimeoutMs);
    return new HikariDataSource(hikari);
  }

  @Bean
  public ClickHouseMigrationRunner clickHouseMigrationRunner(
      @org.springframework.beans.factory.annotation.Qualifier("clickhouseDataSource")
          DataSource clickhouse) {
    return new ClickHouseMigrationRunner(clickhouse);
  }
}
