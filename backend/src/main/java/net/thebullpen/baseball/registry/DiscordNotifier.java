package net.thebullpen.baseball.registry;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Minimal Discord-webhook surface used by {@link ReconciliationJob} and any future ops alert path.
 * Stubbed for 3a.5: when {@code bullpen.discord.webhook-url} is blank (default), every call is
 * logged at the configured severity and that's it — no HTTP traffic. The real client lands in 3c
 * when drift detection actually needs to page someone.
 *
 * <p>Why a class with one log line instead of just calling the logger directly: gives downstream
 * code a stable callsite that can be swapped for a real webhook later without touching every caller
 * — the alternative would be peppering {@code log.info(...)} calls and then doing a
 * grep-and-replace.
 */
@Component
public class DiscordNotifier {

  private static final Logger log = LoggerFactory.getLogger(DiscordNotifier.class);

  public enum Severity {
    NOTICE,
    WARN,
    CRITICAL;
  }

  private final String webhookUrl;

  public DiscordNotifier(@Value("${bullpen.discord.webhook-url:}") String webhookUrl) {
    this.webhookUrl = webhookUrl;
    if (webhookUrl == null || webhookUrl.isBlank()) {
      log.info(
          "DiscordNotifier: stubbed (no webhook URL set) — notices will be logged only,"
              + " not posted to Discord. Set DISCORD_WEBHOOK_URL when 3c lands.");
    } else {
      log.info("DiscordNotifier: webhook configured (HTTP POST will be wired in 3c)");
    }
  }

  /**
   * Send a notice. Currently log-only regardless of {@link #webhookUrl}; the URL is stored so the
   * swap to a real HTTP client in 3c needs zero changes to call sites.
   */
  public void send(Severity severity, String title, Map<String, ?> context) {
    log.info("[discord-{}] {} | {}", severity.name().toLowerCase(), title, context);
  }

  /** Convenience for the common no-context case. */
  public void send(Severity severity, String title) {
    send(severity, title, Map.of());
  }

  boolean isStubbed() {
    return webhookUrl == null || webhookUrl.isBlank();
  }
}
