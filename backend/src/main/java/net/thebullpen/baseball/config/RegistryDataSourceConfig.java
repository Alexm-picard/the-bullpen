package net.thebullpen.baseball.config;

import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Explicit {@code @Primary} SQLite registry datasource, so the registry's {@link JdbcTemplate} and
 * {@code @Transactional} never inherit the ClickHouse datasource.
 *
 * <p><b>Why this exists (the bug it fixes).</b> The registry repositories ({@code
 * RegistryRepository}, {@code ExperimentResultsRepository}, {@code RoutingRepository}, {@code
 * RetrainingQueueRepository}, {@code AlertHistoryRepository}, {@code OpsEventsRepository}) inject
 * an <em>unqualified</em> {@link JdbcTemplate}, relying on Spring Boot to auto-configure a
 * datasource from {@code spring.datasource.url=jdbc:sqlite:...}. Boot's {@code
 * DataSourceAutoConfiguration} backs off the moment <em>any</em> {@link DataSource} bean exists.
 * When ClickHouse is enabled ({@code bullpen.clickhouse.enabled=true}), {@code ClickHouseConfig}
 * defines {@code clickhouseDataSource} as the only {@link DataSource} Boot sees, so the auto {@code
 * JdbcTemplate} + transaction manager bind to ClickHouse and every registry query against {@code
 * model_versions} (a SQLite-only table) throws {@code UNKNOWN_TABLE} (Code 60). The MLP registered
 * fine before ClickHouse was enabled (single datasource = SQLite); enabling ClickHouse unmasked
 * this.
 *
 * <p><b>The fix.</b> Declare the SQLite datasource explicitly and {@code @Primary}, with a matching
 * {@code @Primary} {@link JdbcTemplate} + transaction manager. The six registry repos (all SQLite
 * Flyway tables) bind here; the six analytical repos ({@code LivePitchesRepository}, {@code
 * DriftMetricsRepository}, {@code PlayerRepository}, {@code CalibrationRepository}, {@code
 * PlayerPredictionsRepository}, {@code PredictionLogRepository}) already use
 * {@code @Qualifier("clickhouseDataSource")} and are unaffected. Flyway targets SQLite via the
 * explicit {@code spring.flyway.url=${spring.datasource.url}}.
 */
@Configuration
public class RegistryDataSourceConfig {

  @Bean
  @Primary
  @ConfigurationProperties("spring.datasource")
  public DataSourceProperties registryDataSourceProperties() {
    return new DataSourceProperties();
  }

  @Bean(name = "registryDataSource")
  @Primary
  @ConfigurationProperties("spring.datasource.hikari")
  public DataSource registryDataSource(
      @Qualifier("registryDataSourceProperties") DataSourceProperties props) {
    return props.initializeDataSourceBuilder().build();
  }

  @Bean
  @Primary
  public JdbcTemplate jdbcTemplate(@Qualifier("registryDataSource") DataSource registry) {
    return new JdbcTemplate(registry);
  }

  @Bean
  @Primary
  public PlatformTransactionManager transactionManager(
      @Qualifier("registryDataSource") DataSource registry) {
    return new DataSourceTransactionManager(registry);
  }
}
