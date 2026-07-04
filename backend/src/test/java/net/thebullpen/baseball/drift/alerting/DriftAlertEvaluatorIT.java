package net.thebullpen.baseball.drift.alerting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.thebullpen.baseball.data.JobLockRepository;
import net.thebullpen.baseball.drift.DriftMetric;
import net.thebullpen.baseball.drift.DriftMetricsRepository;
import net.thebullpen.baseball.drift.MetricType;
import net.thebullpen.baseball.registry.DiscordNotifier;
import net.thebullpen.baseball.registry.RegistryRepository;
import net.thebullpen.baseball.registry.dto.ModelVersion;
import net.thebullpen.baseball.registry.dto.Stage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * IT for {@link DriftAlertEvaluator} — exercises the threshold logic + 24h dedup against a real
 * SQLite registry. Uses mocked {@link DriftMetricsRepository} + mocked {@link DiscordNotifier} so
 * the evaluator's pure-orchestration behavior is asserted without ClickHouse Testcontainers. The
 * real {@link AlertHistoryRepository} runs against the per-test temp SQLite (V014 applied by Flyway
 * on boot) so the dedup math hits real-row semantics.
 */
@SpringBootTest
@ActiveProfiles({"api", "registry-it"})
class DriftAlertEvaluatorIT {

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    Path dbPath =
        Path.of(
            System.getProperty("java.io.tmpdir"),
            "bullpen-alert-it-" + UUID.randomUUID() + ".sqlite");
    String url = "jdbc:sqlite:" + dbPath;
    registry.add("spring.datasource.url", () -> url);
    registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
    registry.add("spring.flyway.url", () -> url);
    Path snapshotBase =
        Path.of(
            System.getProperty("java.io.tmpdir"),
            "bullpen-alert-it-snapshots-" + UUID.randomUUID());
    registry.add("bullpen.snapshot.local-base-path", snapshotBase::toString);
  }

  @Autowired private AlertHistoryRepository historyRepo;
  @Autowired private JdbcTemplate jdbc;

  // Mocks created per-test; the production beans for these are NOT autowired so we can build
  // the evaluator manually with our stubs.
  private RegistryRepository registryRepo;
  private DriftMetricsRepository driftRepo;
  private DiscordNotifier discord;
  private DriftAlertEvaluator evaluator;

  @BeforeEach
  void setUp() {
    jdbc.update("DELETE FROM alert_history");
    registryRepo = mock(RegistryRepository.class);
    driftRepo = mock(DriftMetricsRepository.class);
    discord = mock(DiscordNotifier.class);
    evaluator =
        new DriftAlertEvaluator(
            registryRepo,
            driftRepo,
            historyRepo,
            discord,
            mock(JobLockRepository.class),
            0.10,
            0.25);
  }

  // --- PAGE: calibration ------------------------------------------------

  @Test
  void calibration_above_threshold_for_3_days_fires_page_and_records_history() {
    ModelVersion champ = champion("pitch_outcome_pre", 1L);
    when(registryRepo.findActiveChampions()).thenReturn(List.of(champ));
    when(driftRepo.findRecent(
            eq("pitch_outcome_pre"),
            eq(MetricType.CALIBRATION_ERROR),
            eq("all"),
            any(Duration.class)))
        .thenReturn(
            threeDailyMetrics("pitch_outcome_pre", MetricType.CALIBRATION_ERROR, "all", 0.15));
    when(driftRepo.findAllForModel("pitch_outcome_pre")).thenReturn(List.of());

    int fired = evaluator.runOnce();
    assertThat(fired).isEqualTo(1);
    verify(discord)
        .send(
            eq(DiscordNotifier.Severity.WARN),
            eq("PAGE: pitch_outcome_pre calibration drifted"),
            any());
    assertThat(historyRepo.countFor("drift/pitch_outcome_pre/calibration_error/all")).isEqualTo(1L);
  }

  @Test
  void calibration_below_threshold_does_not_fire() {
    ModelVersion champ = champion("model_a", 1L);
    when(registryRepo.findActiveChampions()).thenReturn(List.of(champ));
    when(driftRepo.findRecent(
            eq("model_a"), eq(MetricType.CALIBRATION_ERROR), eq("all"), any(Duration.class)))
        .thenReturn(threeDailyMetrics("model_a", MetricType.CALIBRATION_ERROR, "all", 0.05));
    when(driftRepo.findAllForModel("model_a")).thenReturn(List.of());

    assertThat(evaluator.runOnce()).isEqualTo(0);
    verify(discord, never()).send(any(), any(), any());
  }

  @Test
  void calibration_with_mixed_days_not_all_over_does_not_fire() {
    ModelVersion champ = champion("model_a", 1L);
    when(registryRepo.findActiveChampions()).thenReturn(List.of(champ));
    Instant now = Instant.now();
    List<DriftMetric> mixed = new ArrayList<>();
    mixed.add(
        metric(
            "model_a", MetricType.CALIBRATION_ERROR, "all", 0.15, now.minus(0, ChronoUnit.DAYS)));
    mixed.add(
        metric(
            "model_a", MetricType.CALIBRATION_ERROR, "all", 0.05, now.minus(1, ChronoUnit.DAYS)));
    mixed.add(
        metric(
            "model_a", MetricType.CALIBRATION_ERROR, "all", 0.15, now.minus(2, ChronoUnit.DAYS)));
    when(driftRepo.findRecent(
            eq("model_a"), eq(MetricType.CALIBRATION_ERROR), eq("all"), any(Duration.class)))
        .thenReturn(mixed);
    when(driftRepo.findAllForModel("model_a")).thenReturn(List.of());

    assertThat(evaluator.runOnce()).isEqualTo(0);
    verify(discord, never()).send(any(), any(), any());
  }

  @Test
  void calibration_with_fewer_than_3_samples_does_not_fire() {
    ModelVersion champ = champion("model_a", 1L);
    when(registryRepo.findActiveChampions()).thenReturn(List.of(champ));
    when(driftRepo.findRecent(
            eq("model_a"), eq(MetricType.CALIBRATION_ERROR), eq("all"), any(Duration.class)))
        .thenReturn(
            List.of(metric("model_a", MetricType.CALIBRATION_ERROR, "all", 0.15, Instant.now())));
    when(driftRepo.findAllForModel("model_a")).thenReturn(List.of());

    assertThat(evaluator.runOnce()).isEqualTo(0);
  }

  @Test
  void calibration_three_same_day_reruns_do_not_fire_page() {
    // DEF-M3: 3 rows ALL on the same calendar day (a thrice-rerun 2:30 batch), each over the
    // threshold. Counting rows would fire a false "3 consecutive days" PAGE; counting calendar
    // days must not. Fixed midday-NY instants so the same-day grouping can't straddle midnight.
    ModelVersion champ = champion("model_a", 1L);
    when(registryRepo.findActiveChampions()).thenReturn(List.of(champ));
    Instant noonEt = Instant.parse("2026-06-06T16:00:00Z"); // 12:00 EDT
    List<DriftMetric> sameDay =
        List.of(
            metric("model_a", MetricType.CALIBRATION_ERROR, "all", 0.15, noonEt),
            metric(
                "model_a",
                MetricType.CALIBRATION_ERROR,
                "all",
                0.15,
                noonEt.minus(2, ChronoUnit.HOURS)),
            metric(
                "model_a",
                MetricType.CALIBRATION_ERROR,
                "all",
                0.15,
                noonEt.minus(4, ChronoUnit.HOURS)));
    when(driftRepo.findRecent(
            eq("model_a"), eq(MetricType.CALIBRATION_ERROR), eq("all"), any(Duration.class)))
        .thenReturn(sameDay);
    when(driftRepo.findAllForModel("model_a")).thenReturn(List.of());

    assertThat(evaluator.runOnce()).as("3 same-day reruns are 1 day, not 3").isEqualTo(0);
    verify(discord, never()).send(any(), any(), any());
  }

  @Test
  void calibration_latest_same_day_rerun_wins_over_earlier_under_threshold() {
    // DEF-M3 latest-wins: day 0 had an early under-threshold reading then a later over-threshold
    // correction; days 1 and 2 are over. The corrected (latest) value for day 0 should win, so all
    // 3 distinct days are over and the PAGE fires.
    ModelVersion champ = champion("model_a", 1L);
    when(registryRepo.findActiveChampions()).thenReturn(List.of(champ));
    Instant day0Noon = Instant.parse("2026-06-06T16:00:00Z");
    List<DriftMetric> rows =
        List.of(
            metric(
                "model_a",
                MetricType.CALIBRATION_ERROR,
                "all",
                0.05,
                day0Noon.minus(3, ChronoUnit.HOURS)), // early, under threshold
            metric("model_a", MetricType.CALIBRATION_ERROR, "all", 0.15, day0Noon), // correction
            metric(
                "model_a",
                MetricType.CALIBRATION_ERROR,
                "all",
                0.15,
                day0Noon.minus(1, ChronoUnit.DAYS)),
            metric(
                "model_a",
                MetricType.CALIBRATION_ERROR,
                "all",
                0.15,
                day0Noon.minus(2, ChronoUnit.DAYS)));
    when(driftRepo.findRecent(
            eq("model_a"), eq(MetricType.CALIBRATION_ERROR), eq("all"), any(Duration.class)))
        .thenReturn(rows);
    when(driftRepo.findAllForModel("model_a")).thenReturn(List.of());

    assertThat(evaluator.runOnce()).as("latest same-day correction wins").isEqualTo(1);
  }

  // --- 24h dedup --------------------------------------------------------

  @Test
  void second_run_within_24h_is_suppressed_by_dedup() {
    ModelVersion champ = champion("model_a", 1L);
    when(registryRepo.findActiveChampions()).thenReturn(List.of(champ));
    when(driftRepo.findRecent(
            eq("model_a"), eq(MetricType.CALIBRATION_ERROR), eq("all"), any(Duration.class)))
        .thenReturn(threeDailyMetrics("model_a", MetricType.CALIBRATION_ERROR, "all", 0.15));
    when(driftRepo.findAllForModel("model_a")).thenReturn(List.of());

    int firstRun = evaluator.runOnce();
    int secondRun = evaluator.runOnce();

    assertThat(firstRun).isEqualTo(1);
    assertThat(secondRun).as("second run within 24h must be suppressed").isEqualTo(0);
    verify(discord, times(1)).send(any(), any(), any());
    assertThat(historyRepo.countFor("drift/model_a/calibration_error/all")).isEqualTo(1L);
  }

  // --- NOTICE: feature PSI ----------------------------------------------

  @Test
  void feature_psi_above_threshold_for_7_days_fires_notice() {
    ModelVersion champ = champion("model_a", 1L);
    when(registryRepo.findActiveChampions()).thenReturn(List.of(champ));
    when(driftRepo.findRecent(
            eq("model_a"), eq(MetricType.CALIBRATION_ERROR), eq("all"), any(Duration.class)))
        .thenReturn(List.of());
    List<DriftMetric> rows = new ArrayList<>();
    Instant now = Instant.now();
    for (int i = 0; i < 7; i++) {
      rows.add(
          metric(
              "model_a",
              MetricType.PSI_FEATURE,
              "launch_speed",
              0.30,
              now.minus(i, ChronoUnit.DAYS)));
    }
    when(driftRepo.findAllForModel("model_a")).thenReturn(rows);

    int fired = evaluator.runOnce();
    assertThat(fired).isEqualTo(1);
    verify(discord)
        .send(
            eq(DiscordNotifier.Severity.NOTICE),
            eq("NOTICE: model_a feature drift on launch_speed"),
            any());
  }

  @Test
  void feature_psi_below_threshold_does_not_fire_notice() {
    ModelVersion champ = champion("model_a", 1L);
    when(registryRepo.findActiveChampions()).thenReturn(List.of(champ));
    when(driftRepo.findRecent(
            eq("model_a"), eq(MetricType.CALIBRATION_ERROR), eq("all"), any(Duration.class)))
        .thenReturn(List.of());
    List<DriftMetric> rows = new ArrayList<>();
    Instant now = Instant.now();
    for (int i = 0; i < 7; i++) {
      rows.add(
          metric(
              "model_a",
              MetricType.PSI_FEATURE,
              "launch_speed",
              0.05,
              now.minus(i, ChronoUnit.DAYS)));
    }
    when(driftRepo.findAllForModel("model_a")).thenReturn(rows);

    assertThat(evaluator.runOnce()).isEqualTo(0);
  }

  @Test
  void feature_psi_only_4_of_7_days_above_threshold_does_not_fire() {
    ModelVersion champ = champion("model_a", 1L);
    when(registryRepo.findActiveChampions()).thenReturn(List.of(champ));
    when(driftRepo.findRecent(
            eq("model_a"), eq(MetricType.CALIBRATION_ERROR), eq("all"), any(Duration.class)))
        .thenReturn(List.of());
    List<DriftMetric> rows = new ArrayList<>();
    Instant now = Instant.now();
    double[] values = {0.30, 0.30, 0.30, 0.30, 0.05, 0.05, 0.05};
    for (int i = 0; i < 7; i++) {
      rows.add(
          metric(
              "model_a",
              MetricType.PSI_FEATURE,
              "launch_speed",
              values[i],
              now.minus(i, ChronoUnit.DAYS)));
    }
    when(driftRepo.findAllForModel("model_a")).thenReturn(rows);

    assertThat(evaluator.runOnce()).isEqualTo(0);
  }

  // --- helpers ----------------------------------------------------------

  private static List<DriftMetric> threeDailyMetrics(
      String modelName, MetricType type, String feature, double value) {
    Instant now = Instant.now();
    List<DriftMetric> rows = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      rows.add(metric(modelName, type, feature, value, now.minus(i, ChronoUnit.DAYS)));
    }
    return rows;
  }

  private static DriftMetric metric(
      String modelName, MetricType type, String feature, double value, Instant at) {
    return new DriftMetric(
        at, modelName, 1L, type, feature, value, 1000L, at.minus(24, ChronoUnit.HOURS), at);
  }

  private static ModelVersion champion(String name, long id) {
    return new ModelVersion(
        id,
        name,
        "v1",
        "/tmp/" + name + "/v1/model.onnx",
        "/tmp/" + name + "/v1/metadata.json",
        "train-hash",
        "[2024,2024]",
        "schema-hash",
        "{}",
        Instant.now(),
        Instant.now(),
        Stage.CHAMPION,
        "test",
        null,
        Instant.now(),
        Instant.now());
  }

  // Quiet unused-import warnings the formatter sometimes complains about.
  @SuppressWarnings("unused")
  private static long anyVersionId() {
    return anyLong();
  }
}
