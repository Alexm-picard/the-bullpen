package net.thebullpen.baseball.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * Binding + validation coverage for the typed {@code bullpen.ratelimit.*} config (Wave E / M-task
 * 26, slice 2): the inline {@code @Value} defaults are preserved (including the two-element
 * loopback trusted-proxy list), overrides bind through relaxed keys, and a non-positive per-minute
 * limit fails the context at startup instead of silently throttling a route to zero.
 */
class RateLimitPropertiesTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(TestConfig.class);

  @Test
  void bindsTheFormerAtValueDefaultsWhenUnset() {
    // NB: `enabled` is deliberately not asserted here - the gradle test task pins
    // `bullpen.ratelimit.enabled=false` as a system property (build.gradle.kts) and
    // ApplicationContextRunner inherits system properties, so the record's @DefaultValue("true") is
    // masked under this harness. The `enabled` default + both-ways binding is covered separately.
    runner.run(
        ctx -> {
          RateLimitProperties p = ctx.getBean(RateLimitProperties.class);
          assertThat(p.predictPerMinute()).isEqualTo(60);
          assertThat(p.simulatePerMinute()).isEqualTo(15);
          assertThat(p.searchPerMinute()).isEqualTo(120);
          assertThat(p.adminPerMinute()).isEqualTo(20);
          assertThat(p.trustedProxies()).containsExactly("127.0.0.0/8", "::1");
        });
  }

  @Test
  void enabledBindsBothWays() {
    runner
        .withPropertyValues("bullpen.ratelimit.enabled=true")
        .run(ctx -> assertThat(ctx.getBean(RateLimitProperties.class).enabled()).isTrue());
    runner
        .withPropertyValues("bullpen.ratelimit.enabled=false")
        .run(ctx -> assertThat(ctx.getBean(RateLimitProperties.class).enabled()).isFalse());
  }

  @Test
  void bindsOverridesThroughRelaxedKeys() {
    runner
        .withPropertyValues(
            "bullpen.ratelimit.predict-per-minute=200",
            "bullpen.ratelimit.trusted-proxies=10.0.0.0/8,192.168.0.0/16")
        .run(
            ctx -> {
              RateLimitProperties p = ctx.getBean(RateLimitProperties.class);
              assertThat(p.predictPerMinute()).isEqualTo(200);
              assertThat(p.trustedProxies()).containsExactly("10.0.0.0/8", "192.168.0.0/16");
            });
  }

  @Test
  void rejectsANonPositivePerMinuteLimitAtStartup() {
    runner
        .withPropertyValues("bullpen.ratelimit.predict-per-minute=0")
        .run(ctx -> assertThat(ctx).hasFailed());
  }

  @Configuration
  @EnableConfigurationProperties(RateLimitProperties.class)
  static class TestConfig {}
}
