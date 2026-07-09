package net.thebullpen.baseball.ingest;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import net.thebullpen.baseball.config.IngestProperties;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.ParseException;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * HTTP transport to the public MLB Stats API. Delegates all parsing to {@link MlbFeedParser}; this
 * class only fetches (schedule + GUMBO live feed), with timeouts, a polite {@code User-Agent}, and
 * exponential backoff on 429/5xx. Worker-profile only (decision [143]; the api profile never
 * polls).
 *
 * <p>{@link #httpGet(String)} and {@link #backoff(int)} are package-private and overridable so the
 * retry/orchestration logic is unit-testable against captured fixtures without a network or a mock
 * server - the MLB HTTP boundary is the one place mocking is allowed (testing posture).
 */
@Component
@Profile("worker")
public class MlbStatsApiClient {

  private static final Logger log = LoggerFactory.getLogger(MlbStatsApiClient.class);

  private final MlbFeedParser parser;
  private final String baseUrl;
  private final String userAgent;
  private final int maxRetries;
  private final CloseableHttpClient http;

  public MlbStatsApiClient(MlbFeedParser parser, IngestProperties props) {
    IngestProperties.Live live = props.live();
    this.parser = parser;
    String baseUrl = live.baseUrl();
    this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    this.userAgent = live.userAgent();
    this.maxRetries = live.maxRetries();
    RequestConfig rc =
        RequestConfig.custom()
            .setConnectionRequestTimeout(Timeout.ofMilliseconds(live.timeoutMs()))
            .setResponseTimeout(Timeout.ofMilliseconds(live.timeoutMs()))
            .build();
    this.http = HttpClients.custom().setDefaultRequestConfig(rc).build();
  }

  /**
   * Today's (or any date's) MLB games, for poll discovery + the persisted slate. {@code
   * hydrate=team} enriches the team node with its abbreviation (BOS, NYY) so the pre-game slate can
   * show abbreviations consistent with the live (pitches_live) path; {@code probablePitcher} adds
   * the announced starters (id + fullName) that feed the matchup classification's pitcher side.
   */
  public List<ScheduledGame> fetchSchedule(LocalDate date) throws IOException {
    return parser.parseSchedule(
        getWithRetry(
            baseUrl + "/api/v1/schedule?sportId=1&hydrate=team,probablePitcher&date=" + date));
  }

  /**
   * Season {@link PlayerSeasonStat}s for a set of players (the matchup classification's quality
   * source). Uses the bulk people endpoint with a stats hydrate so one request covers many players
   * and both groups (hitting + pitching). The hydrate value is URL-encoded because it carries
   * {@code []}, {@code ()}, and {@code =} that the URI parser would otherwise reject.
   */
  public List<PlayerSeasonStat> fetchSeasonStats(Collection<Long> playerIds, int season)
      throws IOException {
    if (playerIds.isEmpty()) {
      return List.of();
    }
    String ids = playerIds.stream().map(String::valueOf).collect(Collectors.joining(","));
    String hydrate =
        URLEncoder.encode(
            "stats(group=[hitting,pitching],type=[season],season=" + season + ")",
            StandardCharsets.UTF_8);
    return parser.parseSeasonStats(
        getWithRetry(baseUrl + "/api/v1/people?personIds=" + ids + "&hydrate=" + hydrate));
  }

  /**
   * A game's posted lineups (boxscore batting order). Empty lists until the lineup is posted (~1-2h
   * before first pitch); the lineup job retries on its next tick.
   */
  public Lineup fetchLineup(long gamePk) throws IOException {
    return parser.parseLineup(
        getWithRetry(baseUrl + "/api/v1/game/" + gamePk + "/boxscore"), gamePk);
  }

  /** The GUMBO live feed for one game, parsed into status + every pitch so far. */
  public LiveGameFeed fetchLiveFeed(long gamePk) throws IOException {
    return parser.parseLiveFeed(getWithRetry(baseUrl + "/api/v1.1/game/" + gamePk + "/feed/live"));
  }

  /** The full player roster for one season, for the players-dimension refresh (DP3). */
  public List<MlbPlayer> fetchPlayers(int season) throws IOException {
    return parser.parsePlayers(getWithRetry(baseUrl + "/api/v1/sports/1/players?season=" + season));
  }

  String getWithRetry(String url) throws IOException {
    IOException last = null;
    for (int attempt = 0; attempt <= maxRetries; attempt++) {
      try {
        return httpGet(url);
      } catch (RetryableHttpException e) {
        last = e;
        log.warn(
            "retryable MLB API status {} for {} (attempt {}/{})",
            e.statusCode,
            url,
            attempt + 1,
            maxRetries + 1);
      } catch (IOException e) {
        last = e;
        log.warn(
            "MLB API IO error for {} (attempt {}/{}): {}",
            url,
            attempt + 1,
            maxRetries + 1,
            e.toString());
      }
      if (attempt < maxRetries) {
        backoff(attempt);
      }
    }
    throw last != null ? last : new IOException("MLB API request failed: " + url);
  }

  String httpGet(String url) throws IOException {
    HttpGet get = new HttpGet(url);
    get.addHeader("User-Agent", userAgent);
    get.addHeader("Accept", "application/json");
    return http.execute(
        get,
        response -> {
          int code = response.getCode();
          if (code == 429 || code >= 500) {
            throw new RetryableHttpException(code);
          }
          if (code >= 400) {
            throw new IOException("MLB API " + code + " for " + url);
          }
          return body(response.getEntity());
        });
  }

  private static String body(HttpEntity entity) throws IOException {
    if (entity == null) {
      return "";
    }
    try {
      return EntityUtils.toString(entity, StandardCharsets.UTF_8);
    } catch (ParseException e) {
      throw new IOException("unparseable MLB API entity", e);
    }
  }

  void backoff(int attempt) {
    try {
      Thread.sleep(Math.min(2000L, 200L * (1L << attempt)));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @PreDestroy
  void close() throws IOException {
    http.close();
  }

  /** Signals a 429/5xx that {@link #getWithRetry(String)} should retry with backoff. */
  static final class RetryableHttpException extends IOException {
    private static final long serialVersionUID = 1L;
    final int statusCode;

    RetryableHttpException(int statusCode) {
      super("retryable MLB API status " + statusCode);
      this.statusCode = statusCode;
    }
  }
}
