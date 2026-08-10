package net.thebullpen.baseball.drift.jobs;

import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import net.thebullpen.baseball.data.JobLockRepository;
import net.thebullpen.baseball.drift.DriftMetric;
import net.thebullpen.baseball.drift.DriftMetricsRepository;
import net.thebullpen.baseball.drift.MetricType;
import net.thebullpen.baseball.drift.TruthJoinedPredictionFetcher;
import net.thebullpen.baseball.drift.TruthJoinedPredictionFetcher.TruthJoinedRow;
import net.thebullpen.baseball.drift.algorithms.Calibration;
import net.thebullpen.baseball.registry.RegistryRepository;
import net.thebullpen.baseball.registry.dto.ModelVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily calibration batch (leaf 3c.4) — writes Brier + ECE per active model on the previous day's
 * predictions joined with observed truth (decision [76]: concept drift is the most important
 * signal). 2:30 AM ET — 30 min after PsiFeatureJob + 20 min after PsiPredictionJob.
 *
 * <p>Window applies a built-in 24h settle delay per leaf "Known edge cases" — outcomes for games
 * still in progress at run time would be missing; pulling {@code [now-48h, now-24h]} ensures the
 * join sees ~99% of settled truth.
 *
 * <p>Writes one BRIER row + one CALIBRATION_ERROR row per (model, "all") tuple. Per-segment
 * breakdowns are 3c.5's job.
 */
@Component
@Profile("worker")
public class CalibrationJob {

  private static final Logger log = LoggerFactory.getLogger(CalibrationJob.class);

  private static final String JOB_NAME = "calibration";
  private static final ZoneId ET = ZoneId.of("America/New_York");

  private final RegistryRepository registryRepo;
  private final TruthJoinedPredictionFetcher fetcher;
  private final DriftMetricsRepository driftRepo;
  private final JobLockRepository jobLocks;

  public CalibrationJob(
      RegistryRepository registryRepo,
      TruthJoinedPredictionFetcher fetcher,
      DriftMetricsRepository driftRepo,
      JobLockRepository jobLocks) {
    this.registryRepo = registryRepo;
    this.fetcher = fetcher;
    this.driftRepo = driftRepo;
    this.jobLocks = jobLocks;
  }

  @Scheduled(cron = "0 30 2 * * *", zone = "America/New_York")
  public void run() {
    LocalDate fireDate = LocalDate.now(ET);
    if (!jobLocks.tryAcquire(JOB_NAME, fireDate)) {
      log.info("{} already ran for {} on another instance; skipping", JOB_NAME, fireDate);
      return;
    }
    try {
      runOnce(Instant.now());
    } catch (RuntimeException e) {
      log.error("CalibrationJob: run failed", e);
    }
  }

  /** Visible-for-tests entry point. Returns the number of drift_metric rows written. */
  public int runOnce(Instant computedAt) {
    Instant windowEnd = computedAt.minus(24, ChronoUnit.HOURS);
    Instant windowStart = windowEnd.minus(24, ChronoUnit.HOURS);
    List<ModelVersion> serving = registryRepo.findActiveServingVersions();
    if (serving.isEmpty()) {
      log.info("CalibrationJob: no active serving versions (champion or shadow) registered");
      return 0;
    }
    List<DriftMetric> rows = new ArrayList<>();
    for (ModelVersion mv : serving) {
      rows.addAll(computeForChampion(mv, computedAt, windowStart, windowEnd));
    }
    if (rows.isEmpty()) {
      log.info("CalibrationJob: no rows to write (no truth-joined pairs in window)");
      return 0;
    }
    try {
      driftRepo.insertBatch(rows);
    } catch (SQLException e) {
      throw new RuntimeException("CalibrationJob: failed to insert drift_metrics batch", e);
    }
    log.info(
        "CalibrationJob: wrote {} calibration row(s) across {} serving version(s)",
        rows.size(),
        serving.size());
    return rows.size();
  }

  private List<DriftMetric> computeForChampion(
      ModelVersion champ, Instant computedAt, Instant windowStart, Instant windowEnd) {
    List<TruthJoinedRow> joined =
        fetcher.fetch(champ.modelName(), champ.id(), windowStart, windowEnd);
    if (joined.isEmpty()) {
      return List.of();
    }
    List<double[]> probs = new ArrayList<>(joined.size());
    int[] truth = new int[joined.size()];
    for (int i = 0; i < joined.size(); i++) {
      probs.add(joined.get(i).probs());
      truth[i] = joined.get(i).truthClass();
    }
    double brier = Calibration.brier(probs, truth);
    double ece = Calibration.ece(probs, truth);
    List<DriftMetric> out = new ArrayList<>(2);
    out.add(
        new DriftMetric(
            computedAt,
            champ.modelName(),
            champ.id(),
            MetricType.BRIER,
            "all",
            brier,
            joined.size(),
            windowStart,
            windowEnd));
    out.add(
        new DriftMetric(
            computedAt,
            champ.modelName(),
            champ.id(),
            MetricType.CALIBRATION_ERROR,
            "all",
            ece,
            joined.size(),
            windowStart,
            windowEnd));
    return out;
  }
}
