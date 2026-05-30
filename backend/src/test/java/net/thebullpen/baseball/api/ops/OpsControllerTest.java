package net.thebullpen.baseball.api.ops;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import net.thebullpen.baseball.api.ApiErrorAdvice;
import net.thebullpen.baseball.drift.DriftMetric;
import net.thebullpen.baseball.drift.DriftMetricsRepository;
import net.thebullpen.baseball.drift.MetricType;
import net.thebullpen.baseball.inference.routing.RoutingConfig;
import net.thebullpen.baseball.inference.routing.RoutingMode;
import net.thebullpen.baseball.inference.routing.RoutingRepository;
import net.thebullpen.baseball.registry.RegistryService;
import net.thebullpen.baseball.registry.dto.ModelVersion;
import net.thebullpen.baseball.registry.dto.Stage;
import net.thebullpen.baseball.retraining.RetrainingQueueService;
import net.thebullpen.baseball.retraining.dto.QueueStatus;
import net.thebullpen.baseball.retraining.dto.RetrainingTrigger;
import net.thebullpen.baseball.retraining.dto.TriggerType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class OpsControllerTest {

  private DriftMetricsRepository driftRepo;
  private RoutingRepository routingRepo;
  private RetrainingQueueService retrain;
  private RegistryService registry;
  private MockMvc mvc;

  @BeforeEach
  void setup() {
    driftRepo = mock(DriftMetricsRepository.class);
    routingRepo = mock(RoutingRepository.class);
    retrain = mock(RetrainingQueueService.class);
    registry = mock(RegistryService.class);
    mvc =
        MockMvcBuilders.standaloneSetup(
                new OpsController(driftRepo, routingRepo, retrain, registry))
            .setControllerAdvice(new ApiErrorAdvice())
            .build();
  }

  @Test
  void typeMismatchOnPathVar_mapsTo400_notServerError() {
    // Regression for the Schemathesis-found bug (S1f): a non-numeric {versionId}
    // path variable raised MethodArgumentTypeMismatchException → unhandled → 500.
    // The ApiErrorAdvice handler must map it to a 400 client error. (Direct call:
    // standalone MockMvc doesn't route type-conversion exceptions to the advice
    // the way the full DispatcherServlet does — the Schemathesis CI job covers
    // the end-to-end path.)
    var ex =
        new org.springframework.web.method.annotation.MethodArgumentTypeMismatchException(
            "notanumber", Long.class, "versionId", null, new NumberFormatException());
    var response = new ApiErrorAdvice().handleTypeMismatch(ex);
    org.junit.jupiter.api.Assertions.assertEquals(400, response.getStatusCode().value());
    org.junit.jupiter.api.Assertions.assertEquals(
        "invalid_input", java.util.Objects.requireNonNull(response.getBody()).error().code());
  }

  @Test
  void drift_returns_repo_rows_for_named_model() throws Exception {
    Instant now = Instant.parse("2026-05-25T12:00:00Z");
    when(driftRepo.findAllForModel("pitch_outcome_pre"))
        .thenReturn(
            List.of(
                new DriftMetric(
                    now,
                    "pitch_outcome_pre",
                    7L,
                    MetricType.PSI_FEATURE,
                    "launch_speed_mph",
                    0.05,
                    1234L,
                    now.minusSeconds(7 * 86400),
                    now)));

    mvc.perform(get("/v1/ops/drift").param("model", "pitch_outcome_pre"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].modelName").value("pitch_outcome_pre"))
        .andExpect(jsonPath("$[0].metricValue").value(0.05));
  }

  @Test
  void drift_returns_empty_when_repo_bean_is_absent() throws Exception {
    MockMvc m =
        MockMvcBuilders.standaloneSetup(new OpsController(null, routingRepo, retrain, registry))
            .setControllerAdvice(new ApiErrorAdvice())
            .build();
    m.perform(get("/v1/ops/drift").param("model", "any")).andExpect(status().isOk());
  }

  @Test
  void routing_lists_every_row() throws Exception {
    when(routingRepo.findAll())
        .thenReturn(
            List.of(
                new RoutingConfig(
                    1L,
                    "pitch_outcome_pre",
                    10L,
                    11L,
                    25.0,
                    RoutingMode.AB,
                    Instant.parse("2026-05-25T11:00:00Z"))));

    mvc.perform(get("/v1/ops/routing"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].modelName").value("pitch_outcome_pre"))
        .andExpect(jsonPath("$[0].challengerTrafficPct").value(25.0));
  }

  @Test
  void retrain_without_model_returns_all_queued() throws Exception {
    when(retrain.findAllQueued())
        .thenReturn(
            List.of(
                new RetrainingTrigger(
                    1L,
                    "trig-1",
                    "pitch_outcome_pre",
                    TriggerType.MANUAL,
                    "{}",
                    QueueStatus.QUEUED,
                    Instant.parse("2026-05-25T11:00:00Z"),
                    null,
                    null,
                    null,
                    null)));

    mvc.perform(get("/v1/ops/retrain"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].triggerId").value("trig-1"));
    verify(retrain).findAllQueued();
  }

  @Test
  void retrain_with_model_filter_delegates_to_findByModel() throws Exception {
    when(retrain.findByModel("pitch_outcome_pre")).thenReturn(List.of());

    mvc.perform(get("/v1/ops/retrain").param("model", "pitch_outcome_pre"))
        .andExpect(status().isOk());
    verify(retrain).findByModel("pitch_outcome_pre");
  }

  @Test
  void calibration_summary_maps_each_model_to_its_latest_eval_metrics() throws Exception {
    when(registry.findAllModelNames())
        .thenReturn(List.of("pitch_outcome_pre", "pitch_outcome_post"));
    when(registry.findByName("pitch_outcome_pre"))
        .thenReturn(
            List.of(
                new ModelVersion(
                    7L,
                    "pitch_outcome_pre",
                    "v3",
                    "a",
                    "b",
                    "h1",
                    "2024",
                    "fh",
                    "{\"brier\":0.187}",
                    Instant.now(),
                    null,
                    Stage.CHAMPION,
                    null,
                    null,
                    Instant.now(),
                    Instant.now())));
    when(registry.findByName("pitch_outcome_post")).thenReturn(List.of());

    mvc.perform(get("/v1/ops/calibration-summary"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.pitch_outcome_pre").value("{\"brier\":0.187}"))
        .andExpect(jsonPath("$.pitch_outcome_post").value(""));
  }
}
