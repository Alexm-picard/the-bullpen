package net.thebullpen.baseball.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.UUID;
import net.thebullpen.baseball.Application;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Proves custom meters are VISIBLE ON THE SERVED {@code /actuator/prometheus} SCRAPE - not merely
 * present in an injected SimpleMeterRegistry. This gap was real: until this test, nothing scraped
 * the endpoint on any profile ({@code ActuatorSecurityIT} asserts auth via MockMvc, where the
 * endpoint 404s), so a registration/binding regression - a meter moved onto a hand-rolled registry,
 * a deny MeterFilter, an exposure change - would reach prod with every check green. A passing check
 * is not evidence unless you've confirmed it could have failed; the profile-split negatives below
 * are that confirmation.
 *
 * <p>Boots the API profile CH-FREE (the worker profile cannot boot without ClickHouse - its ungated
 * drift jobs require the CH-gated {@code DriftMetricsRepository}), so this runs in the normal lane
 * on every CI pass. The worker-side series - the ingest family and the two refresh jobs' freshness
 * gauges - are asserted on a REAL worker scrape in {@code WorkerPairTwoInstanceIT} (docker lane);
 * here their ABSENCE from the api scrape is the negative control proving these assertions read real
 * registry state rather than matching strings that would appear anywhere.
 *
 * <p>Named for the worker incident that motivated it (the 2026-08-01 missing-metrics diagnosis):
 * the first check anyone ran was an UNAUTHENTICATED {@code curl | grep}, which 401s and prints
 * nothing for every series at once - "two independent metric families missing from one endpoint" is
 * the signature of that trap, not of two registration bugs. The 401 test pins it on a served port,
 * where MockMvc could not.
 */
@EnabledIf("toyModelPresent")
class ActuatorScrapeIT {

  /** JUnit5 @EnabledIf hook - the api context cannot start without the on-disk toy model. */
  static boolean toyModelPresent() {
    return Files.exists(
        Path.of(System.getProperty("user.dir"))
            .getParent()
            .resolve("training/artifacts/_toy/v0/model.onnx"));
  }

  private static final String METRICS_USER = "it-metrics";
  private static final String METRICS_PASS = "it-metrics-pw";

  private static ConfigurableApplicationContext ctx;
  private static Path dbFile;
  private static int port;
  private static final HttpClient http = HttpClient.newHttpClient();

  @BeforeAll
  static void bootApiChFree() {
    dbFile =
        Path.of(
            System.getProperty("java.io.tmpdir"),
            "bullpen-scrape-it-" + UUID.randomUUID() + ".sqlite");
    String url = "jdbc:sqlite:" + dbFile + "?foreign_keys=true&busy_timeout=5000";
    Path snapshot =
        Path.of(
            System.getProperty("java.io.tmpdir"), "bullpen-scrape-it-snap-" + UUID.randomUUID());
    // The ApiPairTwoInstanceIT CH-free recipe; command-line args out-precedence the profile yml.
    ctx =
        new SpringApplicationBuilder(Application.class)
            .run(
                "--spring.profiles.active=api",
                "--server.port=0",
                "--bullpen.clickhouse.enabled=false",
                "--bullpen.ratelimit.enabled=false",
                "--spring.datasource.url=" + url,
                "--spring.flyway.url=" + url,
                "--bullpen.admin.basicauth=it-admin:it-admin-pw",
                "--bullpen.metrics.basicauth=" + METRICS_USER + ":" + METRICS_PASS,
                "--bullpen.snapshot.local-base-path=" + snapshot);
    String p = ctx.getEnvironment().getProperty("local.server.port");
    assertThat(p).as("embedded server must publish its random port").isNotNull();
    port = Integer.parseInt(p);
  }

  @AfterAll
  static void shutdown() throws Exception {
    http.close();
    if (ctx != null) {
      ctx.close();
    }
    if (dbFile != null) {
      Files.deleteIfExists(dbFile);
    }
  }

  private static HttpResponse<String> get(String path) throws Exception {
    HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build();
    return http.send(req, HttpResponse.BodyHandlers.ofString());
  }

  private static HttpResponse<String> get(String path, String authHeader) throws Exception {
    HttpRequest req =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Authorization", authHeader)
            .build();
    return http.send(req, HttpResponse.BodyHandlers.ofString());
  }

  private static String metricsAuth() {
    return "Basic "
        + Base64.getEncoder()
            .encodeToString((METRICS_USER + ":" + METRICS_PASS).getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void theServedScrapeRequiresAuth_theBareCurlTrap() throws Exception {
    // An unauthenticated GET is a 401 whose body carries no metrics - so `curl | grep <series>`
    // prints nothing for EVERY series at once, reading as "the series are absent".
    HttpResponse<String> resp = get("/actuator/prometheus");
    assertThat(resp.statusCode()).isEqualTo(401);
    assertThat(resp.body()).doesNotContain("thebullpen_").doesNotContain("bullpen_");
  }

  @Test
  void eagerMeters_areOnTheServedScrapeFromBoot() throws Exception {
    HttpResponse<String> resp = get("/actuator/prometheus", metricsAuth());
    assertThat(resp.statusCode()).isEqualTo(200);
    assertThat(resp.body())
        // AsyncPredictionLogger registers eagerly in its constructor on both profiles - the
        // canonical "present from boot with value 0" registration this endpoint must expose.
        // BOUNDARY-ANCHORED (name followed by a label brace or the sample value), because bare
        // contains() also matches any RENAMED series that kept the old name as a prefix - a
        // mutation this test failed to catch until the pattern was anchored.
        .containsPattern("(?m)^thebullpen_prediction_log_queue_depth[{ ]")
        .containsPattern("(?m)^thebullpen_prediction_log_enqueued_total[{ ]")
        // Boot's UptimeMetrics binder - the staleness alert rules' process-age gate reads this;
        // if it ever vanishes from the scrape those rules go permanently silent.
        .containsPattern("(?m)^process_start_time_seconds[{ ]");
  }

  @Test
  void workerOnlySeries_areAbsentHere_provingThePresenceAssertionsCanFail() throws Exception {
    // IngestMetrics and the refresh jobs are worker-profile beans; on the api scrape their
    // series CANNOT appear. Same mechanism (bean absent -> series absent) that
    // WorkerPairTwoInstanceIT's positive assertions guard on the real worker boot.
    HttpResponse<String> resp = get("/actuator/prometheus", metricsAuth());
    assertThat(resp.statusCode()).isEqualTo(200);
    assertThat(resp.body())
        .doesNotContain("bullpen_ingest_last_poll_timestamp_seconds")
        .doesNotContain("bullpen_pitcher_form_age_days")
        .doesNotContain("bullpen_pitchtype_prior_age_days")
        // ...including the two series THIS change introduces - they are the ones that most
        // need a negative control, or their positive assertions are the unfalsifiable kind.
        .doesNotContain("bullpen_pitcher_form_last_refresh_timestamp_seconds")
        .doesNotContain("bullpen_pitchtype_prior_last_refresh_timestamp_seconds");
  }
}
