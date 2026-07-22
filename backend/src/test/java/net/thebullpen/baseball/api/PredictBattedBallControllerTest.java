package net.thebullpen.baseball.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MockMvc tests for {@code POST /v1/predict/batted-ball} - the single-park view of the {@code
 * battedball_outcome} champion.
 *
 * <p>"Retire the toy": this endpoint no longer serves {@code ToyBattedBallInference}. It serves the
 * registered per-park champion (one park of {@code /all-parks}'s output) and 503s with no champion.
 * So this test registers + promotes the same fixture champion the all-parks test uses, then asserts
 * a single park's HR probability; the no-champion path asserts 503 - a real degraded-path test
 * instead of a toy placeholder. Mirrors {@code PredictAllParksControllerTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"api", "registry-controller-it"})
class PredictBattedBallControllerTest {

  private static final Path REPO_ROOT = Path.of(System.getProperty("user.dir")).getParent();
  private static final Path CONTRACT =
      REPO_ROOT.resolve("contracts/feature_pipeline_battedball.json");
  private static final int N_PARKS = 30;
  private static final int N_OUTCOMES = 5;
  private static final String A_PARK = "PARK00";

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    Path dbPath =
        Path.of(
            System.getProperty("java.io.tmpdir"),
            "bullpen-battedball-it-" + UUID.randomUUID() + ".sqlite");
    String url = "jdbc:sqlite:" + dbPath;
    registry.add("spring.datasource.url", () -> url);
    registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
    registry.add("spring.flyway.url", () -> url);
    registry.add("bullpen.admin.basicauth", () -> "it-admin:it-password");
    Path snapshotBase =
        Path.of(
            System.getProperty("java.io.tmpdir"),
            "bullpen-battedball-it-snapshots-" + UUID.randomUUID());
    registry.add("bullpen.snapshot.local-base-path", snapshotBase::toString);
  }

  @Autowired private MockMvc mvc;
  @Autowired private RegistryService service;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper mapper;
  @Autowired private org.springframework.cache.CacheManager cacheManager;

  @TempDir Path artifactDir;

  @BeforeEach
  void resetRegistry() {
    jdbc.update("DELETE FROM experiment_results");
    // model_routing FIRST: promoting a champion writes a routing row (ensureRoutingForChampion).
    jdbc.update("DELETE FROM model_routing");
    jdbc.update("DELETE FROM model_versions");
    // Evict the @Cacheable routing cache too - a DB delete alone leaves RoutingService.findRouting
    // serving a stale routing row, so the no-champion 503 test would route to the still-cached
    // champion (200) instead of falling through to requireChampionId's 503. Order-independent.
    var routing = cacheManager.getCache("routing");
    if (routing != null) {
      routing.clear();
    }
  }

  @Test
  void serves_registered_champion_for_one_park() throws Exception {
    registerAndPromoteChampion();
    mvc.perform(
            post("/v1/predict/batted-ball")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBody("R", A_PARK)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.modelName").value("battedball_outcome"))
        .andExpect(jsonPath("$.modelVersion").value("v1"))
        .andExpect(jsonPath("$.probHr").isNumber())
        .andExpect(jsonPath("$.latencyMicros").isNumber());
  }

  @Test
  void returns_503_when_no_champion_and_no_routing_config() throws Exception {
    mvc.perform(
            post("/v1/predict/batted-ball")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBody("R", A_PARK)))
        .andExpect(status().isServiceUnavailable());
  }

  @Test
  void rejects_unknown_park_with_400() throws Exception {
    registerAndPromoteChampion();
    mvc.perform(
            post("/v1/predict/batted-ball")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBody("R", "ZZZ")))
        .andExpect(status().isBadRequest());
  }

  @Test
  void rejects_switch_hitter_with_400() throws Exception {
    mvc.perform(
            post("/v1/predict/batted-ball")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBody("S", A_PARK)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void rejects_missing_hit_distance_with_400() throws Exception {
    // hitDistanceFt is one of the champion inputs the retired toy request never had - its absence
    // must 400, not silently default to a confidently-wrong prediction.
    String body =
        mapper.writeValueAsString(
            Map.of(
                "launchSpeedMph",
                102.0,
                "launchAngleDeg",
                27.0,
                "sprayAngleDeg",
                5.0,
                "stand",
                "R",
                "baseState",
                0,
                "outs",
                1,
                "parkId",
                A_PARK));
    mvc.perform(
            post("/v1/predict/batted-ball").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest());
  }

  private String validBody(String stand, String parkId) throws Exception {
    return mapper.writeValueAsString(
        Map.of(
            "launchSpeedMph",
            102.0,
            "launchAngleDeg",
            27.0,
            "sprayAngleDeg",
            5.0,
            "hitDistanceFt",
            401.0,
            "stand",
            stand,
            "baseState",
            0,
            "outs",
            1,
            "parkId",
            parkId));
  }

  /** Register + promote the same fixture per-park champion the all-parks controller test uses. */
  private void registerAndPromoteChampion() throws Exception {
    Path artifact = artifactDir.resolve("model.onnx");
    URL onnx = getClass().getResource("/onnx/battedball_park_outcome_fixture.onnx");
    Files.copy(
        Path.of(Objects.requireNonNull(onnx, "fixture missing from classpath").toURI()), artifact);
    Path metadata = artifactDir.resolve("metadata.json");
    Files.writeString(metadata, metadataJson());
    Path pipeline = artifactDir.resolve("feature_pipeline.json");
    Files.copy(CONTRACT, pipeline);
    Files.writeString(artifactDir.resolve("calibrator.json"), calibratorJson());

    RegisterRequest req =
        new RegisterRequest(
            "battedball_outcome",
            "v1",
            artifact.toString(),
            metadata.toString(),
            pipeline.toString(),
            "train-h-singlepark",
            "[2024-01-01,2024-12-31]",
            "{\"ece\":0.03}",
            Instant.now(),
            null,
            null);
    ModelVersion mv = service.register(req);
    // Rule 9: the co-registered baseline must exist before the primary reaches CHAMPION.
    service.register(
        new RegisterRequest(
            "lr_baseline_batted_ball",
            "v1",
            artifact.toString(),
            metadata.toString(),
            pipeline.toString(),
            "train-h-singlepark-baseline",
            "[2024-01-01,2024-12-31]",
            "{\"ece\":0.05}",
            Instant.now(),
            null,
            null));
    // First-ever champion for this model: no experiment gate.
    service.transitionStage(mv.id(), Stage.CHAMPION);
  }

  /** model_name + a 15-feature identity scaler (means 0 / stds 1) so raw features pass through. */
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

  /** 30 parks x 5 identity isotonic calibrators (x_thresholds == y_thresholds == [0,1]). */
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
