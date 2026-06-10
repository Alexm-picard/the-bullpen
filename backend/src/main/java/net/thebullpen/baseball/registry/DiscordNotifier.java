package net.thebullpen.baseball.registry;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Discord-webhook surface used by {@link ReconciliationJob}, the drift alerting path, and any
 * future ops alert. When {@code bullpen.discord.webhook-url} is blank (default), every call is
 * logged and that is it - no HTTP traffic, so dev/CI never phone home. When the URL is set, the
 * notice is logged AND posted to Discord (WS2 / 3c): a single {@code content} message via the JDK
 * {@link HttpClient} with a 5s timeout.
 *
 * <p>Best-effort by contract: a webhook failure (timeout, non-2xx, network error) is logged at WARN
 * and swallowed - an alert that cannot be delivered must never propagate into and break the drift
 * job or reconciliation job that raised it. Every notice is always logged first, so the log remains
 * the source of truth even when Discord is down.
 */
@Component
public class DiscordNotifier {

  private static final Logger log = LoggerFactory.getLogger(DiscordNotifier.class);
  private static final Duration TIMEOUT = Duration.ofSeconds(5);

  public enum Severity {
    NOTICE,
    WARN,
    CRITICAL;
  }

  private final String webhookUrl;
  private final HttpClient http;
  private final ObjectMapper mapper;

  @Autowired
  public DiscordNotifier(@Value("${bullpen.discord.webhook-url:}") String webhookUrl) {
    this(webhookUrl, HttpClient.newBuilder().connectTimeout(TIMEOUT).build(), new ObjectMapper());
  }

  /** Test seam: inject a stub HttpClient so the POST path is exercised without real network. */
  DiscordNotifier(String webhookUrl, HttpClient http, ObjectMapper mapper) {
    this.webhookUrl = webhookUrl;
    this.http = http;
    this.mapper = mapper;
    if (webhookUrl == null || webhookUrl.isBlank()) {
      log.info(
          "DiscordNotifier: stubbed (no webhook URL set) - notices will be logged only,"
              + " not posted to Discord. Set DISCORD_WEBHOOK_URL to enable delivery.");
    } else {
      log.info("DiscordNotifier: webhook configured - notices will be POSTed to Discord");
    }
  }

  /**
   * Send a notice. Always logged; additionally POSTed to Discord when a webhook URL is configured.
   * A delivery failure is logged and swallowed (best-effort) so it cannot break the caller.
   */
  public void send(Severity severity, String title, Map<String, ?> context) {
    log.info("[discord-{}] {} | {}", severity.name().toLowerCase(), title, context);
    if (isStubbed()) {
      return;
    }
    post(formatMessage(severity, title, context));
  }

  /** Convenience for the common no-context case. */
  public void send(Severity severity, String title) {
    send(severity, title, Map.of());
  }

  private void post(String content) {
    String body;
    try {
      body = mapper.writeValueAsString(Map.of("content", content));
    } catch (JsonProcessingException e) {
      log.warn("DiscordNotifier: could not serialize webhook payload; dropping notice", e);
      return;
    }
    try {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(webhookUrl))
              .timeout(TIMEOUT)
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(body))
              .build();
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      int status = response.statusCode();
      if (status < 200 || status >= 300) {
        log.warn("DiscordNotifier: webhook POST returned {} (notice was logged above)", status);
      }
    } catch (java.io.IOException e) {
      log.warn("DiscordNotifier: webhook POST failed (notice was logged above)", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.warn("DiscordNotifier: webhook POST interrupted (notice was logged above)");
    }
  }

  /** {@code **[SEVERITY] title**} plus one {@code key: value} line per context entry. */
  private static String formatMessage(Severity severity, String title, Map<String, ?> context) {
    StringBuilder sb =
        new StringBuilder("**[").append(severity.name()).append("] ").append(title).append("**");
    for (Map.Entry<String, ?> e : context.entrySet()) {
      sb.append("\n").append(e.getKey()).append(": ").append(e.getValue());
    }
    return sb.toString();
  }

  boolean isStubbed() {
    return webhookUrl == null || webhookUrl.isBlank();
  }
}
