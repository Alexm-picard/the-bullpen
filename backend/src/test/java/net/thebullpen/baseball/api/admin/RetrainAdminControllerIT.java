package net.thebullpen.baseball.api.admin;

import static org.hamcrest.Matchers.equalTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import net.thebullpen.baseball.retraining.RetrainingQueueService;
import net.thebullpen.baseball.retraining.dto.TriggerType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** HTTP IT for {@link RetrainAdminController} — auth + enqueue/list/get/cancel. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"api", "registry-controller-it"})
class RetrainAdminControllerIT {

  private static final String ADMIN_USER = "it-admin";
  private static final String ADMIN_PASS = "it-password";
  private static final String BASIC =
      "Basic "
          + Base64.getEncoder()
              .encodeToString((ADMIN_USER + ":" + ADMIN_PASS).getBytes(StandardCharsets.UTF_8));

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    java.nio.file.Path dbPath =
        java.nio.file.Path.of(
            System.getProperty("java.io.tmpdir"),
            "bullpen-retrain-ctrl-it-" + UUID.randomUUID() + ".sqlite");
    String url = "jdbc:sqlite:" + dbPath;
    registry.add("spring.datasource.url", () -> url);
    registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
    registry.add("spring.flyway.url", () -> url);
    registry.add("bullpen.admin.basicauth", () -> ADMIN_USER + ":" + ADMIN_PASS);
    java.nio.file.Path snapshotBase =
        java.nio.file.Path.of(
            System.getProperty("java.io.tmpdir"),
            "bullpen-retrain-ctrl-it-snapshots-" + UUID.randomUUID());
    registry.add("bullpen.snapshot.local-base-path", snapshotBase::toString);
  }

  @Autowired private MockMvc mvc;
  @Autowired private RetrainingQueueService queue;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper mapper;

  @BeforeEach
  void reset() {
    jdbc.update("DELETE FROM retraining_queue");
  }

  @Test
  void unauthenticated_post_is_401() throws Exception {
    mvc.perform(
            post("/v1/admin/retrain")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        Map.of("modelName", "model_a", "reason", "manual test"))))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void post_with_credentials_enqueues_and_returns_row() throws Exception {
    mvc.perform(
            post("/v1/admin/retrain")
                .header("Authorization", BASIC)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        Map.of("modelName", "model_a", "reason", "manual test"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.modelName", equalTo("model_a")))
        .andExpect(jsonPath("$.triggerType", equalTo("MANUAL")))
        .andExpect(jsonPath("$.status", equalTo("QUEUED")));
    assertThatModelHasOneTrigger("model_a", TriggerType.MANUAL);
  }

  @Test
  void post_second_within_1h_returns_existing_trigger() throws Exception {
    String body = mapper.writeValueAsString(Map.of("modelName", "model_a", "reason", "first call"));
    var first =
        mvc.perform(
                post("/v1/admin/retrain")
                    .header("Authorization", BASIC)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isOk())
            .andReturn();
    String firstId =
        mapper.readTree(first.getResponse().getContentAsString()).get("triggerId").asText();

    var second =
        mvc.perform(
                post("/v1/admin/retrain")
                    .header("Authorization", BASIC)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        mapper.writeValueAsString(
                            Map.of("modelName", "model_a", "reason", "second call"))))
            .andExpect(status().isOk())
            .andReturn();
    String secondId =
        mapper.readTree(second.getResponse().getContentAsString()).get("triggerId").asText();

    assertThatModelHasOneTrigger("model_a", TriggerType.MANUAL);
    assertThat(secondId).isEqualTo(firstId);
  }

  @Test
  void list_returns_queued_rows() throws Exception {
    queue.enqueue("model_a", TriggerType.MANUAL, "mt-1", Map.of());
    mvc.perform(get("/v1/admin/retrain").header("Authorization", BASIC))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()", equalTo(1)));
  }

  @Test
  void list_filters_by_model_name() throws Exception {
    queue.enqueue("model_a", TriggerType.MANUAL, "ma-1", Map.of());
    queue.enqueue("model_b", TriggerType.MANUAL, "mb-1", Map.of());
    mvc.perform(
            get("/v1/admin/retrain").param("modelName", "model_b").header("Authorization", BASIC))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()", equalTo(1)))
        .andExpect(jsonPath("$[0].modelName", equalTo("model_b")));
  }

  @Test
  void get_unknown_trigger_is_404() throws Exception {
    mvc.perform(get("/v1/admin/retrain/ghost").header("Authorization", BASIC))
        .andExpect(status().isNotFound());
  }

  @Test
  void cancel_queued_trigger_returns_cancelled_row() throws Exception {
    var inserted = queue.enqueue("model_a", TriggerType.MANUAL, "cancel-me", Map.of());
    mvc.perform(delete("/v1/admin/retrain/" + inserted.triggerId()).header("Authorization", BASIC))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status", equalTo("CANCELLED")));
  }

  @Test
  void cancel_terminal_trigger_is_409() throws Exception {
    var inserted = queue.enqueue("model_a", TriggerType.MANUAL, "term-1", Map.of());
    queue.claimNext();
    queue.completeSuccess("term-1", 1L);
    mvc.perform(delete("/v1/admin/retrain/" + inserted.triggerId()).header("Authorization", BASIC))
        .andExpect(status().isConflict());
  }

  private void assertThatModelHasOneTrigger(String modelName, TriggerType type) {
    org.assertj.core.api.Assertions.assertThat(queue.findByModel(modelName))
        .hasSize(1)
        .first()
        .matches(t -> t.triggerType() == type, "expected single trigger of type " + type);
  }

  private static org.assertj.core.api.AbstractStringAssert<?> assertThat(String actual) {
    return org.assertj.core.api.Assertions.assertThat(actual);
  }
}
