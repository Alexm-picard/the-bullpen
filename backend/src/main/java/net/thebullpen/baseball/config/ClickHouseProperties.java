package net.thebullpen.baseball.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Typed, validated binding for the {@code bullpen.clickhouse.*} namespace (Wave E / M-task 26,
 * slice 3). Replaces the nine field-injected {@code @Value}s on {@link ClickHouseConfig}, grouping
 * the connection knobs and the HikariCP pool sub-namespace ({@code bullpen.clickhouse.pool.*}) into
 * one place with their own defaults.
 *
 * <p>{@code password} is deliberately NOT {@code @NotBlank}: {@link ClickHouseConfig} does the
 * blank-password check itself with a fail-loud message that names the env var and refuses to fall
 * back to a known default credential. Keeping the constraint off the record preserves that specific
 * message (and lets the record bind harmlessly in contexts where ClickHouse is disabled - the
 * {@code @ConditionalOnProperty} gate stays on {@link ClickHouseConfig}, so the datasource bean
 * only materializes when {@code bullpen.clickhouse.enabled=true}).
 */
@ConfigurationProperties("bullpen.clickhouse")
@Validated
public record ClickHouseProperties(
    @DefaultValue("jdbc:ch:http://localhost:8123/default") @NotBlank String url,
    @DefaultValue("default") @NotBlank String user,
    @DefaultValue("") String password,
    @DefaultValue("30000") @Positive int socketTimeoutMs,
    @DefaultValue("10000") @Positive int connectTimeoutMs,
    @DefaultValue @Valid Pool pool) {

  /**
   * HikariCP pool knobs around the raw ClickHouseDataSource ({@code bullpen.clickhouse.pool.*}).
   */
  public record Pool(
      @DefaultValue("8") @Positive int maxSize,
      @DefaultValue("3000") @Positive long connectionTimeoutMs,
      @DefaultValue("2000") @Positive long validationTimeoutMs,
      @DefaultValue("1800000") @Positive long maxLifetimeMs) {}
}
