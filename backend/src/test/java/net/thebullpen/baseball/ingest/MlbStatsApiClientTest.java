package net.thebullpen.baseball.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Exercises the client's orchestration (delegation to {@link MlbFeedParser}, retry/backoff) by
 * overriding the {@code httpGet} boundary with captured fixtures - no network, no mock server. The
 * MLB HTTP boundary is the one place mocking is allowed (testing posture).
 */
class MlbStatsApiClientTest {

  private static String resource(String path) throws IOException {
    try (InputStream in = MlbStatsApiClientTest.class.getResourceAsStream(path)) {
      if (in == null) {
        throw new IOException("missing fixture " + path);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static MlbStatsApiClient client(int maxRetries, HttpStub stub) {
    return new MlbStatsApiClient(
        new MlbFeedParser(new ObjectMapper()), "https://statsapi.mlb.com", "ua", 1000, maxRetries) {
      @Override
      String httpGet(String url) throws IOException {
        return stub.get(url);
      }

      @Override
      void backoff(int attempt) {
        // no sleeping in unit tests
      }
    };
  }

  @FunctionalInterface
  private interface HttpStub {
    String get(String url) throws IOException;
  }

  @Test
  void fetchSchedule_delegates_to_the_parser() throws IOException {
    MlbStatsApiClient c = client(3, url -> resource("/mlb/schedule_2026-06-04.json"));
    assertEquals(4, c.fetchSchedule(LocalDate.of(2026, 6, 4)).size());
  }

  @Test
  void fetchLiveFeed_delegates_to_the_parser() throws IOException {
    MlbStatsApiClient c = client(3, url -> resource("/mlb/feed_live_824753.json"));
    LiveGameFeed feed = c.fetchLiveFeed(824753);
    assertEquals(824753L, feed.gamePk());
    assertEquals(300, feed.pitches().size());
  }

  @Test
  void getWithRetry_retries_transient_5xx_then_succeeds() throws IOException {
    AtomicInteger calls = new AtomicInteger();
    MlbStatsApiClient c =
        client(
            3,
            url -> {
              if (calls.incrementAndGet() < 3) {
                throw new MlbStatsApiClient.RetryableHttpException(503);
              }
              return "ok-body";
            });
    assertEquals("ok-body", c.getWithRetry("https://x/y"));
    assertEquals(3, calls.get(), "two 503s, success on the third attempt");
  }

  @Test
  void getWithRetry_exhausts_retries_then_throws() {
    AtomicInteger calls = new AtomicInteger();
    MlbStatsApiClient c =
        client(
            2,
            url -> {
              calls.incrementAndGet();
              throw new MlbStatsApiClient.RetryableHttpException(500);
            });
    assertThrows(IOException.class, () -> c.getWithRetry("https://x/y"));
    assertEquals(3, calls.get(), "initial attempt + 2 retries");
  }
}
