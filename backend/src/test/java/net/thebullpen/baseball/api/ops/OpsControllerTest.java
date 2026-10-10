package net.thebullpen.baseball.api.ops;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import net.thebullpen.baseball.api.ApiErrorAdvice;
import net.thebullpen.baseball.data.LiveReliabilityRepository;
import net.thebullpen.baseball.data.OpsEventsRepository;
import net.thebullpen.baseball.data.PredictionLogRepository;
import net.thebullpen.baseball.data.RollingAccuracyRepository;
import net.thebullpen.baseball.domain.LatencyStat;
import net.thebullpen.baseball.domain.OpsEvent;
import net.thebullpen.baseball.domain.OpsEventType;
import net.thebullpen.baseball.domain.PagedRows;
import net.thebullpen.baseball.domain.ReliabilityBinRow;
import net.thebullpen.baseball.domain.ReliabilityWindow;
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
                    accuracyService,
                    null,
                    null))
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
                    accuracyService,
                    null,
                    null))
            .setControllerAdvice(new ApiErrorAdvice())
            .build();
    m.perform(get("/v1/ops/drift").param("model", "any")).andExpect(status().isOk());
  }

  @Test
  void events_returns_recent_ops_log_newest_first() throws Exception {
    when(opsEvents.findRecentPage(0, 20))
        .thenReturn(
            new PagedRows<>(
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
                        "batted_ball v1.5 registered as SHADOW")),
                false));

    mvc.perform(get("/v1/ops/events"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.page").value(0))
        .andExpect(jsonPath("$.size").value(20))
        .andExpect(jsonPath("$.hasNext").value(false))
        .andExpect(jsonPath("$.rows[0].type").value("PROMOTE"))
        .andExpect(jsonPath("$.rows[0].detail").value("pitch_outcome_pre v3.3 SHADOW → CHAMPION"))
        .andExpect(jsonPath("$.rows[1].type").value("REGISTER"));
  }

  @Test
  void events_rejects_a_negative_page() throws Exception {
    mvc.perform(get("/v1/ops/events").param("page", "-1")).andExpect(status().isBadRequest());
  }

  @Test
  void events_rejects_a_size_outside_1_to_200() throws Exception {
    mvc.perform(get("/v1/ops/events").param("size", "0")).andExpect(status().isBadRequest());
    mvc.perform(get("/v1/ops/events").param("size", "201")).andExpect(status().isBadRequest());
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
  void latency_rejects_days_outside_1_to_365_without_hitting_the_repo() throws Exception {
    // The live days=2000000000 full-table-scan DoS: an out-of-range window is a 400 BEFORE the
    // ClickHouse read, so an anonymous caller cannot force an unbounded prediction_log scan.
    mvc.perform(get("/v1/ops/latency").param("days", "2000000000"))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/v1/ops/latency").param("days", "0")).andExpect(status().isBadRequest());
    verifyNoInteractions(predictionLog);
  }

  @Test
  void rollingAccuracy_rejects_days_outside_1_to_30() throws Exception {
    // Same anonymous-scan-DoS fence as latency, tighter cap: past the 14-day pitches_live TTL a
    // wider window only widens the prediction_log scan for zero additional joinable truth.
    mvc.perform(get("/v1/ops/rolling-accuracy").param("days", "31"))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/v1/ops/rolling-accuracy").param("days", "0"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void rollingAccuracy_withoutClickHouse_statesWhyForAllFourModels() throws Exception {
    // The endpoint's honesty contract: absence is STATED, never implied. With no analytical
    // store every family says why there is no live figure - no fabricated zeros, no omissions.
    mvc.perform(get("/v1/ops/rolling-accuracy"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.windowDays").value(7))
        .andExpect(jsonPath("$.models.length()").value(4))
        .andExpect(jsonPath("$.models[0].modelName").value("pitch_outcome_pre"))
        .andExpect(jsonPath("$.models[0].status").value("no_live_truth"))
        .andExpect(jsonPath("$.models[0].top1").doesNotExist())
        .andExpect(jsonPath("$.models[1].modelName").value("pitch_outcome_post"))
        .andExpect(jsonPath("$.models[2].modelName").value("battedball_outcome"))
        .andExpect(
            jsonPath("$.models[2].reason").value(org.hamcrest.Matchers.containsString("[163]")))
        .andExpect(jsonPath("$.models[3].modelName").value("pitch_type_pre"))
        .andExpect(jsonPath("$.models[3].status").value("no_live_truth"));
  }

  @Test
  void latency_returns_empty_when_prediction_log_bean_is_absent() throws Exception {
    MockMvc m =
        MockMvcBuilders.standaloneSetup(
                new OpsController(
                    driftRepo,
                    routingRepo,
                    retrain,
                    registry,
                    opsEvents,
                    null,
                    accuracyService,
                    null,
                    null))
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

  // --- live calibration on /rolling-accuracy ---------------------------------------------------

  private MockMvc mvcWithLiveStore(
      RollingAccuracyRepository rolling, LiveReliabilityRepository reliability) {
    return MockMvcBuilders.standaloneSetup(
            new OpsController(
                driftRepo,
                routingRepo,
                retrain,
                registry,
                opsEvents,
                predictionLog,
                accuracyService,
                rolling,
                reliability))
        .setControllerAdvice(new ApiErrorAdvice())
        .build();
  }

  @Test
  void rollingAccuracy_rejects_calibrationDays_outside_1_to_30() throws Exception {
    mvc.perform(get("/v1/ops/rolling-accuracy").param("calibrationDays", "0"))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/v1/ops/rolling-accuracy").param("calibrationDays", "31"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void rollingAccuracy_echoesTheDefaultCalibrationWindow_andIsNullWithoutAStore() throws Exception {
    mvc.perform(get("/v1/ops/rolling-accuracy"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.windowDays").value(7))
        .andExpect(jsonPath("$.calibrationWindowDays").value(30))
        .andExpect(jsonPath("$.models[0].calibration").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(
            jsonPath("$.models[0].reason")
                .value("analytical store not configured in this environment"))
        .andExpect(jsonPath("$.models[3].calibration").value(org.hamcrest.Matchers.nullValue()));
  }

  @Test
  void rollingAccuracy_servesCalibrationEvenWhenTheTop1WindowHasNoTruth() throws Exception {
    // Off-day / offseason shape: the 7-day top-1 window is empty, the 30-day rollup is not.
    RollingAccuracyRepository rolling = mock(RollingAccuracyRepository.class);
    when(rolling.pitchOutcomeDaily(anyString(), anyInt())).thenReturn(List.of());
    when(rolling.pitchTypeDaily(anyInt())).thenReturn(List.of());
    LiveReliabilityRepository reliability = mock(LiveReliabilityRepository.class);
    when(reliability.window(anyString(), anyInt()))
        .thenReturn(new ReliabilityWindow(ReliabilityWindow.Kind.CALENDAR, List.of()));
    when(reliability.window("pitch_outcome_pre", 14))
        .thenReturn(
            new ReliabilityWindow(
                ReliabilityWindow.Kind.LAST_DAYS_OF_PLAY,
                List.of(
                    new ReliabilityBinRow(java.time.LocalDate.of(2026, 9, 28), 6, 400, 250, 258.0),
                    new ReliabilityBinRow(java.time.LocalDate.of(2026, 9, 29), 4, 100, 41, 45.0))));

    mvcWithLiveStore(rolling, reliability)
        .perform(get("/v1/ops/rolling-accuracy").param("calibrationDays", "14"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.calibrationWindowDays").value(14))
        .andExpect(jsonPath("$.models[0].status").value("no_live_truth"))
        .andExpect(jsonPath("$.models[0].calibration.windowDays").value(14))
        .andExpect(jsonPath("$.models[0].calibration.windowKind").value("last_days_of_play"))
        .andExpect(jsonPath("$.models[0].calibration.n").value(500))
        .andExpect(jsonPath("$.models[0].calibration.truthFrom").value("2026-09-28"))
        .andExpect(jsonPath("$.models[0].calibration.truthThrough").value("2026-09-29"))
        .andExpect(jsonPath("$.models[0].calibration.gameDays").value(2))
        .andExpect(jsonPath("$.models[0].calibration.bins.length()").value(2))
        .andExpect(jsonPath("$.models[0].calibration.bins[0].lower").value(0.4))
        .andExpect(jsonPath("$.models[0].calibration.bins[1].observed").value(0.625))
        // an empty window is n = 0 with an empty bin list, never a fabricated bin
        .andExpect(jsonPath("$.models[1].calibration.n").value(0))
        .andExpect(jsonPath("$.models[1].calibration.bins.length()").value(0))
        .andExpect(
            jsonPath("$.models[1].calibration.truthFrom").value(org.hamcrest.Matchers.nullValue()))
        // batted-ball is structurally null
        .andExpect(jsonPath("$.models[2].calibration").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(jsonPath("$.models[3].calibration.windowKind").value("calendar"));
  }

  @Test
  void rollingAccuracy_aFailedCalibrationReadDegradesToNull_notA500() throws Exception {
    RollingAccuracyRepository rolling = mock(RollingAccuracyRepository.class);
    when(rolling.pitchOutcomeDaily(anyString(), anyInt())).thenReturn(List.of());
    when(rolling.pitchTypeDaily(anyInt())).thenReturn(List.of());
    LiveReliabilityRepository reliability = mock(LiveReliabilityRepository.class);
    when(reliability.window(anyString(), anyInt()))
        .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("ch down"));

    mvcWithLiveStore(rolling, reliability)
        .perform(get("/v1/ops/rolling-accuracy"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.models[0].calibration").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(jsonPath("$.models[0].status").value("no_live_truth"));
  }
}
