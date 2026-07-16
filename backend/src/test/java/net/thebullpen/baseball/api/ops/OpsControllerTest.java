package net.thebullpen.baseball.api.ops;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import net.thebullpen.baseball.api.ApiErrorAdvice;
import net.thebullpen.baseball.api.dto.LatencyStat;
import net.thebullpen.baseball.api.dto.OpsEvent;
import net.thebullpen.baseball.api.dto.OpsEventType;
import net.thebullpen.baseball.data.OpsEventsRepository;
import net.thebullpen.baseball.data.PredictionLogRepository;
import net.thebullpen.baseball.drift.DriftMetricsRepository;
import net.thebullpen.baseball.drift.MetricType;
import net.thebullpen.baseball.drift.TaggedDriftMetric;
import net.thebullpen.baseball.inference.routing.RoutingConfig;
import net.thebullpen.baseball.inference.routing.RoutingMode;
import net.thebullpen.baseball.inference.routing.RoutingRepository;
import net.thebullpen.baseball.registry.AccuracyEvidenceRepository;
import net.thebullpen.baseball.registry.AccuracyService;
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
  private OpsEventsRepository opsEvents;
  private PredictionLogRepository predictionLog;
  private AccuracyService accuracyService;
  private MockMvc mvc;

  @BeforeEach
  void setup() {
    driftRepo = mock(DriftMetricsRepository.class);
    routingRepo = mock(RoutingRepository.class);
    retrain = mock(RetrainingQueueService.class);
    registry = mock(RegistryService.class);
    opsEvents = mock(OpsEventsRepository.class);
    predictionLog = mock(PredictionLogRepository.class);
    // Real AccuracyService over the bundled classpath evidence (processResources copies the
    // committed *_full*.json into build/resources/main/accuracy-evidence/), so the scorecard test
    // asserts on real held-out numbers rather than mocks.
    accuracyService = new AccuracyService(new AccuracyEvidenceRepository(new ObjectMapper()));
    mvc =
        MockMvcBuilders.standaloneSetup(
                new OpsController(
                    driftRepo,
                    routingRepo,
                    retrain,
                    registry,
                    opsEvents,
                    predictionLog,
                    accuracyService))
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
  void drift_returns_repo_rows_for_named_model_with_the_tag() throws Exception {
    // E-4: the ops surface reads the tag-carrying variant so [175] induced-drill evidence rows
    // are labelable on the dashboard; '' = organic. Same row shape plus the additive tag field.
    Instant now = Instant.parse("2026-05-25T12:00:00Z");
    when(driftRepo.findAllForModelTagged("battedball_outcome"))
        .thenReturn(
            List.of(
                new TaggedDriftMetric(
                    now,
                    "battedball_outcome",
                    7L,
                    MetricType.PSI_FEATURE,
                    "launchSpeedMph",
                    0.91,
                    5000L,
                    now.minusSeconds(86400),
                    now,
                    "induced-drill-2026-07"),
                new TaggedDriftMetric(
                    now.minusSeconds(3600),
                    "battedball_outcome",
                    7L,
                    MetricType.PSI_FEATURE,
                    "launchAngleDeg",
                    0.04,
                    5000L,
                    now.minusSeconds(86400),
                    now,
                    "")));

    mvc.perform(get("/v1/ops/drift").param("model", "battedball_outcome"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].modelName").value("battedball_outcome"))
        .andExpect(jsonPath("$[0].metricValue").value(0.91))
        .andExpect(jsonPath("$[0].tag").value("induced-drill-2026-07"))
        .andExpect(jsonPath("$[1].tag").value(""));
  }

  @Test
  void drift_returns_empty_when_repo_bean_is_absent() throws Exception {
    MockMvc m =
        MockMvcBuilders.standaloneSetup(
                new OpsController(
                    null,
                    routingRepo,
                    retrain,
                    registry,
                    opsEvents,
                    predictionLog,
                    accuracyService))
            .setControllerAdvice(new ApiErrorAdvice())
            .build();
    m.perform(get("/v1/ops/drift").param("model", "any")).andExpect(status().isOk());
  }

  @Test
  void events_returns_recent_ops_log_newest_first() throws Exception {
    when(opsEvents.findRecent(20))
        .thenReturn(
            List.of(
                new OpsEvent(
                    2L,
                    Instant.parse("2026-05-30T19:00:00Z"),
                    OpsEventType.PROMOTE,
                    "pitch_outcome_pre v3.3 SHADOW → CHAMPION"),
                new OpsEvent(
                    1L,
                    Instant.parse("2026-05-30T14:00:00Z"),
                    OpsEventType.REGISTER,
                    "batted_ball v1.5 registered as SHADOW")));

    mvc.perform(get("/v1/ops/events"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].type").value("PROMOTE"))
        .andExpect(jsonPath("$[0].detail").value("pitch_outcome_pre v3.3 SHADOW → CHAMPION"))
        .andExpect(jsonPath("$[1].type").value("REGISTER"));
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

  @Test
  void latency_returns_quantile_rows_per_model() throws Exception {
    when(predictionLog.latencyQuantiles(7))
        .thenReturn(
            List.of(new LatencyStat("pitch_outcome_pre", "v3", 12_345L, 0.42, 0.91, 1.37, 2.10)));

    mvc.perform(get("/v1/ops/latency"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].modelName").value("pitch_outcome_pre"))
        .andExpect(jsonPath("$[0].sampleCount").value(12345))
        .andExpect(jsonPath("$[0].p99Ms").value(1.37))
        .andExpect(jsonPath("$[0].p999Ms").value(2.10));
    verify(predictionLog).latencyQuantiles(7);
  }

  @Test
  void latency_returns_empty_when_prediction_log_bean_is_absent() throws Exception {
    MockMvc m =
        MockMvcBuilders.standaloneSetup(
                new OpsController(
                    driftRepo, routingRepo, retrain, registry, opsEvents, null, accuracyService))
            .setControllerAdvice(new ApiErrorAdvice())
            .build();
    m.perform(get("/v1/ops/latency")).andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
  }

  @Test
  void accuracy_returns_offline_scorecard_from_committed_evidence() throws Exception {
    mvc.perform(get("/v1/ops/accuracy"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray())
        // every row is labeled offline held-out, never live
        .andExpect(
            jsonPath("$[0].evaluation").value(org.hamcrest.Matchers.containsString("offline")))
        .andExpect(
            jsonPath("$[0].evaluation").value(org.hamcrest.Matchers.containsString("not live")))
        // the passed post head is present with its gate verdict
        .andExpect(
            jsonPath("$[?(@.modelName=='pitch_outcome_post')].gateStatus")
                .value(org.hamcrest.Matchers.hasItem("passed")))
        // batted_ball_mlp reconciles to the registry/serving name
        .andExpect(
            jsonPath("$[?(@.evidenceModelName=='batted_ball_mlp')].modelName")
                .value(org.hamcrest.Matchers.hasItem("battedball_outcome")))
        // the SELF-REFERENTIAL ece_vs_retro calibration note rides through verbatim
        .andExpect(
            jsonPath("$[?(@.modelName=='battedball_outcome')].calibrationNote")
                .value(
                    org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.containsString("retro"))));
  }

  @Test
  void backfill_accuracy_returns_the_committed_box_artifact() throws Exception {
    // The artifact is committed (#157) + bundled onto the classpath, so the endpoint returns 200
    // with the held-out backfill doc (was 204 before #157; this test is updated to that state).
    mvc.perform(get("/v1/ops/backfill-accuracy"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.model_name").value("battedball_outcome"))
        .andExpect(jsonPath("$.season_from").value(2026))
        .andExpect(jsonPath("$.eval_kind").value("offline_holdout_unseen"));
  }
}
