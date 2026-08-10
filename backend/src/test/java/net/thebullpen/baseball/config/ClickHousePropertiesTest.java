package net.thebullpen.baseball.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * Binding + validation coverage for the typed {@code bullpen.clickhouse.*} config (Wave E / M-task
 * 26, slice 3): the former field-{@code @Value} defaults are preserved (including the nested {@code
 * pool.*} sub-namespace), overrides bind through relaxed keys, {@code password} carries no
 * constraint (the fail-loud blank check lives in {@link ClickHouseConfig}), and a non-positive
 * timeout / pool size fails the context at startup.
 */
class ClickHousePropertiesTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(TestConfig.class);

  @Test
  void bindsTheFormerAtValueDefaultsWhenUnset() {
    runner.run(
        ctx -> {
          ClickHouseProperties p = ctx.getBean(ClickHouseProperties.class);
          assertThat(p.url()).isEqualTo("jdbc:ch:http://localhost:8123/default");
          assertThat(p.user()).isEqualTo("default");
          assertThat(p.password()).isEmpty();
          assertThat(p.socketTimeoutMs()).isEqualTo(30_000);
          assertThat(p.connectTimeoutMs()).isEqualTo(10_000);
          assertThat(p.pool().maxSize()).isEqualTo(8);
          assertThat(p.pool().connectionTimeoutMs()).isEqualTo(3_000L);
          assertThat(p.pool().validationTimeoutMs()).isEqualTo(2_000L);
          assertThat(p.pool().maxLifetimeMs()).isEqualTo(1_800_000L);
        });
  }

  @Test
  void bindsOverridesThroughRelaxedKeys() {
    runner
        .withPropertyValues(
            "bullpen.clickhouse.url=jdbc:ch:http://ch.internal:8123/bullpen",
            "bullpen.clickhouse.password=s3cret",
            "bullpen.clickhouse.socket-timeout-ms=5000",
            "bullpen.clickhouse.pool.max-size=16")
        .run(
            ctx -> {
              ClickHouseProperties p = ctx.getBean(ClickHouseProperties.class);
              assertThat(p.url()).isEqualTo("jdbc:ch:http://ch.internal:8123/bullpen");
              assertThat(p.password()).isEqualTo("s3cret");
              assertThat(p.socketTimeoutMs()).isEqualTo(5_000);
              assertThat(p.pool().maxSize()).isEqualTo(16);
            });
  }

  @Test
  void rejectsANonPositiveSocketTimeoutAtStartup() {
    runner
        .withPropertyValues("bullpen.clickhouse.socket-timeout-ms=0")
        .run(ctx -> assertThat(ctx).hasFailed());
  }

  @Test
  void rejectsANonPositivePoolSizeAtStartup() {
    runner
        .withPropertyValues("bullpen.clickhouse.pool.max-size=0")
        .run(ctx -> assertThat(ctx).hasFailed());
  }

  @Configuration
  @EnableConfigurationProperties(ClickHouseProperties.class)
  static class TestConfig {}
}
