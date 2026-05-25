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

  // --- worker-facing endpoints (3d.3) ----------------------------------

  @Test
  void claim_returns_204_when_queue_is_empty() throws Exception {
    mvc.perform(post("/v1/admin/retrain/claim").header("Authorization", BASIC))
        .andExpect(status().isNoContent());
  }

  @Test
  void claim_returns_running_row_when_queue_has_one() throws Exception {
    queue.enqueue("model_a", TriggerType.MANUAL, "claim-me", Map.of());
    mvc.perform(post("/v1/admin/retrain/claim").header("Authorization", BASIC))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.triggerId", equalTo("claim-me")))
        .andExpect(jsonPath("$.status", equalTo("RUNNING")));
  }

  @Test
  void complete_success_flips_to_succeeded_with_produced_version_id() throws Exception {
    queue.enqueue("model_a", TriggerType.MANUAL, "ok-1", Map.of());
    queue.claimNext();
    mvc.perform(
            post("/v1/admin/retrain/ok-1/complete")
                .header("Authorization", BASIC)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(Map.of("succeeded", true, "producedVersionId", 42))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status", equalTo("SUCCEEDED")))
        .andExpect(jsonPath("$.producedVersionId", equalTo(42)));
  }

  @Test
  void complete_failure_flips_to_failed_with_error_message() throws Exception {
    queue.enqueue("model_a", TriggerType.MANUAL, "fail-1", Map.of());
    queue.claimNext();
    mvc.perform(
            post("/v1/admin/retrain/fail-1/complete")
                .header("Authorization", BASIC)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        Map.of("succeeded", false, "errorMessage", "OOM during fit"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status", equalTo("FAILED")));
  }

  @Test
  void complete_success_without_producedVersionId_is_400() throws Exception {
    queue.enqueue("model_a", TriggerType.MANUAL, "miss-1", Map.of());
    queue.claimNext();
    mvc.perform(
            post("/v1/admin/retrain/miss-1/complete")
                .header("Authorization", BASIC)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("succeeded", true))))
        .andExpect(status().isBadRequest());
  }

  @Test
  void complete_failure_without_errorMessage_is_400() throws Exception {
    queue.enqueue("model_a", TriggerType.MANUAL, "miss-2", Map.of());
    queue.claimNext();
    mvc.perform(
            post("/v1/admin/retrain/miss-2/complete")
                .header("Authorization", BASIC)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("succeeded", false))))
        .andExpect(status().isBadRequest());
  }

  @Test
  void complete_for_unknown_trigger_is_404() throws Exception {
    mvc.perform(
            post("/v1/admin/retrain/ghost/complete")
                .header("Authorization", BASIC)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(Map.of("succeeded", true, "producedVersionId", 1))))
        .andExpect(status().isNotFound());
  }

  // --- reap-stale (3d.4) ----------------------------------------------

  @Test
  void reap_stale_with_no_stuck_rows_returns_zero() throws Exception {
    mvc.perform(post("/v1/admin/retrain/reap-stale").header("Authorization", BASIC))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reaped", equalTo(0)));
  }

  @Test
  void reap_stale_flips_stuck_running_row_back_to_queued() throws Exception {
    queue.enqueue("model_a", TriggerType.SCHEDULED, "stuck-1", Map.of());
    queue.claimNext(); // now RUNNING with started_at = now
    // Backdate started_at past the 4h default threshold so the reap-stale call catches it.
    jdbc.update(
        "UPDATE retraining_queue SET started_at = ? WHERE trigger_id = ?",
        java.sql.Timestamp.from(
            java.time.Instant.now().minus(5, java.time.temporal.ChronoUnit.HOURS)),
        "stuck-1");

    mvc.perform(post("/v1/admin/retrain/reap-stale").header("Authorization", BASIC))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reaped", equalTo(1)));
    org.assertj.core.api.Assertions.assertThat(queue.getByTriggerId("stuck-1").status())
        .isEqualTo(net.thebullpen.baseball.retraining.dto.QueueStatus.QUEUED);
  }

  @Test
  void reap_stale_unauthenticated_is_401() throws Exception {
    mvc.perform(post("/v1/admin/retrain/reap-stale")).andExpect(status().isUnauthorized());
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
