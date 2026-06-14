package net.thebullpen.baseball.config;

import com.clickhouse.jdbc.ClickHouseDataSource;
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
    log.info("ClickHouse DataSource ready url={}", url);
    return new ClickHouseDataSource(url, props);
  }

  @Bean
  public ClickHouseMigrationRunner clickHouseMigrationRunner(
      @org.springframework.beans.factory.annotation.Qualifier("clickhouseDataSource")
          DataSource clickhouse) {
    return new ClickHouseMigrationRunner(clickhouse);
  }
}
