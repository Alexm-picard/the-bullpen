package net.thebullpen.baseball.drift.jobs;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.thebullpen.baseball.data.JobLockRepository;
import net.thebullpen.baseball.drift.DriftHealthMetrics;
import net.thebullpen.baseball.drift.DriftMetric;
import net.thebullpen.baseball.drift.DriftMetricsRepository;
import net.thebullpen.baseball.drift.FeatureDistributionFetcher;
import net.thebullpen.baseball.drift.MetricType;
import net.thebullpen.baseball.drift.TrainingDistributionLoader;
import net.thebullpen.baseball.drift.TrainingDistributionLoader.ReferenceDistributions;
import net.thebullpen.baseball.drift.algorithms.Psi;
import net.thebullpen.baseball.registry.RegistryRepository;
import net.thebullpen.baseball.registry.SnapshotStorage;
import net.thebullpen.baseball.registry.dto.ModelVersion;
import net.thebullpen.baseball.registry.dto.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily PSI-feature batch job (leaf 3c.2). Worker profile only. Runs at 2 AM ET (after games end).
 * For every active CHAMPION model: loads training-time reference distributions from {@code
 * metadata.json}, fetches observed 24h distributions from {@code prediction_log} via {@link
 * FeatureDistributionFetcher}, computes PSI (continuous) or chi² (categorical) per feature, writes
 * one {@link DriftMetric} row per (model, feature) into {@code drift_metrics}.
 *
 * <p>Skips features whose reference is empty (training pipeline hasn't emitted distributions yet)
 * and (model, feature) tuples whose observed sample is empty (no traffic / stub fetcher). Both are
 * logged at INFO so the operator can spot when the pipeline is or isn't producing data.
 *
 * <p>Heartbeat ping to Healthchecks.io is left for the operator's external monitor — the leaf body
 * lists it but the project's monitoring is Uptime Robot for endpoint health (decision [129]), not
 * Healthchecks.io. The job logs at INFO on success/failure so an operator can scan the worker logs.
 */
@Component
@Profile("worker")
public class PsiFeatureJob {

  private static final Logger log = LoggerFactory.getLogger(PsiFeatureJob.class);

  private static final String JOB_NAME = "psi_feature";
  private static final ZoneId ET = ZoneId.of("America/New_York");

  private final RegistryRepository registryRepo;
  private final TrainingDistributionLoader trainingLoader;
  private final FeatureDistributionFetcher fetcher;
  private final DriftMetricsRepository driftRepo;
  private final JobLockRepository jobLocks;
  private final DriftHealthMetrics driftHealth;

  public PsiFeatureJob(
      RegistryRepository registryRepo,
      TrainingDistributionLoader trainingLoader,
      FeatureDistributionFetcher fetcher,
      DriftMetricsRepository driftRepo,
      JobLockRepository jobLocks,
      DriftHealthMetrics driftHealth) {
    this.registryRepo = registryRepo;
    this.trainingLoader = trainingLoader;
    this.fetcher = fetcher;
    this.driftRepo = driftRepo;
    this.jobLocks = jobLocks;
    this.driftHealth = driftHealth;
  }

  /** Cron: 2 AM ET daily (post-baseball-window). */
  @Scheduled(cron = "0 0 2 * * *", zone = "America/New_York")
  public void run() {
    LocalDate fireDate = LocalDate.now(ET);
    if (!jobLocks.tryAcquire(JOB_NAME, fireDate)) {
      log.info("{} already ran for {} on another instance; skipping", JOB_NAME, fireDate);
      return;
    }
    try {
      runOnce(Instant.now());
    } catch (RuntimeException e) {
      log.error("PsiFeatureJob: run failed", e);
    }
  }

  /**
   * Visible-for-tests entry point. Iterates every model whose latest version is CHAMPION, computes
   * per-feature PSI / chi², writes the batch. Returns the number of rows written so the test can
   * assert.
   */
  public int runOnce(Instant computedAt) {
    Instant windowStart = computedAt.minus(24, ChronoUnit.HOURS);
    List<ModelVersion> serving = registryRepo.findActiveServingVersions();
    if (serving.isEmpty()) {
      log.info(
          "PsiFeatureJob: no active serving versions (champion or shadow) - nothing to compute");
      return 0;
    }
    List<DriftMetric> rows = new ArrayList<>();
    for (ModelVersion mv : serving) {
      rows.addAll(computeForChampion(mv, computedAt, windowStart, computedAt));
    }
    if (rows.isEmpty()) {
      log.info("PsiFeatureJob: no PSI rows to write (no training distributions or no traffic)");
      return 0;
    }
    try {
      driftRepo.insertBatch(rows);
    } catch (SQLException e) {
      throw new RuntimeException("PsiFeatureJob: failed to insert drift_metrics batch", e);
    }
    log.info(
        "PsiFeatureJob: wrote {} drift_metric row(s) across {} serving version(s)",
        rows.size(),
        serving.size());
    return rows.size();
  }

  private List<DriftMetric> computeForChampion(
      ModelVersion champ, Instant computedAt, Instant windowStart, Instant windowEnd) {
    List<DriftMetric> out = new ArrayList<>();
    if (SnapshotStorage.isS3Uri(champ.metadataPath())) {
      log.info(
          "PsiFeatureJob: skipping {} (id={}) — metadata is S3-archived; restore first",
          champ.naturalKey(),
          champ.id());
      return out;
    }
    ReferenceDistributions refs = trainingLoader.load(champ.id(), Path.of(champ.metadataPath()));
    if (refs.isEmpty()) {
      reportMissingBaseline(champ);
      return out;
    }
    // Continuous PSI per feature.
    for (Map.Entry<String, double[]> entry : refs.continuous().entrySet()) {
      String feature = entry.getKey();
      double[] reference = entry.getValue();
      List<Double> sample =
          fetcher.fetchContinuous(champ.modelName(), champ.id(), feature, windowStart, windowEnd);
      if (sample.isEmpty()) {
        continue;
      }
      double[] actual = toArr(sample);
      double psi = Psi.computeContinuous(reference, actual, Psi.DEFAULT_BINS);
      out.add(
          new DriftMetric(
              computedAt,
              champ.modelName(),
              champ.id(),
              MetricType.PSI_FEATURE,
              feature,
              psi,
              sample.size(),
              windowStart,
              windowEnd));
    }
    // Categorical chi² per feature.
    for (Map.Entry<String, Map<String, Integer>> entry : refs.categorical().entrySet()) {
      String feature = entry.getKey();
      Map<String, Integer> reference = entry.getValue();
      Map<String, Integer> actual =
          fetcher.fetchCategorical(champ.modelName(), champ.id(), feature, windowStart, windowEnd);
      if (actual.isEmpty()) {
        continue;
      }
      double chi2 = Psi.computeCategorical(reference, actual);
      long sampleSize = actual.values().stream().mapToLong(Integer::longValue).sum();
      out.add(
          new DriftMetric(
              computedAt,
              champ.modelName(),
              champ.id(),
              MetricType.PSI_FEATURE,
              feature,
              chi2,
              sampleSize,
              windowStart,
              windowEnd));
    }
    return out;
  }

  /**
   * A serving version has no feature-distribution baseline in its {@code metadata.json}. For a
   * CHAMPION this is PSI going dark on the production model (decision [175]'s silent-starve) - WARN
   * loudly, name the remediation, and bump the alertable counter. For a SHADOW challenger the miss
   * is benign (its PSI is nice-to-have) - INFO only, no counter, so the alert stays clean.
   */
  private void reportMissingBaseline(ModelVersion serving) {
    if (serving.stage() == Stage.CHAMPION) {
      log.warn(
          "PsiFeatureJob: CHAMPION {} (id={}) has NO feature-distribution baseline - PSI is dark"
              + " for this production model. Run"
              + " scripts/backfill_training_distributions.py --model {} against the champion bundle.",
          serving.naturalKey(),
          serving.id(),
          serving.modelName());
      driftHealth.markChampionMissingBaseline(serving.modelName(), "feature");
    } else {
      log.info(
          "PsiFeatureJob: {} {} (id={}) has no feature-distribution baseline; skipping PSI",
          serving.stage(),
          serving.naturalKey(),
          serving.id());
    }
  }

  private static double[] toArr(List<Double> xs) {
    double[] a = new double[xs.size()];
    for (int i = 0; i < xs.size(); i++) {
      a[i] = xs.get(i);
    }
    return a;
  }
}
