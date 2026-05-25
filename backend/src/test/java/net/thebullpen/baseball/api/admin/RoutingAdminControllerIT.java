package net.thebullpen.baseball.api.admin;

import static org.hamcrest.Matchers.equalTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import java.util.Map;
import java.util.UUID;
import net.thebullpen.baseball.inference.routing.RoutingMode;
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
import org.springframework.cache.CacheManager;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HTTP-layer tests for {@link RoutingAdminController}. Exercises the same auth boundary as {@link
 * RegistryControllerIT} plus the validation-rule → HTTP-status mapping for every {@link
 * net.thebullpen.baseball.inference.routing.RoutingException} subclass.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"api", "registry-controller-it"})
class RoutingAdminControllerIT {

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
            "bullpen-routing-ctrl-it-" + UUID.randomUUID() + ".sqlite");
    String url = "jdbc:sqlite:" + dbPath;
    registry.add("spring.datasource.url", () -> url);
    registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
    registry.add("spring.flyway.url", () -> url);
    registry.add("bullpen.admin.basicauth", () -> ADMIN_USER + ":" + ADMIN_PASS);
    Path snapshotBase =
        Path.of(
            System.getProperty("java.io.tmpdir"),
            "bullpen-routing-ctrl-it-snapshots-" + UUID.randomUUID());
    registry.add("bullpen.snapshot.local-base-path", snapshotBase::toString);
  }

  @Autowired private MockMvc mvc;
  @Autowired private RegistryService registry;
  @Autowired private RoutingService routing;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper mapper;
  @Autowired private CacheManager cacheManager;

  @TempDir Path artifactDir;

  @BeforeEach
  void reset() {
    jdbc.update("DELETE FROM experiment_results");
    jdbc.update("DELETE FROM model_routing");
    jdbc.update("DELETE FROM model_versions");
    cacheManager.getCacheNames().forEach(name -> cacheManager.getCache(name).clear());
  }

  // --- auth boundary ----------------------------------------------------

  @Test
  void list_without_credentials_is_unauthorized() throws Exception {
    mvc.perform(get("/v1/admin/routing")).andExpect(status().isUnauthorized());
  }

  @Test
  void list_with_credentials_returns_200_and_array() throws Exception {
    mvc.perform(get("/v1/admin/routing").header("Authorization", BASIC))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()", equalTo(0)));
  }

  // --- get + 404 --------------------------------------------------------

  @Test
  void get_for_unknown_model_is_404() throws Exception {
    mvc.perform(get("/v1/admin/routing/unknown").header("Authorization", BASIC))
        .andExpect(status().isNotFound());
  }

  @Test
  void get_for_known_model_returns_routing_row() throws Exception {
    bootstrapRouting("admin_routing_model");
    mvc.perform(get("/v1/admin/routing/admin_routing_model").header("Authorization", BASIC))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.modelName", equalTo("admin_routing_model")))
        .andExpect(jsonPath("$.mode", equalTo("SHADOW")))
        .andExpect(jsonPath("$.challengerVersionId").doesNotExist());
  }

  // --- setChallenger ----------------------------------------------------

  @Test
  void set_challenger_with_shadow_version_returns_200() throws Exception {
    bootstrapRouting("set_ch_model");
    ModelVersion v2 = registry.register(sampleRequest("set_ch_model", "v2"));
    registry.transitionStage(v2.id(), Stage.SHADOW);

    mvc.perform(
            post("/v1/admin/routing/set_ch_model/challenger")
                .header("Authorization", BASIC)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        Map.of(
                            "challengerVersionId",
                            v2.id(),
                            "reason",
                            "promoting v2 to challenger for shadow eval"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.challengerVersionId", equalTo((int) v2.id())));
  }

  @Test
  void set_challenger_with_candidate_version_is_400() throws Exception {
    bootstrapRouting("set_ch_cand_model");
    ModelVersion v2 = registry.register(sampleRequest("set_ch_cand_model", "v2"));
    // v2 stays at CANDIDATE.

    mvc.perform(
            post("/v1/admin/routing/set_ch_cand_model/challenger")
                .header("Authorization", BASIC)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        Map.of("challengerVersionId", v2.id(), "reason", "trying"))))
        .andExpect(status().isBadRequest());
  }

  @Test
  void set_challenger_for_unknown_model_is_404() throws Exception {
    mvc.perform(
            post("/v1/admin/routing/no_such_model/challenger")
                .header("Authorization", BASIC)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(Map.of("challengerVersionId", 1L, "reason", "x"))))
        .andExpect(status().isNotFound());
  }

  // --- clearChallenger --------------------------------------------------

  @Test
  void clear_challenger_returns_routing_without_challenger() throws Exception {
    bootstrapRouting("clr_ch_model");
    ModelVersion v2 = registry.register(sampleRequest("clr_ch_model", "v2"));
    registry.transitionStage(v2.id(), Stage.SHADOW);
    routing.setChallenger("clr_ch_model", v2.id());

    mvc.perform(delete("/v1/admin/routing/clr_ch_model/challenger").header("Authorization", BASIC))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.challengerVersionId").doesNotExist())
        .andExpect(jsonPath("$.mode", equalTo("SHADOW")));
  }

  // --- setTrafficPct ----------------------------------------------------

  @Test
  void set_traffic_pct_in_shadow_mode_with_nonzero_is_400() throws Exception {
    bootstrapRouting("tp_shadow_model");
    mvc.perform(
            post("/v1/admin/routing/tp_shadow_model/traffic-pct")
                .header("Authorization", BASIC)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("pct", 25.0, "reason", "ramp"))))
        .andExpect(status().isBadRequest());
  }

  @Test
  void set_traffic_pct_in_ab_mode_with_valid_value_returns_200() throws Exception {
    bootstrapRouting("tp_ab_model");
    routing.setMode("tp_ab_model", RoutingMode.AB);
    mvc.perform(
            post("/v1/admin/routing/tp_ab_model/traffic-pct")
                .header("Authorization", BASIC)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("pct", 25.0, "reason", "ramp"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.challengerTrafficPct", equalTo(25.0)));
  }

  @Test
  void set_traffic_pct_out_of_range_is_400() throws Exception {
    bootstrapRouting("tp_range_model");
    // Bean Validation rejects pct > 100 at the @DecimalMax layer → 400.
    mvc.perform(
            post("/v1/admin/routing/tp_range_model/traffic-pct")
                .header("Authorization", BASIC)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("pct", 150.0, "reason", "bad"))))
        .andExpect(status().isBadRequest());
  }

  // --- setMode ----------------------------------------------------------

  @Test
  void set_mode_to_ab_returns_updated_routing() throws Exception {
    bootstrapRouting("mode_ab_model");
    mvc.perform(
            post("/v1/admin/routing/mode_ab_model/mode")
                .header("Authorization", BASIC)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("mode", "AB", "reason", "ramp"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.mode", equalTo("AB")));
  }

  @Test
  void set_mode_with_invalid_value_is_400() throws Exception {
    bootstrapRouting("mode_bad_model");
    mvc.perform(
            post("/v1/admin/routing/mode_bad_model/mode")
                .header("Authorization", BASIC)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("mode", "live", "reason", "x"))))
        .andExpect(status().isBadRequest());
  }

  // --- helpers ----------------------------------------------------------

  private void bootstrapRouting(String modelName) throws Exception {
    ModelVersion v1 = registry.register(sampleRequest(modelName, "v1"));
    registry.transitionStage(v1.id(), Stage.CHAMPION);
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
        "routing-ctrl-it",
        "registered by RoutingAdminControllerIT");
  }
}
