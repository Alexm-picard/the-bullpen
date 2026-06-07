package net.thebullpen.baseball.api.admin;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import net.thebullpen.baseball.registry.RegistryService;
import net.thebullpen.baseball.registry.dto.ModelVersion;
import net.thebullpen.baseball.registry.dto.RegisterRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * INC-2 (decision [151]) load-gate behavior. Unlike {@link RegistryControllerIT} (which disables
 * the gate to test transition/rule-5 logic on dummy snapshots), this IT leaves the gate ON (the
 * default) and asserts that promoting a NON-LOADABLE model is rejected with 422 ModelLoadFailed at
 * promote- time - the control that would have turned the 2026-06-07 incident into a clean 422
 * instead of a stuck champion + live 500. The registered snapshot here carries a "stub" model.onnx
 * (not a real ONNX), so the serving-path load throws.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"api", "registry-controller-it"})
class PromotionLoadGateIT {

  private static final String ADMIN_USER = "it-admin";
  private static final String ADMIN_PASS = "it-password";
  private static final String BASIC =
      "Basic " + Base64.getEncoder().encodeToString((ADMIN_USER + ":" + ADMIN_PASS).getBytes());

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    Path dbPath =
        Path.of(
            System.getProperty("java.io.tmpdir"),
            "bullpen-loadgate-it-" + UUID.randomUUID() + ".sqlite");
    String url = "jdbc:sqlite:" + dbPath;
    registry.add("spring.datasource.url", () -> url);
    registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
    registry.add("spring.flyway.url", () -> url);
    registry.add("bullpen.admin.basicauth", () -> ADMIN_USER + ":" + ADMIN_PASS);
    Path snapshotBase =
        Path.of(
            System.getProperty("java.io.tmpdir"), "bullpen-loadgate-it-snap-" + UUID.randomUUID());
    registry.add("bullpen.snapshot.local-base-path", snapshotBase::toString);
    // Gate stays at its prod default (enabled) - that's the whole point of this IT.
  }

  @Autowired private MockMvc mvc;
  @Autowired private RegistryService service;
  @Autowired private ObjectMapper mapper;
  @TempDir Path artifactDir;

  @Test
  void promoting_a_non_loadable_model_to_champion_is_422_not_a_stuck_champion() throws Exception {
    ModelVersion mv = service.register(stubRequest("loadgate_model", "v1"));

    mvc.perform(
            post("/v1/admin/registry/loadgate_model/promote/" + mv.id())
                .header("Authorization", BASIC)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        Map.of("targetStage", "champion", "reason", "load-gate IT"))))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.error.message").exists());

    // The gate ran BEFORE the transition: the version is still CANDIDATE, no champion exists.
    org.assertj.core.api.Assertions.assertThat(
            service.getById(mv.id()).orElseThrow().stage().name())
        .isEqualTo("CANDIDATE");
    org.assertj.core.api.Assertions.assertThat(service.findChampion("loadgate_model")).isEmpty();
  }

  private RegisterRequest stubRequest(String modelName, String version) throws Exception {
    Path artifact = writeFile(modelName + "-" + version + "-model.onnx", "stub-not-a-real-onnx");
    Path metadata = writeFile(modelName + "-" + version + "-meta.json", "{}"); // no park_order
    Path pipeline =
        writeFile(
            modelName + "-" + version + "-pipeline.json",
            "{\"model_name\":\""
                + modelName
                + "\",\"pipeline_version\":\"1.0.0\",\"feature_order\":[\"a\",\"b\"],"
                + "\"schema_hash\":\"recomputed-on-load\"}");
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
        "loadgate-it",
        "registered by PromotionLoadGateIT");
  }

  private Path writeFile(String name, String content) throws Exception {
    Path p = artifactDir.resolve(name);
    Files.writeString(p, content);
    return p;
  }
}
