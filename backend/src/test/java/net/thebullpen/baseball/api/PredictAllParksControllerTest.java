package net.thebullpen.baseball.api;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
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
 * Serving round-trip for {@code POST /v1/predict/batted-ball/all-parks} (B4, decision [146]).
 * Registers a real {@code battedball_outcome} snapshot (the {@code [None,15]->[None,30,5]} ONNX
 * fixture + the committed contract + an identity scaler + identity per-park calibrators), promotes
 * it to CHAMPION, and asserts the endpoint serves the registered model (not the toy) with a 30-park
 * HR map. Also covers the no-champion 503 fallback and request validation. Mirrors {@code
 * RegistryControllerIT}'s fresh-tmp-SQLite + snapshot-base isolation.
 *
 * <p>This is the CI form of the handoff's BUG-1 / BUG-1c verification: register -> serve, asserting
 * the served identity is the registered model and that the (BUG-1c) calibrator landed in the
 * snapshot so the model loads + serves calibrated.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"api", "registry-controller-it"})
class PredictAllParksControllerTest {

  private static final Path REPO_ROOT = Path.of(System.getProperty("user.dir")).getParent();
  private static final Path CONTRACT =
      REPO_ROOT.resolve("contracts/feature_pipeline_battedball.json");
  private static final int N_PARKS = 30;
  private static final int N_OUTCOMES = 5;

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    Path dbPath =
        Path.of(
            System.getProperty("java.io.tmpdir"),
            "bullpen-allparks-it-" + UUID.randomUUID() + ".sqlite");
    String url = "jdbc:sqlite:" + dbPath;
    registry.add("spring.datasource.url", () -> url);
    registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
    registry.add("spring.flyway.url", () -> url);
    registry.add("bullpen.admin.basicauth", () -> "it-admin:it-password");
    Path snapshotBase =
        Path.of(
            System.getProperty("java.io.tmpdir"),
            "bullpen-allparks-it-snapshots-" + UUID.randomUUID());
    registry.add("bullpen.snapshot.local-base-path", snapshotBase::toString);
  }

  @Autowired private MockMvc mvc;
  @Autowired private RegistryService service;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper mapper;

  @TempDir Path artifactDir;

  @BeforeEach
  void resetRegistry() {
    jdbc.update("DELETE FROM experiment_results");
    jdbc.update("DELETE FROM model_versions");
  }

  @Test
  void serves_registered_champion_with_30_park_hr_map() throws Exception {
    registerAndPromoteChampion();
    mvc.perform(
            post("/v1/predict/batted-ball/all-parks")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBody("R")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.modelName").value("battedball_outcome"))
        .andExpect(jsonPath("$.modelVersion").value("v1"))
        .andExpect(jsonPath("$.probHrByPark.length()").value(equalTo(N_PARKS)))
        .andExpect(jsonPath("$.probHrByPark.PARK00").value(greaterThanOrEqualTo(0.0)))
        .andExpect(jsonPath("$.latencyMicros").value(greaterThanOrEqualTo(0)));
  }

  @Test
  void returns_503_when_no_champion_and_no_routing_config() throws Exception {
    mvc.perform(
            post("/v1/predict/batted-ball/all-parks")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBody("R")))
        .andExpect(status().isServiceUnavailable());
  }

  @Test
  void rejects_switch_hitter_with_400() throws Exception {
    mvc.perform(
            post("/v1/predict/batted-ball/all-parks")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBody("S")))
        .andExpect(status().isBadRequest());
  }

  @Test
  void rejects_missing_launch_speed_with_400() throws Exception {
    String body =
        mapper.writeValueAsString(
            Map.of(
                "launchAngleDeg",
                27.0,
                "sprayAngleDeg",
                5.0,
                "hitDistanceFt",
                401.0,
                "stand",
                "R",
                "baseState",
                0,
                "outs",
                1));
    mvc.perform(
            post("/v1/predict/batted-ball/all-parks")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest());
  }

  private String validBody(String stand) throws Exception {
    return mapper.writeValueAsString(
        Map.of(
            "launchSpeedMph", 102.0,
            "launchAngleDeg", 27.0,
            "sprayAngleDeg", 5.0,
            "hitDistanceFt", 401.0,
            "stand", stand,
            "baseState", 0,
            "outs", 1));
  }

  private void registerAndPromoteChampion() throws Exception {
    Path artifact = artifactDir.resolve("model.onnx");
    URL onnx = getClass().getResource("/onnx/battedball_park_outcome_fixture.onnx");
    Files.copy(
        Path.of(Objects.requireNonNull(onnx, "fixture missing from classpath").toURI()), artifact);
    Path metadata = artifactDir.resolve("metadata.json");
    Files.writeString(metadata, metadataJson());
    Path pipeline = artifactDir.resolve("feature_pipeline.json");
    Files.copy(CONTRACT, pipeline);
    // BUG-1c: a calibrator.json beside the artifact must be copied into the snapshot, or the model
    // serves uncalibrated. Named exactly calibrator.json so RegistryService.register picks it up.
    Files.writeString(artifactDir.resolve("calibrator.json"), calibratorJson());

    RegisterRequest req =
        new RegisterRequest(
            "battedball_outcome",
            "v1",
            artifact.toString(),
            metadata.toString(),
            pipeline.toString(),
            "train-h-allparks",
            "[2024-01-01,2024-12-31]",
            "{\"ece\":0.03}",
            Instant.now(),
            null,
            null);
    ModelVersion mv = service.register(req);
    // First-ever champion for this model: no experiment gate (RegistryServiceIT).
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
