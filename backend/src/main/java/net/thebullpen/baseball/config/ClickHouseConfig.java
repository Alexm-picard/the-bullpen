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

  private final ClickHouseProperties props;

  public ClickHouseConfig(ClickHouseProperties props) {
    this.props = props;
  }

  @Bean(name = "clickhouseDataSource")
  public DataSource clickhouseDataSource() throws SQLException {
    if (props.password() == null || props.password().isBlank()) {
      throw new IllegalStateException(
          "bullpen.clickhouse.password is unset - set BULLPEN_CLICKHOUSE_PASSWORD in the runtime"
              + " environment (or override @DynamicPropertySource in tests). Refusing to fall back"
              + " to a known default credential.");
    }
    ClickHouseProperties.Pool pool = props.pool();
    Properties chProps = new Properties();
    chProps.setProperty("user", props.user());
    chProps.setProperty("password", props.password());
    chProps.setProperty("socket_timeout", Integer.toString(props.socketTimeoutMs()));
    chProps.setProperty("connection_timeout", Integer.toString(props.connectTimeoutMs()));
    ClickHouseDataSource chDataSource = new ClickHouseDataSource(props.url(), chProps);

    HikariConfig hikari = new HikariConfig();
    hikari.setDataSource(chDataSource);
    hikari.setPoolName("clickhouse-pool");
    hikari.setMaximumPoolSize(pool.maxSize());
    hikari.setMinimumIdle(Math.min(2, pool.maxSize()));
    hikari.setConnectionTimeout(pool.connectionTimeoutMs());
    hikari.setValidationTimeout(pool.validationTimeoutMs());
    hikari.setMaxLifetime(pool.maxLifetimeMs());
    hikari.setConnectionTestQuery("SELECT 1");
    // Preserve the prior behavior where a ClickHouse outage at startup did NOT block app boot (the
    // raw ClickHouseDataSource connected lazily): skip the eager initial-connection probe. The pool
    // fills on first use; socket_timeout bounds any stuck query thereafter.
    hikari.setInitializationFailTimeout(-1);
    log.info(
        "ClickHouse DataSource ready url={} pool(max={}, connTimeout={}ms) client(socket={}ms,"
            + " connect={}ms)",
        props.url(),
        pool.maxSize(),
        pool.connectionTimeoutMs(),
        props.socketTimeoutMs(),
        props.connectTimeoutMs());
    return new HikariDataSource(hikari);
  }

  @Bean
  public ClickHouseMigrationRunner clickHouseMigrationRunner(
      @org.springframework.beans.factory.annotation.Qualifier("clickhouseDataSource")
          DataSource clickhouse) {
    return new ClickHouseMigrationRunner(clickhouse);
  }
}
