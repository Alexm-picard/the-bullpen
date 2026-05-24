package net.thebullpen.baseball.api.admin;

import static org.hamcrest.Matchers.equalTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
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
 * End-to-end HTTP tests for {@link RegistryAdminController} + {@link
 * net.thebullpen.baseball.api.ops.RegistryOpsController}.
 *
 * <p>Runs the real Spring Security filter chain so the auth boundary (rule G10 / decision [29]) is
 * exercised — not stubbed past a {@code @WebMvcTest} slice. The cost is a full SpringBootTest
 * context; the value is the assurance that {@code /v1/admin/**} really does return 401 without
 * Basic creds and that the credentials wired through {@code bullpen.admin.basicauth} are honored.
 *
 * <p>SQLite isolation + the admin credential are both injected via {@link DynamicPropertySource} so
 * each test JVM gets a fresh DB and the test-only creds {@code it-admin:it-password} are scoped to
 * this class.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"api", "registry-controller-it"})
class RegistryControllerIT {

  private static final String ADMIN_USER = "it-admin";
  private static final String ADMIN_PASS = "it-password";
  private static final String BASIC =
      "Basic "
          + java.util.Base64.getEncoder()
              .encodeToString((ADMIN_USER + ":" + ADMIN_PASS).getBytes());

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    Path dbPath =
        Path.of(
            System.getProperty("java.io.tmpdir"),
            "bullpen-registry-ctrl-it-" + UUID.randomUUID() + ".sqlite");
    String url = "jdbc:sqlite:" + dbPath;
    registry.add("spring.datasource.url", () -> url);
    registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
    registry.add("spring.flyway.url", () -> url);
    registry.add("bullpen.admin.basicauth", () -> ADMIN_USER + ":" + ADMIN_PASS);
  }

  @Autowired private MockMvc mvc;
  @Autowired private RegistryService service;
  @Autowired private JdbcTemplate jdbc;
  // Use Spring's auto-configured mapper — it has JSR-310 wired up, so Instant
  // serializes correctly through the same code path the controller uses.
  @Autowired private ObjectMapper mapper;

  @TempDir Path artifactDir;

  @BeforeEach
  void resetRegistry() {
    jdbc.update("DELETE FROM experiment_results");
    jdbc.update("DELETE FROM model_versions");
  }

  // --- auth boundary -----------------------------------------------------

  @Test
  void admin_list_without_credentials_is_unauthorized() throws Exception {
    mvc.perform(get("/v1/admin/registry/any_model")).andExpect(status().isUnauthorized());
  }

  @Test
  void admin_list_with_wrong_credentials_is_unauthorized() throws Exception {
    String bad = "Basic " + java.util.Base64.getEncoder().encodeToString("wrong:nope".getBytes());
    mvc.perform(get("/v1/admin/registry/any_model").header("Authorization", bad))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void admin_list_with_valid_credentials_returns_200_and_empty_for_unknown_model()
      throws Exception {
    mvc.perform(get("/v1/admin/registry/unknown_model").header("Authorization", BASIC))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()", equalTo(0)));
  }

  @Test
  void ops_list_is_publicly_readable_without_credentials() throws Exception {
    mvc.perform(get("/v1/ops/registry/any_model"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()", equalTo(0)));
  }

  // --- register endpoint -------------------------------------------------

  @Test
  void admin_register_round_trip_returns_candidate_row() throws Exception {
    RegisterRequest req = sampleRequest("ctrl_model", "v1");
    mvc.perform(
            post("/v1/admin/registry/ctrl_model/register")
                .header("Authorization", BASIC)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(req)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.modelName", equalTo("ctrl_model")))
        .andExpect(jsonPath("$.version", equalTo("v1")))
        .andExpect(jsonPath("$.stage", equalTo("CANDIDATE")));
  }

  @Test
  void admin_register_with_path_body_modelName_mismatch_is_400() throws Exception {
    RegisterRequest req = sampleRequest("body_model", "v1");
    mvc.perform(
            post("/v1/admin/registry/path_model/register")
                .header("Authorization", BASIC)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(req)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void admin_register_with_missing_artifact_is_422() throws Exception {
    Path metadata = writeFile("missing-artifact-meta.json", "{}");
    Path pipeline = writeFile("missing-artifact-pipeline.json", featurePipelineJson("salt-x"));
    RegisterRequest req =
        new RegisterRequest(
            "ctrl_model",
            "v-bad",
            "/no/such/model.onnx",
            metadata.toString(),
            pipeline.toString(),
            "h",
            "[2024-01-01,2024-12-31]",
            "{}",
            Instant.now(),
            null,
            null);
    mvc.perform(
            post("/v1/admin/registry/ctrl_model/register")
                .header("Authorization", BASIC)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(req)))
        .andExpect(status().isUnprocessableEntity());
  }

  // --- promote endpoint --------------------------------------------------

  @Test
  void admin_promote_first_ever_to_champion_succeeds_without_experiment_row() throws Exception {
    ModelVersion mv = service.register(sampleRequest("bootstrap_promo_model", "v1"));
    mvc.perform(
            post("/v1/admin/registry/bootstrap_promo_model/promote/" + mv.id())
                .header("Authorization", BASIC)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        Map.of(
                            "targetStage", "champion",
                            "reason", "first version of new model"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.stage", equalTo("CHAMPION")));
  }

  @Test
  void admin_promote_to_champion_without_experiment_row_is_409() throws Exception {
    ModelVersion v1 = service.register(sampleRequest("gated_promo_model", "v1"));
    service.transitionStage(v1.id(), Stage.CHAMPION);
    ModelVersion v2 = service.register(sampleRequest("gated_promo_model", "v2"));
    service.transitionStage(v2.id(), Stage.SHADOW);

    mvc.perform(
            post("/v1/admin/registry/gated_promo_model/promote/" + v2.id())
                .header("Authorization", BASIC)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        Map.of(
                            "targetStage", "champion",
                            "reason", "trying without experiment"))))
        .andExpect(status().isConflict());

    // gate must leave the challenger at SHADOW, prior champion in place
    org.assertj.core.api.Assertions.assertThat(service.getById(v2.id()))
        .map(ModelVersion::stage)
        .contains(Stage.SHADOW);
  }

  @Test
  void admin_promote_to_champion_with_experiment_row_succeeds() throws Exception {
    ModelVersion v1 = service.register(sampleRequest("happy_promo_model", "v1"));
    service.transitionStage(v1.id(), Stage.CHAMPION);
    ModelVersion v2 = service.register(sampleRequest("happy_promo_model", "v2"));
    service.transitionStage(v2.id(), Stage.SHADOW);
    seedPassingExperiment("happy_promo_model", v1.id(), v2.id());

    mvc.perform(
            post("/v1/admin/registry/happy_promo_model/promote/" + v2.id())
                .header("Authorization", BASIC)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        Map.of(
                            "targetStage", "champion",
                            "reason", "passed brier-on-held-out by 1.3 pts"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.stage", equalTo("CHAMPION")));
  }

  @Test
  void admin_promote_with_unknown_version_id_is_404() throws Exception {
    mvc.perform(
            post("/v1/admin/registry/any_model/promote/99999")
                .header("Authorization", BASIC)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        Map.of(
                            "targetStage", "shadow",
                            "reason", "nope"))))
        .andExpect(status().isNotFound());
  }

  @Test
  void admin_promote_with_invalid_target_stage_is_400() throws Exception {
    ModelVersion mv = service.register(sampleRequest("bad_target_model", "v1"));
    mvc.perform(
            post("/v1/admin/registry/bad_target_model/promote/" + mv.id())
                .header("Authorization", BASIC)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        Map.of(
                            "targetStage", "demoted",
                            "reason", "nope"))))
        .andExpect(status().isBadRequest());
  }

  // --- ops read ----------------------------------------------------------

  @Test
  void ops_get_by_id_returns_row_when_model_matches() throws Exception {
    ModelVersion mv = service.register(sampleRequest("ops_model", "v1"));
    mvc.perform(get("/v1/ops/registry/ops_model/" + mv.id()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id", equalTo((int) mv.id())))
        .andExpect(jsonPath("$.modelName", equalTo("ops_model")));
  }

  @Test
  void ops_get_by_id_with_unknown_id_is_404() throws Exception {
    mvc.perform(get("/v1/ops/registry/any_model/99999")).andExpect(status().isNotFound());
  }

  @Test
  void ops_get_by_id_with_model_mismatch_is_404() throws Exception {
    ModelVersion mv = service.register(sampleRequest("real_model", "v1"));
    mvc.perform(get("/v1/ops/registry/other_model/" + mv.id())).andExpect(status().isNotFound());
  }

  // --- helpers -----------------------------------------------------------

  private void seedPassingExperiment(
      String modelName, long championVersionId, long challengerVersionId) {
    jdbc.update(
        "INSERT INTO experiment_results (model_name, champion_version_id, challenger_version_id,"
            + " started_at, ended_at, primary_metric, primary_threshold, guardrails,"
            + " sample_size_target, sample_size_observed, champion_metric, challenger_metric,"
            + " guardrails_observed, status, notes)"
            + " VALUES (?, ?, ?, ?, ?, 'brier', 0.20, '{}', 10000, 12345, 0.185, 0.172, '{}',"
            + " 'passed', 'seeded by RegistryControllerIT')",
        modelName,
        championVersionId,
        challengerVersionId,
        Timestamp.from(Instant.now().minusSeconds(7200)),
        Timestamp.from(Instant.now().minusSeconds(60)));
  }

  private static String featurePipelineJson(String salt) {
    return "{\n"
        + "  \"model_name\": \"ctrl_model\",\n"
        + "  \"pipeline_version\": \"1.0.0\",\n"
        + "  \"salt\": \""
        + salt
        + "\",\n"
        + "  \"feature_order\": [\"a\", \"b\", \"c\"],\n"
        + "  \"schema_hash\": \"recomputed-on-load\"\n"
        + "}\n";
  }

  private Path writeFile(String name, String content) throws Exception {
    Path p = artifactDir.resolve(name);
    Files.writeString(p, content);
    return p;
  }

  private RegisterRequest sampleRequest(String modelName, String version) throws Exception {
    Path artifact = writeFile(modelName + "-" + version + "-model.onnx", "stub");
    Path metadata = writeFile(modelName + "-" + version + "-meta.json", "{}");
    Path pipeline =
        writeFile(modelName + "-" + version + "-pipeline.json", featurePipelineJson(modelName));
    return new RegisterRequest(
        modelName,
        version,
        artifact.toString(),
        metadata.toString(),
        pipeline.toString(),
        "train-h-" + version,
        "[2024-01-01,2024-12-31]",
        "{\"brier\":0.18}",
        Instant.now(),
        "controller-it",
        "registered by RegistryControllerIT");
  }
}
