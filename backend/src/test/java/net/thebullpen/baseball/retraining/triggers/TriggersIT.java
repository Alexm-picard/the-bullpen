package net.thebullpen.baseball.retraining.triggers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.thebullpen.baseball.drift.DriftMetric;
import net.thebullpen.baseball.drift.DriftMetricsRepository;
import net.thebullpen.baseball.drift.MetricType;
import net.thebullpen.baseball.registry.DiscordNotifier;
import net.thebullpen.baseball.registry.RegistryService;
import net.thebullpen.baseball.registry.dto.ModelVersion;
import net.thebullpen.baseball.registry.dto.RegisterRequest;
import net.thebullpen.baseball.registry.dto.Stage;
import net.thebullpen.baseball.retraining.RetrainingQueueService;
import net.thebullpen.baseball.retraining.dto.QueueStatus;
import net.thebullpen.baseball.retraining.dto.RetrainingTrigger;
import net.thebullpen.baseball.retraining.dto.TriggerType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * End-to-end IT for the three trigger producers (leaf 3d.2). Uses the real {@link
 * RetrainingQueueService} + real registry but stubs the DriftMetricsRepository so the drift-trigger
 * tests can inject synthetic calibration-error rows without standing Testcontainers ClickHouse.
 */
@SpringBootTest
@ActiveProfiles({"api", "registry-it"})
class TriggersIT {

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    Path dbPath =
        Path.of(
            System.getProperty("java.io.tmpdir"),
            "bullpen-triggers-it-" + UUID.randomUUID() + ".sqlite");
    String url = "jdbc:sqlite:" + dbPath;
    registry.add("spring.datasource.url", () -> url);
    registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
    registry.add("spring.flyway.url", () -> url);
    Path snapshotBase =
        Path.of(
            System.getProperty("java.io.tmpdir"),
            "bullpen-triggers-it-snapshots-" + UUID.randomUUID());
    registry.add("bullpen.snapshot.local-base-path", snapshotBase::toString);
  }

  @Autowired private RegistryService registry;
  @Autowired private net.thebullpen.baseball.registry.RegistryRepository registryRepo;
  @Autowired private RetrainingQueueService queue;
  @Autowired private ManualTrigger manualTrigger;
  @Autowired private JdbcTemplate jdbc;

  @TempDir Path artifactDir;

  @BeforeEach
  void reset() {
    jdbc.update("DELETE FROM retraining_queue");
    jdbc.update("DELETE FROM experiment_results");
    jdbc.update("DELETE FROM model_routing");
    jdbc.update("DELETE FROM model_versions");
  }

  // --- ScheduledTrigger -------------------------------------------------

  @Test
  void scheduled_trigger_enqueues_one_row_per_active_champion() throws Exception {
    long champA = registerAndPromote("model_a");
    long champB = registerAndPromote("model_b");

    ScheduledTrigger trigger = new ScheduledTrigger(registryRepo, queue);
    int enqueued = trigger.runOnce(LocalDate.of(2026, 6, 1));

    assertThat(enqueued).isEqualTo(2);
    List<RetrainingTrigger> qa = queue.findByModel("model_a");
    List<RetrainingTrigger> qb = queue.findByModel("model_b");
    assertThat(qa).hasSize(1);
    assertThat(qb).hasSize(1);
    assertThat(qa.get(0).triggerType()).isEqualTo(TriggerType.SCHEDULED);
    assertThat(qa.get(0).triggerId()).isEqualTo("sched-202606-model_a");
    assertThat(qa.get(0).status()).isEqualTo(QueueStatus.QUEUED);
    // Champion version id surface check
    assertThat(qa.get(0).triggerMetadata()).contains("\"champion_version_id\":" + champA);
    assertThat(qb.get(0).triggerMetadata()).contains("\"champion_version_id\":" + champB);
  }

  @Test
  void scheduled_trigger_second_run_same_month_is_idempotent() throws Exception {
    registerAndPromote("model_a");
    ScheduledTrigger trigger = new ScheduledTrigger(registryRepo, queue);
    LocalDate today = LocalDate.of(2026, 6, 1);

    int first = trigger.runOnce(today);
    int second = trigger.runOnce(today);

    assertThat(first).isEqualTo(1);
    assertThat(second).as("same-month re-run should dedup via stable trigger_id").isEqualTo(0);
    assertThat(queue.findByModel("model_a")).hasSize(1);
  }

  @Test
  void scheduled_trigger_with_no_champions_enqueues_nothing() throws Exception {
    ScheduledTrigger trigger = new ScheduledTrigger(registryRepo, queue);
    assertThat(trigger.runOnce(LocalDate.of(2026, 6, 1))).isEqualTo(0);
  }

  // --- DriftTrigger -----------------------------------------------------

  @Test
  void drift_trigger_with_7_days_sustained_drift_enqueues_once() throws Exception {
    long champ = registerAndPromote("model_a");
    DriftMetricsRepository driftRepo = mock(DriftMetricsRepository.class);
    DiscordNotifier discord = mock(DiscordNotifier.class);
    when(driftRepo.findRecent(
            eq("model_a"), eq(MetricType.CALIBRATION_ERROR), eq("all"), any(Duration.class)))
        .thenReturn(buildDriftSeries(7, 0.15));

    DriftTrigger trigger = new DriftTrigger(registryRepo, driftRepo, queue, discord, 0.10);
    int enqueued = trigger.runOnce(Instant.now());

    assertThat(enqueued).isEqualTo(1);
    List<RetrainingTrigger> rows = queue.findByModel("model_a");
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).triggerType()).isEqualTo(TriggerType.DRIFT);
    assertThat(rows.get(0).triggerMetadata()).contains("\"champion_version_id\":" + champ);
    verify(discord)
        .send(
            eq(DiscordNotifier.Severity.NOTICE),
            eq("Drift-triggered retrain enqueued for model_a"),
            any());
  }

  @Test
  void drift_trigger_with_calibration_below_threshold_does_not_enqueue() throws Exception {
    registerAndPromote("model_a");
    DriftMetricsRepository driftRepo = mock(DriftMetricsRepository.class);
    DiscordNotifier discord = mock(DiscordNotifier.class);
    when(driftRepo.findRecent(
            eq("model_a"), eq(MetricType.CALIBRATION_ERROR), eq("all"), any(Duration.class)))
        .thenReturn(buildDriftSeries(7, 0.05));

    DriftTrigger trigger = new DriftTrigger(registryRepo, driftRepo, queue, discord, 0.10);
    assertThat(trigger.runOnce(Instant.now())).isEqualTo(0);
    verify(discord, never()).send(any(), any(), any());
  }

  @Test
  void drift_trigger_with_fewer_than_7_samples_does_not_enqueue() throws Exception {
    registerAndPromote("model_a");
    DriftMetricsRepository driftRepo = mock(DriftMetricsRepository.class);
    DiscordNotifier discord = mock(DiscordNotifier.class);
    when(driftRepo.findRecent(
            eq("model_a"), eq(MetricType.CALIBRATION_ERROR), eq("all"), any(Duration.class)))
        .thenReturn(buildDriftSeries(6, 0.20));

    DriftTrigger trigger = new DriftTrigger(registryRepo, driftRepo, queue, discord, 0.10);
    assertThat(trigger.runOnce(Instant.now())).isEqualTo(0);
  }

  @Test
  void drift_trigger_second_run_within_dedup_window_is_suppressed() throws Exception {
    registerAndPromote("model_a");
    DriftMetricsRepository driftRepo = mock(DriftMetricsRepository.class);
    DiscordNotifier discord = mock(DiscordNotifier.class);
    when(driftRepo.findRecent(
            eq("model_a"), eq(MetricType.CALIBRATION_ERROR), eq("all"), any(Duration.class)))
        .thenReturn(buildDriftSeries(7, 0.15));

    DriftTrigger trigger = new DriftTrigger(registryRepo, driftRepo, queue, discord, 0.10);
    int first = trigger.runOnce(Instant.now());
    int second = trigger.runOnce(Instant.now());

    assertThat(first).isEqualTo(1);
    assertThat(second).as("drift retrain already queued within 7d → dedup").isEqualTo(0);
    assertThat(queue.findByModel("model_a")).hasSize(1);
  }

  // --- ManualTrigger ----------------------------------------------------

  @Test
  void manual_trigger_enqueues_with_caller_metadata() throws Exception {
    registerAndPromote("model_a");
    RetrainingTrigger row =
        manualTrigger.enqueue("model_a", "regression in v1 ECE on holdout", "alex");
    assertThat(row.triggerType()).isEqualTo(TriggerType.MANUAL);
    assertThat(row.triggerMetadata()).contains("\"reason\":\"regression");
    assertThat(row.triggerMetadata()).contains("\"requested_by\":\"alex\"");
  }

  @Test
  void manual_trigger_second_call_within_1h_returns_existing_row() throws Exception {
    registerAndPromote("model_a");
    RetrainingTrigger first = manualTrigger.enqueue("model_a", "first call", "alex");
    RetrainingTrigger second = manualTrigger.enqueue("model_a", "second call", "alex");
    assertThat(second.triggerId())
        .as("manual within 1h dedup returns existing trigger_id")
        .isEqualTo(first.triggerId());
    assertThat(queue.findByModel("model_a")).hasSize(1);
  }

  // --- helpers ----------------------------------------------------------

  private List<DriftMetric> buildDriftSeries(int days, double value) {
    Instant now = Instant.now();
    List<DriftMetric> rows = new ArrayList<>();
    for (int i = 0; i < days; i++) {
      Instant at = now.minus(i, ChronoUnit.DAYS);
      rows.add(
          new DriftMetric(
              at,
              "model_a",
              1L,
              MetricType.CALIBRATION_ERROR,
              "all",
              value,
              5000L,
              at.minus(24, ChronoUnit.HOURS),
              at));
    }
    return rows;
  }

  private long registerAndPromote(String modelName) throws Exception {
    ModelVersion v = registry.register(sampleRequest(modelName, "v1"));
    registry.transitionStage(v.id(), Stage.CHAMPION);
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
        "triggers-it",
        "registered by TriggersIT");
  }

  // quiet unused-import warning
  @SuppressWarnings("unused")
  private static long unusedAnyLong() {
    return anyLong();
  }
}
