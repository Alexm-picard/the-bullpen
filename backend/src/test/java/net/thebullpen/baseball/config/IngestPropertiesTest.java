package net.thebullpen.baseball.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * Binding + validation coverage for the typed {@code bullpen.ingest.*} config (Wave E / M-task 26):
 * the inline {@code @Value} defaults are preserved, overrides bind through relaxed keys, and a bad
 * value fails the context at startup instead of surfacing much later at first use.
 */
class IngestPropertiesTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(TestConfig.class);

  @Test
  void bindsTheFormerAtValueDefaultsWhenUnset() {
    runner.run(
        ctx -> {
          IngestProperties p = ctx.getBean(IngestProperties.class);
          assertThat(p.live().baseUrl()).isEqualTo("https://statsapi.mlb.com");
          assertThat(p.live().userAgent()).isEqualTo("TheBullpen/1.0 (+https://thebullpen.net)");
          assertThat(p.live().timeoutMs()).isEqualTo(5000);
          assertThat(p.live().maxRetries()).isEqualTo(3);
          assertThat(p.live().apiMinGapMs()).isEqualTo(500L);
          assertThat(p.live().scheduleRefreshMin()).isEqualTo(15L);
          assertThat(p.live().leaseStaleSeconds()).isEqualTo(30L);
          assertThat(p.players().forceRefreshOnBoot()).isFalse();
        });
  }

  @Test
  void bindsOverridesThroughRelaxedKeys() {
    runner
        .withPropertyValues(
            "bullpen.ingest.live.base-url=https://example.test",
            "bullpen.ingest.live.max-retries=7",
            "bullpen.ingest.live.api-min-gap-ms=0",
            "bullpen.ingest.players.force-refresh-on-boot=true")
        .run(
            ctx -> {
              IngestProperties p = ctx.getBean(IngestProperties.class);
              assertThat(p.live().baseUrl()).isEqualTo("https://example.test");
              assertThat(p.live().maxRetries()).isEqualTo(7);
              assertThat(p.live().apiMinGapMs()).isZero();
              assertThat(p.players().forceRefreshOnBoot()).isTrue();
            });
  }

  @Test
  void rejectsANonPositiveTimeoutAtStartup() {
    runner
        .withPropertyValues("bullpen.ingest.live.timeout-ms=0")
        .run(ctx -> assertThat(ctx).hasFailed());
  }

  @Test
  void rejectsABlankBaseUrlAtStartup() {
    runner
        .withPropertyValues("bullpen.ingest.live.base-url=")
        .run(ctx -> assertThat(ctx).hasFailed());
  }

  @Test
  void coexistsWithTheGateAndTickKeysThisRecordDoesNotBind() {
    // live.enabled / players.enabled are @ConditionalOnProperty bean-activation gates and
    // live.tick-ms is a SpEL @Scheduled delay - all live under bullpen.ingest.* but are
    // deliberately not record fields. ignoreUnknownFields (the @ConfigurationProperties default)
    // must let them sit alongside the typed binder without failing the context.
    runner
        .withPropertyValues(
            "bullpen.ingest.live.enabled=true",
            "bullpen.ingest.live.tick-ms=5000",
            "bullpen.ingest.players.enabled=true")
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed();
              assertThat(ctx.getBean(IngestProperties.class).live().baseUrl())
                  .isEqualTo("https://statsapi.mlb.com");
            });
  }

  @Configuration
  @EnableConfigurationProperties(IngestProperties.class)
  static class TestConfig {}
}
