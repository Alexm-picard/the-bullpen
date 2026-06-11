package net.thebullpen.baseball.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import net.thebullpen.baseball.inference.AsyncPredictionLogger;
import net.thebullpen.baseball.inference.PredictionLogEvent;
import net.thebullpen.baseball.inference.PredictionLogWriter;
import net.thebullpen.baseball.inference.routing.RoutingService;
import net.thebullpen.baseball.registry.RegistryService;
import net.thebullpen.baseball.registry.dto.ModelVersion;
import net.thebullpen.baseball.registry.dto.RegisterRequest;
import net.thebullpen.baseball.registry.dto.Stage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Registry-routed serving round-trip for {@code POST /v1/predict/pitch} (W1, BUG-2). Registers a
 * real {@code pitch_outcome_pre} snapshot (the tiny {@code [None,31]->[None,5]} ORT fixture + the
 * committed contract + an identity calibrator + minimal Tier-2 lookups), promotes it to CHAMPION,
 * and asserts the endpoint serves THROUGH {@link net.thebullpen.baseball.inference.InferenceRouter}
 * and logs the CHAMPION row with the REAL {@code model_version_id} FK (not the old hardcoded-name,
 * null-FK path).
 *
 * <p>Real ORT-Java session loading a small fixture model (no mocked ONNX, per the testing posture).
 * The prediction log is captured by a primary {@link CapturingLogger} subclass so the FK is
 * asserted in CI without a ClickHouse round-trip.
 *
 * <p>Covers:
 *
 * <ul>
 *   <li>champion serves through the router, CHAMPION row carries the registered version's FK;
 *   <li>no champion / no routing returns 503 (NOT 404);
 *   <li>SHADOW challenger runs in parallel and is logged with the challenger's FK (rule 9 keeps it
 *       under the same {@code pitch_outcome_pre} model name).
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"api", "registry-controller-it"})
class PredictPitchRoutingIT {

  private static final Path REPO_ROOT = Path.of(System.getProperty("user.dir")).getParent();
  private static final Path CONTRACT = REPO_ROOT.resolve("contracts/feature_pipeline.json");
  private static final String MODEL_NAME = "pitch_outcome_pre";

  /** Captures every enqueued event so the routed FK can be asserted without ClickHouse. */
  static final class CapturingLogger extends AsyncPredictionLogger {
    final List<PredictionLogEvent> events = new CopyOnWriteArrayList<>();

    CapturingLogger(Optional<PredictionLogWriter> writer, MeterRegistry registry) {
      super(writer, registry, 20000);
    }

    @Override
    public void enqueue(PredictionLogEvent event) {
      events.add(event);
      super.enqueue(event);
    }
  }

  @TestConfiguration
  static class CaptureConfig {
    @Bean
    @Primary
    CapturingLogger capturingLogger(Optional<PredictionLogWriter> writer, MeterRegistry registry) {
      return new CapturingLogger(writer, registry);
    }
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    Path dbPath =
        Path.of(
            System.getProperty("java.io.tmpdir"),
            "bullpen-pitch-routing-it-" + UUID.randomUUID() + ".sqlite");
    String url = "jdbc:sqlite:" + dbPath;
    registry.add("spring.datasource.url", () -> url);
    registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
    registry.add("spring.flyway.url", () -> url);
    registry.add("bullpen.admin.basicauth", () -> "it-admin:it-password");
    Path snapshotBase =
        Path.of(
            System.getProperty("java.io.tmpdir"),
            "bullpen-pitch-routing-it-snapshots-" + UUID.randomUUID());
    registry.add("bullpen.snapshot.local-base-path", snapshotBase::toString);
  }

  @Autowired private MockMvc mvc;
  @Autowired private RegistryService registryService;
  @Autowired private RoutingService routingService;
  @Autowired private CapturingLogger logger;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private CacheManager cacheManager;
  @Autowired private ObjectMapper mapper;

  @TempDir Path sourceDir;

  @BeforeEach
  void reset() {
    jdbc.update("DELETE FROM experiment_results");
    jdbc.update("DELETE FROM model_routing");
    jdbc.update("DELETE FROM model_versions");
    // Routing cache survives across tests in the shared JVM context - purge so prior RoutingConfig
    // entries don't bleed into the next test's findRouting().
    cacheManager
        .getCacheNames()
        .forEach(
            name -> {
              var c = cacheManager.getCache(name);
              if (c != null) {
                c.clear();
              }
            });
    logger.events.clear();
  }

