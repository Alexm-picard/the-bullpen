package net.thebullpen.baseball.api.admin;

import static org.hamcrest.Matchers.equalTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.thebullpen.baseball.registry.RegistryService;
import net.thebullpen.baseball.registry.dto.ModelVersion;
import net.thebullpen.baseball.registry.dto.RegisterRequest;
import net.thebullpen.baseball.registry.dto.Stage;
import net.thebullpen.baseball.registry.experiment.PairedPrediction;
import net.thebullpen.baseball.registry.experiment.PairedPredictionFetcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** HTTP-layer tests for {@link ExperimentAdminController} — auth boundary + endpoint shape. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"api", "registry-controller-it"})
@Import(ExperimentAdminControllerIT.TestFetcherConfig.class)
class ExperimentAdminControllerIT {

  private static final String ADMIN_USER = "it-admin";
  private static final String ADMIN_PASS = "it-password";
  private static final String BASIC =
      "Basic "
          + Base64.getEncoder()
              .encodeToString((ADMIN_USER + ":" + ADMIN_PASS).getBytes(StandardCharsets.UTF_8));

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    Path dbPath =
        Path.of(
            System.getProperty("java.io.tmpdir"),
            "bullpen-exp-ctrl-it-" + UUID.randomUUID() + ".sqlite");
    String url = "jdbc:sqlite:" + dbPath;
    registry.add("spring.datasource.url", () -> url);
    registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
    registry.add("spring.flyway.url", () -> url);
    registry.add("bullpen.admin.basicauth", () -> ADMIN_USER + ":" + ADMIN_PASS);
    Path snapshotBase =
        Path.of(
            System.getProperty("java.io.tmpdir"),
            "bullpen-exp-ctrl-it-snapshots-" + UUID.randomUUID());
    registry.add("bullpen.snapshot.local-base-path", snapshotBase::toString);
  }

  @TestConfiguration
  static class TestFetcherConfig {
    @Bean
    @Primary
    PairedPredictionFetcher controllableFetcher() {
      return new java.util.function.Supplier<PairedPredictionFetcher>() {
        @Override
        public PairedPredictionFetcher get() {
          return (modelName, championVersion, challengerVersion, since, until) -> List.of();
        }
      }.get();
    }
  }

  @Autowired private MockMvc mvc;
  @Autowired private RegistryService registry;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper mapper;

  @TempDir Path artifactDir;

  @BeforeEach
  void reset() {
    jdbc.update("DELETE FROM experiment_results");
    jdbc.update("DELETE FROM model_routing");
    jdbc.update("DELETE FROM model_versions");
  }

  @Test
  void list_without_credentials_is_unauthorized() throws Exception {
    mvc.perform(get("/v1/admin/experiments").param("modelName", "any"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void list_with_credentials_returns_empty_array_for_unknown_model() throws Exception {
    mvc.perform(
            get("/v1/admin/experiments")
                .param("modelName", "no_model")
                .header("Authorization", BASIC))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()", equalTo(0)));
  }

  @Test
  void start_returns_running_row() throws Exception {
    long champ = registerAndPromoteChampion("ec_start_model");
    long chall = registerShadow("ec_start_model", champ);

    mvc.perform(
            post("/v1/admin/experiments/start")
                .header("Authorization", BASIC)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        Map.of(
                            "modelName",
                            "ec_start_model",
                            "championVersionId",
                            champ,
                            "challengerVersionId",
                            chall,
                            "primaryMetric",
                            "BRIER",
                            "primaryThreshold",
                            0.005,
                            "sampleSizeTarget",
                            10,
                            "guardrails",
                            Map.of(),
                            "reason",
                            "v2 looks promising"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status", equalTo("RUNNING")))
        .andExpect(jsonPath("$.modelName", equalTo("ec_start_model")));
  }

  @Test
  void start_with_existing_running_experiment_is_409() throws Exception {
    long champ = registerAndPromoteChampion("ec_dup_model");
    long chall = registerShadow("ec_dup_model", champ);
    startExperiment("ec_dup_model", champ, chall);

    mvc.perform(
            post("/v1/admin/experiments/start")
                .header("Authorization", BASIC)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        Map.of(
                            "modelName",
                            "ec_dup_model",
                            "championVersionId",
                            champ,
                            "challengerVersionId",
                            chall,
                            "primaryMetric",
                            "BRIER",
                            "primaryThreshold",
                            0.005,
                            "sampleSizeTarget",
                            10,
                            "guardrails",
                            Map.of(),
                            "reason",
                            "second attempt"))))
        .andExpect(status().isConflict());
  }

  @Test
  void evaluate_for_running_experiment_returns_verdict() throws Exception {
    long champ = registerAndPromoteChampion("ec_ev_model");
    long chall = registerShadow("ec_ev_model", champ);
    long expId = startExperiment("ec_ev_model", champ, chall);

    mvc.perform(post("/v1/admin/experiments/" + expId + "/evaluate").header("Authorization", BASIC))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.outcome").exists())
        .andExpect(jsonPath("$.sampleSizeObserved").exists());
  }

  @Test
  void evaluate_for_unknown_id_is_404() throws Exception {
    mvc.perform(post("/v1/admin/experiments/99999/evaluate").header("Authorization", BASIC))
        .andExpect(status().isNotFound());
  }

  @Test
  void complete_with_insufficient_sample_is_409() throws Exception {
    // Fetcher returns empty list → sample size 0 < target 10 → 409.
    long champ = registerAndPromoteChampion("ec_few_model");
    long chall = registerShadow("ec_few_model", champ);
    long expId = startExperiment("ec_few_model", champ, chall);

    mvc.perform(post("/v1/admin/experiments/" + expId + "/complete").header("Authorization", BASIC))
        .andExpect(status().isConflict());
  }

  @Test
  void abort_running_experiment_returns_aborted() throws Exception {
    long champ = registerAndPromoteChampion("ec_abort_model");
    long chall = registerShadow("ec_abort_model", champ);
    long expId = startExperiment("ec_abort_model", champ, chall);

    mvc.perform(
            post("/v1/admin/experiments/" + expId + "/abort")
                .header("Authorization", BASIC)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("reason", "regretted starting it"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status", equalTo("ABORTED")));
  }

  @Test
  void abort_terminal_experiment_is_409() throws Exception {
    long champ = registerAndPromoteChampion("ec_doubleabort_model");
    long chall = registerShadow("ec_doubleabort_model", champ);
    long expId = startExperiment("ec_doubleabort_model", champ, chall);

    mvc.perform(
            post("/v1/admin/experiments/" + expId + "/abort")
                .header("Authorization", BASIC)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("reason", "first abort"))))
        .andExpect(status().isOk());
    mvc.perform(
            post("/v1/admin/experiments/" + expId + "/abort")
                .header("Authorization", BASIC)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("reason", "second abort"))))
        .andExpect(status().isConflict());
  }

  // --- helpers ----------------------------------------------------------

  private long startExperiment(String modelName, long champ, long chall) throws Exception {
    String body =
        mapper.writeValueAsString(
            Map.of(
                "modelName",
                modelName,
                "championVersionId",
                champ,
                "challengerVersionId",
                chall,
                "primaryMetric",
                "BRIER",
                "primaryThreshold",
                0.005,
                "sampleSizeTarget",
                10,
                "guardrails",
                Map.of(),
                "reason",
                "test exp"));
    var mvcResult =
        mvc.perform(
                post("/v1/admin/experiments/start")
                    .header("Authorization", BASIC)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isOk())
            .andReturn();
    return mapper.readTree(mvcResult.getResponse().getContentAsString()).get("id").asLong();
  }

  private long registerAndPromoteChampion(String modelName) throws Exception {
    ModelVersion v = registry.register(sampleRequest(modelName, "v1"));
    registry.transitionStage(v.id(), Stage.CHAMPION);
    return v.id();
  }

  private long registerShadow(String modelName, long championId) throws Exception {
    ModelVersion v = registry.register(sampleRequest(modelName, "v2"));
    registry.transitionStage(v.id(), Stage.SHADOW);
    return v.id();
  }

  private RegisterRequest sampleRequest(String modelName, String version) throws Exception {
    Path artifact = artifactDir.resolve(modelName + "-" + version + "-model.onnx");
    Files.writeString(artifact, "stub");
    Path metadata = artifactDir.resolve(modelName + "-" + version + "-metadata.json");
    Files.writeString(metadata, "{}");
    Path pipeline = artifactDir.resolve(modelName + "-" + version + "-pipeline.json");
    Files.writeString(
        pipeline,
        "{\"model_name\":\""
            + modelName
            + "\",\"pipeline_version\":\"1\",\"feature_order\":[\"x\"],\"schema_hash\":\"\"}");
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
        "exp-ctrl-it",
        "registered by ExperimentAdminControllerIT");
  }

  /** Silence unused-import warning in test file. */
  @SuppressWarnings("unused")
  private static List<PairedPrediction> dummyPairs() {
    return List.of();
  }
}
