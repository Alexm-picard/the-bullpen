package net.thebullpen.baseball.config;

import io.sentry.Sentry;
import io.sentry.SentryOptions;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * A6 (ADR-0008) — Sentry SDK customization. The {@code sentry-spring-boot-starter-jakarta} does the
 * heavy lifting (captures unhandled controller exceptions + ERROR logs, all auto-disabled when
 * {@code sentry.dsn} is blank, which is the dev/CI/test default). This bean only stamps the request
 * {@code correlation_id} (set in MDC by {@link CorrelationIdFilter}) onto every outgoing event as a
 * tag, so a GlitchTip issue links back to the exact structured-log line in Grafana/Loki.
 */
@Configuration
public class SentryConfig {

  @Bean
  Sentry.OptionsConfiguration<SentryOptions> bullpenSentryOptions() {
    return options ->
        options.setBeforeSend(
            (event, hint) -> {
              String correlationId = MDC.get("correlation_id");
              if (correlationId != null && !correlationId.isBlank()) {
                event.setTag("correlation_id", correlationId);
              }
              return event;
            });
  }
}