  @Test
  void serves_registered_champion_through_router_and_logs_real_version_fk() throws Exception {
    registerLrBaseline(); // B4: pre cannot reach CHAMPION without its baseline registered
    long championId = registerPitchVersion("v1");
    registryService.transitionStage(championId, Stage.CHAMPION);

    mvc.perform(
            post("/v1/predict/pitch").contentType(MediaType.APPLICATION_JSON).content(validBody()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.modelName").value(MODEL_NAME))
        .andExpect(jsonPath("$.modelVersion").value("v1"))
        .andExpect(jsonPath("$.probabilities.ball").isNumber())
        .andExpect(jsonPath("$.winner").isString());

    PredictionLogEvent champEvent =
        logger.events.stream()
            .filter(e -> e.role() == PredictionLogEvent.Role.CHAMPION)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no CHAMPION prediction-log event captured"));
    assertThat(champEvent.modelName()).isEqualTo(MODEL_NAME);
    assertThat(champEvent.modelVersion()).isEqualTo("v1");
    assertThat(champEvent.modelVersionId())
        .as("W1: the routed CHAMPION row carries the registered version FK, not null")
        .isEqualTo(championId);
  }

  @Test
  void returns_503_when_no_champion_and_no_routing_config() throws Exception {
    mvc.perform(
            post("/v1/predict/pitch").contentType(MediaType.APPLICATION_JSON).content(validBody()))
        .andExpect(status().isServiceUnavailable());
  }

  @Test
  void shadow_challenger_runs_in_parallel_and_is_logged_with_its_own_fk() throws Exception {
    registerLrBaseline(); // B4: pre cannot reach CHAMPION without its baseline registered
    long championId = registerPitchVersion("v1");
    registryService.transitionStage(championId, Stage.CHAMPION);
    long challengerId = registerPitchVersion("v2");
    registryService.transitionStage(challengerId, Stage.SHADOW);
    routingService.setChallenger(MODEL_NAME, challengerId);

    mvc.perform(
            post("/v1/predict/pitch").contentType(MediaType.APPLICATION_JSON).content(validBody()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.modelVersion").value("v1")); // champion serves the user

    PredictionLogEvent shadowEvent =
        logger.events.stream()
            .filter(e -> e.role() == PredictionLogEvent.Role.SHADOW)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no SHADOW prediction-log event captured"));
    assertThat(shadowEvent.modelName()).isEqualTo(MODEL_NAME); // rule 9: same model name
    assertThat(shadowEvent.modelVersion()).isEqualTo("v2");
    assertThat(shadowEvent.modelVersionId())
        .as("the SHADOW row carries the challenger's FK")
        .isEqualTo(challengerId);
  }

  // --- helpers ----------------------------------------------------------

  /**
   * Register a {@code pitch_outcome_pre} version from the committed ORT fixture + contract, placing
   * the Tier-2 lookups the pre pipeline reads from the snapshot. The registry's placeArtifacts only
   * relocates model.onnx + metadata.json + feature_pipeline.json + calibrator.json, so the lookups
   * are copied into the snapshot post-registration - exactly what the W4 registration scaffolding
   * must also do for a real pitch snapshot.
   */
  private long registerPitchVersion(String version) throws Exception {
    Path versionSource = Files.createDirectories(sourceDir.resolve(version));
    Path artifact = versionSource.resolve("model.onnx");
    URL onnx = getClass().getResource("/onnx/pitch_outcome_fixture.onnx");
    Files.copy(
        Path.of(Objects.requireNonNull(onnx, "pitch fixture missing from classpath").toURI()),
        artifact);
    Path metadata = versionSource.resolve("metadata.json");
    Files.writeString(metadata, metadataJson());
    Path pipeline = versionSource.resolve("feature_pipeline.json");
    Files.copy(CONTRACT, pipeline);
    Files.writeString(versionSource.resolve("calibrator.json"), identityCalibratorJson());

    RegisterRequest req =
        new RegisterRequest(
            MODEL_NAME,
            version,
            artifact.toString(),
            metadata.toString(),
            pipeline.toString(),
            "train-h-pitch-" + version,
            "[2015-01-01,2023-12-31]",
            "{\"ece\":0.0035}",
            java.time.Instant.now(),
            "pitch-routing-it",
            "registered by PredictPitchRoutingIT");
    ModelVersion mv = registryService.register(req);
    placeLookups(mv.id());
    return mv.id();
  }

  /**
   * B4 (rule 9): {@code pitch_outcome_pre} cannot reach CHAMPION without its partner LR baseline
   * registered. Minimal registration - same canonical contract family (feature_pipeline.json), so
   * the same fixture files satisfy both the B1 canonical gate and the artifact checks. Mirrors
   * production truth: the box registry carries {@code pitch_outcome_lr_baseline} as a shadow.
   */
  private void registerLrBaseline() throws Exception {
    Path baselineSource = Files.createDirectories(sourceDir.resolve("lr-baseline"));
    Path artifact = baselineSource.resolve("model.onnx");
    URL onnx = getClass().getResource("/onnx/pitch_outcome_fixture.onnx");
    Files.copy(
        Path.of(Objects.requireNonNull(onnx, "pitch fixture missing from classpath").toURI()),
        artifact);
    Path metadata = baselineSource.resolve("metadata.json");
    Files.writeString(metadata, "{\"model_name\":\"pitch_outcome_lr_baseline\"}");
    Path pipeline = baselineSource.resolve("feature_pipeline.json");
    Files.copy(CONTRACT, pipeline);
    registryService.register(
        new RegisterRequest(
            "pitch_outcome_lr_baseline",
            "v1",
            artifact.toString(),
            metadata.toString(),
            pipeline.toString(),
            "train-h-pitch-lr",
            "[2015-01-01,2023-12-31]",
            "{\"ece\":0.012}",
            java.time.Instant.now(),
            "pitch-routing-it",
            "baseline registered by PredictPitchRoutingIT (rule 9)"));
  }

  /**
   * Copy the Tier-2 lookups into the registered snapshot dir (placeArtifacts doesn't move them).
   */
  private void placeLookups(long versionId) throws Exception {
    ModelVersion mv = registryService.getById(versionId).orElseThrow();
    Path snapshotDir = Path.of(mv.artifactPath()).getParent();
    Files.writeString(snapshotDir.resolve("park_id_mapping.json"), parkLookupJson());
    Files.writeString(snapshotDir.resolve("pitcher_te.json"), teLookupJson("pitcher_id"));
    Files.writeString(snapshotDir.resolve("batter_te.json"), teLookupJson("batter_id"));
  }

  private String validBody() throws Exception {
    return mapper.writeValueAsString(
        Map.ofEntries(
            Map.entry("countBalls", 1),
            Map.entry("countStrikes", 1),
            Map.entry("outs", 1),
            Map.entry("inning", 4),
            Map.entry("baseState", 0),
            Map.entry("scoreDiff", 0),
            Map.entry("dow", 3),
            Map.entry("pitcherThrows", "R"),
            Map.entry("batterStand", "L"),
            Map.entry("parkId", "NYY"),
            Map.entry("pitcherId", 545361L),
            Map.entry("batterId", 605141L)));
  }

  /** model_name + the calibrator pointer the loader reads from metadata. */
  private static String metadataJson() {
    return "{\"model_name\":\"pitch_outcome_pre\",\"model_version\":\"v1\","
        + "\"calibrator\":{\"path\":\"calibrator.json\"}}";
  }

  /** Identity isotonic calibrator over the 5 pitch classes (x == y == [0,1]). */
  private static String identityCalibratorJson() {
    String[] labels = {"ball", "called_strike", "swinging_strike", "foul", "in_play"};
    StringBuilder classLabels = new StringBuilder("[");
    StringBuilder breakpoints = new StringBuilder("[");
    for (int i = 0; i < labels.length; i++) {
      if (i > 0) {
        classLabels.append(",");
        breakpoints.append(",");
      }
      classLabels.append("\"").append(labels[i]).append("\"");
      breakpoints.append("{\"x_thresholds\":[0.0,1.0],\"y_thresholds\":[0.0,1.0]}");
    }
    classLabels.append("]");
    breakpoints.append("]");
    return "{\"class_labels\":" + classLabels + ",\"breakpoints\":" + breakpoints + "}";
  }

  /** Empty park mapping (forPark falls back to missing_value); the fixture ignores park_id_int. */
  private static String parkLookupJson() {
    return "{\"park_id\":{},\"missing_value\":-1}";
  }

  /** TE lookup with an all-zero prior and no rows (forEntity falls back to the prior). */
  private static String teLookupJson(String entityCol) {
    return "{\"entity_col\":\""
        + entityCol
        + "\",\"prior\":{\"ball\":0.0,\"called_strike\":0.0,\"swinging_strike\":0.0,"
        + "\"foul\":0.0,\"in_play\":0.0},\"rows\":[]}";
  }
}
