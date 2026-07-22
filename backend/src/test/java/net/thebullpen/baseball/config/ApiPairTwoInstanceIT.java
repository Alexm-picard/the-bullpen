package net.thebullpen.baseball.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.thebullpen.baseball.Application;
import net.thebullpen.baseball.inference.routing.RoutingService;
import net.thebullpen.baseball.registry.RegistryRepository;
import net.thebullpen.baseball.registry.RegistryService;
import net.thebullpen.baseball.registry.dto.ModelVersion;
import net.thebullpen.baseball.registry.dto.RegisterRequest;
import net.thebullpen.baseball.registry.dto.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * D-39 (PR-D5) api-pair two-instance IT: proves the {@code api} profile is safe-to-duplicate. Boots
 * TWO real api contexts on ONE shared temp SQLite (the deliberate single-host registry boundary)
 * and asserts:
 *
 * <ul>
 *   <li>(a) concurrent predictions on both ports return identical outputs with zero errors - the
 *       serving path is stateless and the A/B bucketer is deterministic;
 *   <li>(b) a routing write on instance A converges on instance B within the routing-cache TTL. The
 *       per-JVM Caffeine cache with a bounded {@code expireAfterWrite} IS the cross-instance
 *       convergence mechanism; D-38 made it a property and this drives it to 3s instead of the 30s
 *       default (no long sleep). It also stays stale on B in the meantime, proving the cache
 *       caches.
 * </ul>
 *
 * <p>CH-free ({@code bullpen.clickhouse.enabled=false}), so it runs on EVERY CI pass, not the
 * docker gate. Guarded on the toy artifact ({@code ToyBattedBallInference.@PostConstruct} refuses
 * to start without {@code training/artifacts/_toy/v0/model.onnx}; CI {@code backend.yml} generates
 * it, and a fresh clone without it skips this class rather than failing).
 *
 * <p>The two api contexts run no {@code @Profile("worker")} schedulers, so there is no
 * double-firing here - the worker-pair concurrency (job_locks / lease / alert dedup) is D-39b under
 * the docker gate.
 */
@EnabledIf("toyModelPresent")
class ApiPairTwoInstanceIT {

  private static final String ADMIN = "it-admin:it-password";
  // Champion-shape single-park body (the toy shape was retired): the per-park outcome model's seven
  // inputs + a parkId selecting one of its parks. PARK00 exists in the fixture calibrator.
  private static final String PREDICT_BODY =
      "{\"launchSpeedMph\":104.5,\"launchAngleDeg\":28.0,\"sprayAngleDeg\":5.0,"
          + "\"hitDistanceFt\":401.0,\"stand\":\"R\",\"baseState\":0,\"outs\":1,"
          + "\"parkId\":\"PARK00\"}";
  private static final Path CONTRACT =
      Path.of(System.getProperty("user.dir"))
          .getParent()
          .resolve("contracts/feature_pipeline_battedball.json");
  private static final int N_PARKS = 30;
  private static final int N_OUTCOMES = 5;
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final HttpClient HTTP = HttpClient.newHttpClient();

  private static ConfigurableApplicationContext ctxA;
  private static ConfigurableApplicationContext ctxB;
  private static int portA;
  private static int portB;
  private static Path dbFile;
  private static Path snapshotBase;

  /** JUnit5 @EnabledIf hook - the api context cannot start without the on-disk toy model. */
  static boolean toyModelPresent() {
    return Files.exists(
        Path.of(System.getProperty("user.dir"))
            .getParent()
            .resolve("training/artifacts/_toy/v0/model.onnx"));
  }

  @BeforeAll
  static void bootPair() {
    dbFile =
        Path.of(
            System.getProperty("java.io.tmpdir"), "bullpen-d39-" + UUID.randomUUID() + ".sqlite");
    String url = "jdbc:sqlite:" + dbFile + "?foreign_keys=true&busy_timeout=5000";
    // A SHARED snapshot store so a champion registered via A resolves on B too (both instances
    // read this base). Needed because /v1/predict/batted-ball now serves the registry champion,
    // not the toy - so the predict test must register a real, loadable model.
    snapshotBase =
        Path.of(System.getProperty("java.io.tmpdir"), "bullpen-d39-snapshots-" + UUID.randomUUID());
    // Boot SEQUENTIALLY: A migrates + baselines the fresh SQLite to head, then B's Flyway is a
    // validate/no-op - avoids a concurrent-writer SQLITE_BUSY on the single-writer file.
    ctxA = boot(url);
    portA = portOf(ctxA);
    ctxB = boot(url);
    portB = portOf(ctxB);
    registerChampion(ctxA.getBean(RegistryService.class));
  }

