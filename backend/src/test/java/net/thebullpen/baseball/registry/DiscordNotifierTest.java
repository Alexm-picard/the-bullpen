package net.thebullpen.baseball.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * WS2: DiscordNotifier posts to the webhook when configured, logs-only when not, and is best-effort
 * - a delivery failure never propagates into the caller (a drift / reconciliation job).
 */
class DiscordNotifierTest {

  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void blank_url_logs_only_and_never_posts() throws Exception {
    HttpClient http = mock(HttpClient.class);
    DiscordNotifier notifier = new DiscordNotifier("", http, mapper);
    notifier.send(DiscordNotifier.Severity.WARN, "drift detected", Map.of("psi", 0.31));
    verify(http, never()).send(any(), any());
  }

  @Test
  @SuppressWarnings("unchecked")
  void configured_url_posts_to_the_webhook() throws Exception {
    HttpClient http = mock(HttpClient.class);
    HttpResponse<Object> resp = mock(HttpResponse.class);
    when(resp.statusCode()).thenReturn(204);
    when(http.send(any(HttpRequest.class), any())).thenReturn(resp);

    DiscordNotifier notifier =
        new DiscordNotifier("https://discord.test/webhook/abc", http, mapper);
    notifier.send(
        DiscordNotifier.Severity.CRITICAL, "champion 500ing", Map.of("model", "pitch_outcome_pre"));

    ArgumentCaptor<HttpRequest> req = ArgumentCaptor.forClass(HttpRequest.class);
    verify(http).send(req.capture(), any());
    assertThat(req.getValue().uri().toString()).isEqualTo("https://discord.test/webhook/abc");
    assertThat(req.getValue().method()).isEqualTo("POST");
  }

  @Test
  @SuppressWarnings("unchecked")
  void a_non_2xx_response_is_swallowed() throws Exception {
    HttpClient http = mock(HttpClient.class);
    HttpResponse<Object> resp = mock(HttpResponse.class);
    when(resp.statusCode()).thenReturn(500);
    when(http.send(any(HttpRequest.class), any())).thenReturn(resp);
    DiscordNotifier notifier =
        new DiscordNotifier("https://discord.test/webhook/abc", http, mapper);

    // Best-effort: a webhook failure must never break the caller.
    assertThatCode(() -> notifier.send(DiscordNotifier.Severity.WARN, "x"))
        .doesNotThrowAnyException();
  }

  @Test
  @SuppressWarnings("unchecked")
  void an_io_failure_is_swallowed() throws Exception {
    HttpClient http = mock(HttpClient.class);
    when(http.send(any(HttpRequest.class), any())).thenThrow(new IOException("connection refused"));
    DiscordNotifier notifier =
        new DiscordNotifier("https://discord.test/webhook/abc", http, mapper);

    assertThatCode(() -> notifier.send(DiscordNotifier.Severity.WARN, "x"))
        .doesNotThrowAnyException();
  }
}
