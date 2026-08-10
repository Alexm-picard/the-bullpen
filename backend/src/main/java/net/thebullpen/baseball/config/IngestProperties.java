package net.thebullpen.baseball.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Typed, validated binding for the {@code bullpen.ingest.*} namespace (Wave E / M-task 26).
 * Replaces the eight scattered {@code @Value} injections that {@link
 * net.thebullpen.baseball.ingest.MlbStatsApiClient}, {@link
 * net.thebullpen.baseball.ingest.LivePollingService}, and {@link
 * net.thebullpen.baseball.ingest.PlayersRefreshJob} each declared inline, so the ingest knobs live
 * in one place, carry their own defaults, and fail fast at startup on a bad value instead of much
 * later at first use.
 *
 * <p>Registered via {@code @ConfigurationPropertiesScan} on {@link
 * net.thebullpen.baseball.Application}. The separate {@code bullpen.ingest.players.enabled} flag
 * stays a {@code @ConditionalOnProperty} on {@code PlayersRefreshJob} - it gates whether the bean
 * exists at all, which is a container concern, not a value this record binds.
 */
@ConfigurationProperties("bullpen.ingest")
@Validated
public record IngestProperties(
    @DefaultValue @Valid Live live, @DefaultValue @Valid Players players) {

  /**
   * Live MLB Stats API transport + poller cadence knobs ({@code bullpen.ingest.live.*}). Shared by
   * the HTTP client (base URL, user agent, timeout, retries) and the poller (API gap, schedule
   * refresh, lease staleness).
   */
  public record Live(
      @DefaultValue("https://statsapi.mlb.com") @NotBlank String baseUrl,
      @DefaultValue("TheBullpen/1.0 (+https://thebullpen.net)") @NotBlank String userAgent,
      @DefaultValue("5000") @Positive int timeoutMs,
      @DefaultValue("3") @PositiveOrZero int maxRetries,
      @DefaultValue("500") @PositiveOrZero long apiMinGapMs,
      @DefaultValue("15") @Positive long scheduleRefreshMin,
      @DefaultValue("30") @Positive long leaseStaleSeconds) {}

  /** Player-refresh job knobs ({@code bullpen.ingest.players.*}). */
  public record Players(@DefaultValue("false") boolean forceRefreshOnBoot) {}
}
