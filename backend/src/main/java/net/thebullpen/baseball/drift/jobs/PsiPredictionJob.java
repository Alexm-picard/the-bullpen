package net.thebullpen.baseball.drift.jobs;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.thebullpen.baseball.drift.DriftMetric;
import net.thebullpen.baseball.drift.DriftMetricsRepository;
import net.thebullpen.baseball.drift.MetricType;
import net.thebullpen.baseball.drift.PredictionDistributionFetcher;
import net.thebullpen.baseball.drift.TrainingDistributionLoader;
import net.thebullpen.baseball.drift.algorithms.Psi;
import net.thebullpen.baseball.registry.RegistryRepository;
import net.thebullpen.baseball.registry.SnapshotStorage;
import net.thebullpen.baseball.registry.dto.ModelVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily PSI-prediction batch job (leaf 3c.3). Same plumbing as {@link PsiFeatureJob} but operates
 * on per-class predicted-probability distributions (decoded out of the {@code prediction} JSON
 * column of {@code prediction_log}) rather than per-feature input distributions.
 *
 * <p>Runs at 2:10 AM ET — 10 minutes after PsiFeatureJob to avoid contending on the registry and
 * ClickHouse connections. Writes one drift_metric row per {@code (model, class)} with {@code
 * metric_type = PSI_PREDICTION} and {@code feature_or_segment = <class_name>}.
 */
@Component
@Profile("worker")
public class PsiPredictionJob {

  private static final Logger log = LoggerFactory.getLogger(PsiPredictionJob.class);

  private final RegistryRepository registryRepo;
  private final TrainingDistributionLoader trainingLoader;
  private final PredictionDistributionFetcher fetcher;
  private final DriftMetricsRepository driftRepo;

  public PsiPredictionJob(
      RegistryRepository registryRepo,
      TrainingDistributionLoader trainingLoader,
      PredictionDistributionFetcher fetcher,
      DriftMetricsRepository driftRepo) {
    this.registryRepo = registryRepo;
    this.trainingLoader = trainingLoader;
    this.fetcher = fetcher;
    this.driftRepo = driftRepo;
  }

  @Scheduled(cron = "0 10 2 * * *", zone = "America/New_York")
  public void run() {
    try {
      runOnce(Instant.now());
    } catch (RuntimeException e) {
      log.error("PsiPredictionJob: run failed", e);
    }
  }

  /** Visible-for-tests entry point. Returns the number of drift_metric rows written. */
  public int runOnce(Instant computedAt) {
    Instant windowStart = computedAt.minus(24, ChronoUnit.HOURS);
    List<ModelVersion> serving = registryRepo.findActiveServingVersions();
    if (serving.isEmpty()) {
      log.info("PsiPredictionJob: no active serving versions (champion or shadow) registered");
      return 0;
    }
    List<DriftMetric> rows = new ArrayList<>();
    for (ModelVersion champ : serving) {
      rows.addAll(computeForChampion(champ, computedAt, windowStart, computedAt));
    }
    if (rows.isEmpty()) {
      log.info("PsiPredictionJob: no PSI-prediction rows to write");
      return 0;
    }
    try {
      driftRepo.insertBatch(rows);
    } catch (SQLException e) {
      throw new RuntimeException("PsiPredictionJob: failed to insert drift_metrics batch", e);
    }
    log.info(
        "PsiPredictionJob: wrote {} PSI-prediction row(s) across {} serving version(s)",
        rows.size(),
        serving.size());
    return rows.size();
  }

  private List<DriftMetric> computeForChampion(
      ModelVersion champ, Instant computedAt, Instant windowStart, Instant windowEnd) {
    List<DriftMetric> out = new ArrayList<>();
    if (SnapshotStorage.isS3Uri(champ.metadataPath())) {
      log.info(
          "PsiPredictionJob: skipping {} (id={}) — metadata is S3-archived",
          champ.naturalKey(),
          champ.id());
      return out;
    }
    Map<String, double[]> refs =
        trainingLoader.loadPerClassPredictionReference(champ.id(), Path.of(champ.metadataPath()));
    if (refs.isEmpty()) {
      return out;
    }
    Map<String, List<Double>> observed =
        fetcher.fetchPerClassProbabilities(champ.modelName(), champ.id(), windowStart, windowEnd);
    if (observed.isEmpty()) {
      return out;
    }
    for (Map.Entry<String, double[]> entry : refs.entrySet()) {
      String className = entry.getKey();
      double[] reference = entry.getValue();
      List<Double> sample = observed.get(className);
      if (sample == null || sample.isEmpty()) {
        continue;
      }
      double[] actual = new double[sample.size()];
      for (int i = 0; i < sample.size(); i++) {
        actual[i] = sample.get(i);
      }
      double psi = Psi.computeContinuous(reference, actual, Psi.DEFAULT_BINS);
      out.add(
          new DriftMetric(
              computedAt,
              champ.modelName(),
              champ.id(),
              MetricType.PSI_PREDICTION,
              className,
              psi,
              sample.size(),
              windowStart,
              windowEnd));
    }
    return out;
  }
}