  private static ConfigurableApplicationContext boot(String sharedDbUrl) {
    // Command-line args are highest precedence - .properties() would be out-precedenced by
    // application-api.yml for keys like server.port / spring.datasource.url.
    return new SpringApplicationBuilder(Application.class)
        .run(
            "--spring.profiles.active=api",
            "--server.port=0",
            "--bullpen.clickhouse.enabled=false",
            "--bullpen.ratelimit.enabled=false",
            "--spring.datasource.url=" + sharedDbUrl,
            "--spring.flyway.url=" + sharedDbUrl,
            "--bullpen.admin.basicauth=" + ADMIN,
            "--bullpen.snapshot.local-base-path=" + snapshotBase,
            "--bullpen.cache.routing-ttl-seconds=3");
  }

  private static int portOf(ConfigurableApplicationContext ctx) {
    return ((ServletWebServerApplicationContext) ctx).getWebServer().getPort();
  }

  @AfterAll
  static void shutdown() throws Exception {
    if (ctxA != null) {
      ctxA.close();
    }
    if (ctxB != null) {
      ctxB.close();
    }
    if (dbFile != null) {
      Files.deleteIfExists(dbFile);
    }
  }

  @Test
  void concurrentPredictsAcrossBothInstancesAreIdenticalWithZeroErrors() throws Exception {
    int perPort = 8;
    // Virtual threads (the locked house style) - all 16 requests are in flight at once rather than
    // capped at a fixed pool, a stronger cross-instance concurrency exercise.
    ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
    try {
      List<Future<Float>> futures = new ArrayList<>();
      for (int i = 0; i < perPort; i++) {
        futures.add(pool.submit(() -> predictProbHr(portA)));
        futures.add(pool.submit(() -> predictProbHr(portB)));
      }
      List<Float> results = new ArrayList<>();
      for (Future<Float> f : futures) {
        results.add(f.get(30, TimeUnit.SECONDS)); // any non-200 throws -> zero-errors assertion
      }
      // Every instance returns the SAME champion prediction for the same input (stateless serving +
      // deterministic ONNX, same shared snapshot), so a duplicated api instance is a byte-identical
      // replica - the stateless-replica property scale-out relies on (ADR-0013).
      float first = results.get(0);
      assertThat(results).allSatisfy(p -> assertThat(p).isEqualTo(first));
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void routingWriteOnAConvergesOnBWithinTheTtl() throws Exception {
    String model = "d39_conv_model";
    // model_routing.champion_version_id has a FK to model_versions, so insert a minimal version
    // first (RegistryRepository.insert bypasses the register schema-hash gate), then create the
    // SHADOW routing row pointing at it.
    long championId =
        ctxA.getBean(RegistryRepository.class)
            .insert(
                model,
                "v1",
                "artifact",
                "metadata",
                "hash",
                "[2024-01-01,2024-12-31]",
                "schema",
                "{}",
                Instant.now(),
                Stage.SHADOW,
                "d39-it",
                "routing convergence seed")
            .id();
    ctxA.getBean(RoutingService.class).ensureRoutingForChampion(model, championId);

    // Prime B's per-JVM routing cache with the current (SHADOW) value via the ONLY cache-backed
    // HTTP
    // read (GET /v1/admin/routing/{model}; /v1/ops/routing is uncached and would flip instantly).
    assertThat(routingMode(portB, model)).isEqualTo("SHADOW");

    // Write on A: flip mode to AB. A's @CacheEvict clears A's cache; B's cache is untouched.
    assertThat(setModeAb(portA, model)).isEqualTo(200);

    // Immediately on A: visible (A's cache was evicted on its own write).
    assertThat(routingMode(portA, model)).isEqualTo("AB");
    // Immediately on B: STILL stale (B's per-JVM cache was not evicted) - proves the cache caches.
    assertThat(routingMode(portB, model)).isEqualTo("SHADOW");

    // After the 3s TTL, B's expireAfterWrite lapses, it re-reads the shared DB, and converges to
    // AB. Poll well past the TTL so a CI GC/JIT/fsync stall on the re-read still lands green.
    assertThat(pollUntil(() -> routingMode(portB, model), "AB", 8000)).isEqualTo("AB");
  }

  // --- HTTP helpers -----------------------------------------------------

  private static float predictProbHr(int port) throws Exception {
    HttpResponse<String> resp =
        HTTP.send(
            HttpRequest.newBuilder(
                    URI.create("http://localhost:" + port + "/v1/predict/batted-ball"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(PREDICT_BODY))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    if (resp.statusCode() != 200) {
      throw new AssertionError(
          "predict on " + port + " returned " + resp.statusCode() + ": " + resp.body());
    }
    return MAPPER.readTree(resp.body()).get("probHr").floatValue();
  }

  private static String routingMode(int port, String model) throws Exception {
    HttpResponse<String> resp =
        HTTP.send(
            HttpRequest.newBuilder(
                    URI.create("http://localhost:" + port + "/v1/admin/routing/" + model))
                .header("Authorization", basicAuth())
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    if (resp.statusCode() != 200) {
      throw new AssertionError(
          "routing GET on " + port + " returned " + resp.statusCode() + ": " + resp.body());
    }
    return MAPPER.readTree(resp.body()).get("mode").asText();
  }

  private static int setModeAb(int port, String model) throws Exception {
    return HTTP.send(
            HttpRequest.newBuilder(
                    URI.create("http://localhost:" + port + "/v1/admin/routing/" + model + "/mode"))
                .header("Authorization", basicAuth())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"mode\":\"AB\",\"reason\":\"d39\"}"))
                .build(),
            HttpResponse.BodyHandlers.ofString())
        .statusCode();
  }

  private static String basicAuth() {
    return "Basic " + Base64.getEncoder().encodeToString(ADMIN.getBytes(StandardCharsets.UTF_8));
  }

  private interface Read {
    String get() throws Exception;
  }

  private static String pollUntil(Read read, String want, long timeoutMs) throws Exception {
    long deadlineNanos = System.nanoTime() + timeoutMs * 1_000_000L;
    String last = null;
    while (System.nanoTime() < deadlineNanos) {
      last = read.get();
      if (want.equals(last)) {
        return last;
      }
      Thread.sleep(100);
    }
    return last;
  }

  // --- champion fixture (same shape as PredictAllParksControllerTest; a shared test helper is a
  // reasonable follow-up if a fourth copy appears)
  // -------------------------------------------------

  private static void registerChampion(RegistryService service) {
    try {
      Path dir = Files.createTempDirectory("bullpen-d39-champ-");
      Path artifact = dir.resolve("model.onnx");
      URL onnx =
          ApiPairTwoInstanceIT.class.getResource("/onnx/battedball_park_outcome_fixture.onnx");
      Files.copy(Path.of(Objects.requireNonNull(onnx, "fixture missing").toURI()), artifact);
      Path metadata = dir.resolve("metadata.json");
      Files.writeString(metadata, metadataJson());
      Path pipeline = dir.resolve("feature_pipeline.json");
      Files.copy(CONTRACT, pipeline);
      Files.writeString(dir.resolve("calibrator.json"), calibratorJson());
      ModelVersion mv =
          service.register(
              new RegisterRequest(
                  "battedball_outcome",
                  "v1",
                  artifact.toString(),
                  metadata.toString(),
                  pipeline.toString(),
                  "d39-champ",
                  "[2024-01-01,2024-12-31]",
                  "{\"ece\":0.03}",
                  Instant.now(),
                  null,
                  null));
      service.register(
          new RegisterRequest(
              "lr_baseline_batted_ball",
              "v1",
              artifact.toString(),
              metadata.toString(),
              pipeline.toString(),
              "d39-champ-baseline",
              "[2024-01-01,2024-12-31]",
              "{\"ece\":0.05}",
              Instant.now(),
              null,
              null));
      service.transitionStage(mv.id(), Stage.CHAMPION);
    } catch (Exception e) {
      throw new IllegalStateException("failed to register the d39 fixture champion", e);
    }
  }

  private static String metadataJson() {
    StringBuilder means = new StringBuilder("[");
    StringBuilder stds = new StringBuilder("[");
    for (int i = 0; i < 15; i++) {
      means.append(i == 0 ? "0.0" : ",0.0");
      stds.append(i == 0 ? "1.0" : ",1.0");
    }
    means.append("]");
    stds.append("]");
    return "{\"model_name\":\"battedball_outcome\",\"feature_scaler\":{\"means\":"
        + means
        + ",\"stds\":"
        + stds
        + ",\"is_continuous\":[]}}";
  }

  private static String calibratorJson() {
    String identity = "{\"x_thresholds\":[0.0,1.0],\"y_thresholds\":[0.0,1.0]}";
    StringBuilder parkOrder = new StringBuilder("[");
    StringBuilder parks = new StringBuilder("{");
    for (int p = 0; p < N_PARKS; p++) {
      String name = String.format("PARK%02d", p);
      if (p > 0) {
        parkOrder.append(",");
        parks.append(",");
      }
      parkOrder.append("\"").append(name).append("\"");
      parks.append("\"").append(name).append("\":[");
      for (int o = 0; o < N_OUTCOMES; o++) {
        parks.append(o == 0 ? "" : ",").append(identity);
      }
      parks.append("]");
    }
    parkOrder.append("]");
    parks.append("}");
    return "{\"park_order\":"
        + parkOrder
        + ",\"outcome_order\":[\"out\",\"1b\",\"2b\",\"3b\",\"hr\"],\"parks\":"
        + parks
        + "}";
  }
}
