package net.thebullpen.baseball.drift;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import net.thebullpen.baseball.data.JobLockRepository;
import net.thebullpen.baseball.drift.TruthJoinedPredictionFetcher.TruthJoinedRow;
import net.thebullpen.baseball.drift.alerting.AlertHistoryRepository;
import net.thebullpen.baseball.drift.alerting.DriftAlertEvaluator;
import net.thebullpen.baseball.drift.algorithms.Psi;
import net.thebullpen.baseball.drift.jobs.CalibrationJob;
import net.thebullpen.baseball.registry.DiscordNotifier;
import net.thebullpen.baseball.registry.RegistryRepository;
import net.thebullpen.baseball.registry.dto.ModelVersion;
import net.thebullpen.baseball.registry.dto.Stage;
import net.thebullpen.baseball.retraining.RetrainingQueueService;
import net.thebullpen.baseball.retraining.dto.TriggerType;
import net.thebullpen.baseball.retraining.triggers.DriftTrigger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Drift-induction DRILL (plan S3 — the centerpiece-postmortem evidence generator).
 *
 * <p>Injects a controlled, known drift into the champion and drives the REAL detection chain
 * end-to-end:
 *
 * <ol>
 *   <li>1σ feature shift on {@code launch_speed} → real {@link Psi#computeContinuous} PSI
 *   <li>Systematic over-confidence → real {@link CalibrationJob} ECE
 *   <li>Sustained over the alert windows → real {@link DriftAlertEvaluator} fires PAGE
 *       (calibration) + NOTICE (feature PSI)
 *   <li>Real {@link DriftTrigger} enqueues a {@code TriggerType.DRIFT} retrain in the real
 *       (SQLite-backed) {@link RetrainingQueueService}
 * </ol>
 *
 * <p>The PSI/ECE VALUES are real math on the injected data; the alert + trigger + queue logic is
 * the production code. Only the ClickHouse {@code drift_metrics} read/persist is mocked (the values
 * fed in are the real computed ones) — honest for a drill (see docs/postmortems/README.md "Drill
 * events"). The stdout transcript is the evidence pasted into the postmortem.
 *
 * <p>@Tag("drill") — excluded from normal CI. Run on demand: {@code ./gradlew test -PrunDrills
 * --tests "*DriftInductionDrillIT"}.
 */
@SpringBootTest
@ActiveProfiles({"api", "registry-it"})
@Tag("drill")
class DriftInductionDrillIT {

  private static final String MODEL = "battedball_outcome";
  private static final long VERSION_ID = 1L;
  private static final double PAGE_ECE_THRESHOLD = 0.10;
  private static final double NOTICE_PSI_THRESHOLD = 0.25;

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    Path dbPath =
        Path.of(
            System.getProperty("java.io.tmpdir"), "bullpen-drill-" + UUID.randomUUID() + ".sqlite");
    String url = "jdbc:sqlite:" + dbPath;
    registry.add("spring.datasource.url", () -> url);
    registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
    registry.add("spring.flyway.url", () -> url);
    Path snapshotBase =
        Path.of(
            System.getProperty("java.io.tmpdir"), "bullpen-drill-snapshots-" + UUID.randomUUID());
    registry.add("bullpen.snapshot.local-base-path", snapshotBase::toString);
  }

  @Autowired private AlertHistoryRepository historyRepo;
  @Autowired private RetrainingQueueService queue;
  @Autowired private JdbcTemplate jdbc;

  private RegistryRepository registryRepo;
  private DriftMetricsRepository driftRepo;
  private DiscordNotifier discord;

  @BeforeEach
  void setUp() {
    jdbc.update("DELETE FROM alert_history");
    registryRepo = mock(RegistryRepository.class);
    driftRepo = mock(DriftMetricsRepository.class);
    discord = mock(DiscordNotifier.class);
    ModelVersion champ = champion(MODEL, VERSION_ID);
    when(registryRepo.findActiveChampions()).thenReturn(List.of(champ));
  }

  @Test
  void induced_drift_fires_page_notice_and_enqueues_a_drift_retrain() throws Exception {
    log("==================================================================");
    log("DRIFT INDUCTION DRILL — controlled synthetic drift on " + MODEL + " v1");
    log("==================================================================");

    // --- 1. Inject a 1σ feature shift → real PSI -----------------------------
    double psi = inducedFeaturePsi();
    log(
        String.format(
            "INJECT  1σ mean shift on launch_speed  ->  PSI = %.3f  (NOTICE threshold %.2f)",
            psi, NOTICE_PSI_THRESHOLD));
    assertThat(psi).as("1σ shift should clear the significant PSI threshold").isGreaterThan(0.25);

    // --- 2. Inject systematic over-confidence → real ECE (CalibrationJob) -----
    double ece = inducedCalibrationEce();
    log(
        String.format(
            "INJECT  over-confident predictions     ->  ECE = %.3f  (PAGE threshold  %.2f)",
            ece, PAGE_ECE_THRESHOLD));
    assertThat(ece)
        .as("injected miscalibration should clear the PAGE ECE threshold")
        .isGreaterThan(0.10);

    // --- 3. Stage the metrics sustained over the alert windows ---------------
    Instant now = Instant.now();
    stageSustainedDrift(psi, ece, now);
    log("STAGE   metrics sustained 7d (CALIBRATION_ERROR + PSI_FEATURE)");

    // --- 4. Real DriftAlertEvaluator → PAGE + NOTICE -------------------------
    DriftAlertEvaluator evaluator =
        new DriftAlertEvaluator(
            registryRepo,
            driftRepo,
            historyRepo,
            discord,
            mock(JobLockRepository.class),
            PAGE_ECE_THRESHOLD,
            NOTICE_PSI_THRESHOLD,
            7); // feature-PSI notice sustain window: prod default (the drill stages 7 days)
    int alerts = evaluator.runOnce();
    log("DETECT  DriftAlertEvaluator fired " + alerts + " alert(s)");
    ArgumentCaptor<DiscordNotifier.Severity> sev =
        ArgumentCaptor.forClass(DiscordNotifier.Severity.class);
    ArgumentCaptor<String> title = ArgumentCaptor.forClass(String.class);
    verify(discord, org.mockito.Mockito.atLeastOnce()).send(sev.capture(), title.capture(), any());
    for (int i = 0; i < title.getAllValues().size(); i++) {
      log("ALERT   [" + sev.getAllValues().get(i) + "] " + title.getAllValues().get(i));
    }
    assertThat(alerts).as("both PAGE (calibration) + NOTICE (feature) should fire").isEqualTo(2);

    // --- 5. Real DriftTrigger → enqueue a DRIFT retrain ----------------------
    DriftTrigger trigger =
        new DriftTrigger(registryRepo, driftRepo, queue, discord, PAGE_ECE_THRESHOLD);
    int enqueued = trigger.runOnce(now);
    log("TRIGGER DriftTrigger enqueued " + enqueued + " retrain(s)");

    List<net.thebullpen.baseball.retraining.dto.RetrainingTrigger> queued = queue.findAllQueued();
    assertThat(queued)
        .as("a DRIFT-typed retrain for " + MODEL + " must be queued")
        .anyMatch(t -> t.modelName().equals(MODEL) && t.triggerType() == TriggerType.DRIFT);
    queued.stream()
        .filter(t -> t.modelName().equals(MODEL))
        .forEach(
            t ->
                log(
                    "QUEUE   trigger_id="
                        + t.triggerId()
                        + " model="
                        + t.modelName()
                        + " type="
                        + t.triggerType()
                        + " status="
                        + t.status()));
    log("==================================================================");
    log("DRILL COMPLETE — full chain: inject → detect → alert → enqueue ✔");
    log("==================================================================");
  }

  // --- injection helpers (real math) --------------------------------------

  /** 1σ Gaussian mean shift between reference + observed → real continuous PSI. */
  private static double inducedFeaturePsi() {
    Random r = new Random(42);
    int n = 10_000;
    double[] reference = new double[n];
    double[] observed = new double[n];
    for (int i = 0; i < n; i++) {
      reference[i] = r.nextGaussian();
      observed[i] = r.nextGaussian() + 1.0; // 1σ shift
    }
    return Psi.computeContinuous(reference, observed, Psi.DEFAULT_BINS);
  }

  /** Over-confident predictions (0.75 on class 0, only ~58% actually class 0) → real ECE. */
  private double inducedCalibrationEce() throws Exception {
    TruthJoinedPredictionFetcher fetcher = mock(TruthJoinedPredictionFetcher.class);
    DriftMetricsRepository captureRepo = mock(DriftMetricsRepository.class);
    Random r = new Random(7);
    List<TruthJoinedRow> joined = new ArrayList<>();
    for (int i = 0; i < 2000; i++) {
      int actual = r.nextDouble() < 0.58 ? 0 : 1; // 58% truly class 0
      joined.add(new TruthJoinedRow(new double[] {0.75, 0.25}, actual)); // but 0.75 confident
    }
    when(fetcher.fetch(eq(MODEL), eq(VERSION_ID), any(Instant.class), any(Instant.class)))
        .thenReturn(joined);
    new CalibrationJob(registryRepo, fetcher, captureRepo, mock(JobLockRepository.class))
        .runOnce(Instant.now());

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<DriftMetric>> cap = ArgumentCaptor.forClass(List.class);
    verify(captureRepo).insertBatch(cap.capture());
    return cap.getValue().stream()
        .filter(m -> m.metricType() == MetricType.CALIBRATION_ERROR)
        .map(DriftMetric::metricValue)
        .findFirst()
        .orElseThrow(() -> new AssertionError("CalibrationJob wrote no ECE row"));
  }

  /** Mock the ClickHouse drift_metrics reads the evaluator + trigger do. */
  private void stageSustainedDrift(double psi, double ece, Instant now) {
    // 7 daily rows: the DriftAlertEvaluator PAGE needs ≥3 consecutive over-threshold,
    // the DriftTrigger needs ≥7 over-threshold — stage 7 to satisfy both.
    List<DriftMetric> ece7d = new ArrayList<>();
    List<DriftMetric> psi7d = new ArrayList<>();
    for (int i = 0; i < 7; i++) {
      ece7d.add(metric(MetricType.CALIBRATION_ERROR, "all", ece, now.minus(i, ChronoUnit.DAYS)));
      psi7d.add(metric(MetricType.PSI_FEATURE, "launch_speed", psi, now.minus(i, ChronoUnit.DAYS)));
    }
    when(driftRepo.findRecent(
            eq(MODEL), eq(MetricType.CALIBRATION_ERROR), eq("all"), any(Duration.class)))
        .thenReturn(ece7d);
    when(driftRepo.findAllForModel(MODEL)).thenReturn(psi7d);
  }

  private static DriftMetric metric(MetricType type, String feature, double value, Instant at) {
    return new DriftMetric(
        at, MODEL, VERSION_ID, type, feature, value, 2000L, at.minus(24, ChronoUnit.HOURS), at);
  }

  private static ModelVersion champion(String name, long id) {
    return new ModelVersion(
        id,
        name,
        "v1",
        "/tmp/" + name + "/v1/model.onnx",
        "/tmp/" + name + "/v1/metadata.json",
        "train-hash",
        "[2015,2024]",
        "schema-hash",
        "{}",
        Instant.now(),
        Instant.now(),
        Stage.CHAMPION,
        "drill",
        null,
        Instant.now(),
        Instant.now());
  }

  private static void log(String line) {
    System.out.println("[drill " + Instant.now() + "] " + line);
  }
}
