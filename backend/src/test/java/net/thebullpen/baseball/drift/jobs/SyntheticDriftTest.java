package net.thebullpen.baseball.drift.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.thebullpen.baseball.drift.DriftMetric;
import net.thebullpen.baseball.drift.DriftMetricsRepository;
import net.thebullpen.baseball.drift.FeatureDistributionFetcher;
import net.thebullpen.baseball.drift.MetricType;
import net.thebullpen.baseball.drift.PredictionDistributionFetcher;
import net.thebullpen.baseball.drift.TrainingDistributionLoader;
import net.thebullpen.baseball.drift.TrainingDistributionLoader.ReferenceDistributions;
import net.thebullpen.baseball.drift.TruthJoinedPredictionFetcher;
import net.thebullpen.baseball.drift.TruthJoinedPredictionFetcher.TruthJoinedRow;
import net.thebullpen.baseball.registry.RegistryRepository;
import net.thebullpen.baseball.registry.dto.ModelVersion;
import net.thebullpen.baseball.registry.dto.Stage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Synthetic-drift end-to-end tests (leaf 3c.6 / decision [64]). Wires each detector through its
 * fetcher seam with mocked synthetic data and asserts that the WRITTEN metric value crosses the
 * industry rule-of-thumb threshold the operator would alert on:
 *
 * <ul>
 *   <li>1σ feature shift → PSI &gt; 0.25 (significant)
 *   <li>5pp class-proportion shift → PSI &gt; 0.1 (moderate)
 *   <li>Systematic miscalibration → ECE &gt; 0.1
 *   <li>Negative case: zero injection → all metrics &lt; 0.05 (no-drift floor)
 * </ul>
 *
 * <p>Uses the same mock-fetcher pattern the per-job tests use, so the assertion target is the
 * MetricValue field of the DriftMetric the job writes — proving the full pipeline from fetched-data
 * → metric math → drift_metric row is sound.
 */
class SyntheticDriftTest {

  // --- feature PSI ------------------------------------------------------

  @Test
  void one_sigma_feature_shift_yields_psi_above_significant_threshold() throws Exception {
    RegistryRepository registryRepo = mock(RegistryRepository.class);
    TrainingDistributionLoader loader = mock(TrainingDistributionLoader.class);
    FeatureDistributionFetcher fetcher = mock(FeatureDistributionFetcher.class);
    DriftMetricsRepository driftRepo = mock(DriftMetricsRepository.class);

    ModelVersion champ = champion("model_a", 1L);
    when(registryRepo.findAllNameVersionPairs())
        .thenReturn(List.<String[]>of(new String[] {"model_a", "v1"}));
    when(registryRepo.findByName("model_a")).thenReturn(List.of(champ));

    Random r = new Random(42);
    int n = 10_000;
    double[] reference = new double[n];
    List<Double> observed = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      reference[i] = r.nextGaussian();
      observed.add(r.nextGaussian() + 1.0); // 1σ mean shift
    }
    when(loader.load(eq(1L), any(Path.class)))
        .thenReturn(new ReferenceDistributions(Map.of("launch_speed", reference), Map.of()));
    when(fetcher.fetchContinuous(
            eq("model_a"), eq(1L), eq("launch_speed"), any(Instant.class), any(Instant.class)))
        .thenReturn(observed);

    PsiFeatureJob job = new PsiFeatureJob(registryRepo, loader, fetcher, driftRepo);
    job.runOnce(Instant.now());

    DriftMetric written = captureSingle(driftRepo);
    assertThat(written.metricType()).isEqualTo(MetricType.PSI_FEATURE);
    assertThat(written.metricValue())
        .as("1σ feature shift should produce PSI > 0.25 (significant threshold)")
        .isGreaterThan(0.25);
  }

  @Test
  void no_injection_on_feature_stays_below_no_drift_floor() throws Exception {
    RegistryRepository registryRepo = mock(RegistryRepository.class);
    TrainingDistributionLoader loader = mock(TrainingDistributionLoader.class);
    FeatureDistributionFetcher fetcher = mock(FeatureDistributionFetcher.class);
    DriftMetricsRepository driftRepo = mock(DriftMetricsRepository.class);

    ModelVersion champ = champion("model_a", 1L);
    when(registryRepo.findAllNameVersionPairs())
        .thenReturn(List.<String[]>of(new String[] {"model_a", "v1"}));
    when(registryRepo.findByName("model_a")).thenReturn(List.of(champ));

    Random r = new Random(42);
    int n = 10_000;
    double[] reference = new double[n];
    List<Double> observed = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      reference[i] = r.nextGaussian();
      observed.add(r.nextGaussian()); // no shift
    }
    when(loader.load(eq(1L), any(Path.class)))
        .thenReturn(new ReferenceDistributions(Map.of("launch_speed", reference), Map.of()));
    when(fetcher.fetchContinuous(
            eq("model_a"), eq(1L), eq("launch_speed"), any(Instant.class), any(Instant.class)))
        .thenReturn(observed);

    PsiFeatureJob job = new PsiFeatureJob(registryRepo, loader, fetcher, driftRepo);
    job.runOnce(Instant.now());

    DriftMetric written = captureSingle(driftRepo);
    assertThat(written.metricValue())
        .as("no injection should stay below the 0.05 no-drift floor (false-positive guard)")
        .isLessThan(0.05);
  }

  // --- prediction PSI ---------------------------------------------------

  @Test
  void five_pp_class_proportion_shift_yields_psi_above_moderate_threshold() throws Exception {
    RegistryRepository registryRepo = mock(RegistryRepository.class);
    TrainingDistributionLoader loader = mock(TrainingDistributionLoader.class);
    PredictionDistributionFetcher fetcher = mock(PredictionDistributionFetcher.class);
    DriftMetricsRepository driftRepo = mock(DriftMetricsRepository.class);

    ModelVersion champ = champion("pitch_outcome_pre", 1L);
    when(registryRepo.findAllNameVersionPairs())
        .thenReturn(List.<String[]>of(new String[] {"pitch_outcome_pre", "v1"}));
    when(registryRepo.findByName("pitch_outcome_pre")).thenReturn(List.of(champ));

    Random r = new Random(7);
    int n = 5000;
    double[] reference = new double[n];
    List<Double> observed = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      // Reference: probability of "in_play" class ~ uniform(0, 0.3).
      reference[i] = r.nextDouble() * 0.3;
      // Observed: shifted up by 0.05 (5pp) — confidences in "in_play" higher across the board.
      observed.add(Math.min(1.0, r.nextDouble() * 0.3 + 0.05));
    }
    when(loader.loadPerClassPredictionReference(eq(1L), any(Path.class)))
        .thenReturn(Map.of("in_play", reference));
    when(fetcher.fetchPerClassProbabilities(
            eq("pitch_outcome_pre"), eq(1L), any(Instant.class), any(Instant.class)))
        .thenReturn(Map.of("in_play", observed));

    PsiPredictionJob job = new PsiPredictionJob(registryRepo, loader, fetcher, driftRepo);
    job.runOnce(Instant.now());

    DriftMetric written = captureSingle(driftRepo);
    assertThat(written.metricType()).isEqualTo(MetricType.PSI_PREDICTION);
    assertThat(written.featureOrSegment()).isEqualTo("in_play");
    assertThat(written.metricValue())
        .as("5pp class shift should produce PSI > 0.1 (moderate threshold)")
        .isGreaterThan(0.1);
  }

  // --- calibration ECE --------------------------------------------------

  @Test
  void systematic_overconfidence_yields_ece_above_zero_one() throws Exception {
    RegistryRepository registryRepo = mock(RegistryRepository.class);
    TruthJoinedPredictionFetcher fetcher = mock(TruthJoinedPredictionFetcher.class);
    DriftMetricsRepository driftRepo = mock(DriftMetricsRepository.class);

    ModelVersion champ = champion("model_a", 1L);
    when(registryRepo.findAllNameVersionPairs())
        .thenReturn(List.<String[]>of(new String[] {"model_a", "v1"}));
    when(registryRepo.findByName("model_a")).thenReturn(List.of(champ));

    // 1000 predictions all at 0.9 confidence for class 0, but only ~50% actually class 0.
    // ECE = |0.9 - 0.5| = 0.4 ≫ 0.1.
    List<TruthJoinedRow> joined = new ArrayList<>();
    for (int i = 0; i < 1000; i++) {
      joined.add(new TruthJoinedRow(new double[] {0.9, 0.1}, i % 2));
    }
    when(fetcher.fetch(eq("model_a"), eq(1L), any(Instant.class), any(Instant.class)))
        .thenReturn(joined);

    CalibrationJob job = new CalibrationJob(registryRepo, fetcher, driftRepo);
    job.runOnce(Instant.now());

    List<DriftMetric> written = captureAll(driftRepo);
    DriftMetric ece =
        written.stream()
            .filter(m -> m.metricType() == MetricType.CALIBRATION_ERROR)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no ECE row written"));
    assertThat(ece.metricValue())
        .as("0.9 confidence + 50%% accuracy should produce ECE ≈ 0.4 (>> 0.1 alert threshold)")
        .isGreaterThan(0.1);
  }

  @Test
  void perfect_predictions_keep_ece_at_zero_no_false_positive() throws Exception {
    RegistryRepository registryRepo = mock(RegistryRepository.class);
    TruthJoinedPredictionFetcher fetcher = mock(TruthJoinedPredictionFetcher.class);
    DriftMetricsRepository driftRepo = mock(DriftMetricsRepository.class);

    ModelVersion champ = champion("model_a", 1L);
    when(registryRepo.findAllNameVersionPairs())
        .thenReturn(List.<String[]>of(new String[] {"model_a", "v1"}));
    when(registryRepo.findByName("model_a")).thenReturn(List.of(champ));

    List<TruthJoinedRow> joined = new ArrayList<>();
    for (int i = 0; i < 1000; i++) {
      // Perfect prediction (confidence 1.0 on correct class). ECE = 0.
      int t = i % 2;
      double[] p = t == 0 ? new double[] {1.0, 0.0} : new double[] {0.0, 1.0};
      joined.add(new TruthJoinedRow(p, t));
    }
    when(fetcher.fetch(eq("model_a"), eq(1L), any(Instant.class), any(Instant.class)))
        .thenReturn(joined);

    CalibrationJob job = new CalibrationJob(registryRepo, fetcher, driftRepo);
    job.runOnce(Instant.now());

    List<DriftMetric> written = captureAll(driftRepo);
    for (DriftMetric m : written) {
      assertThat(m.metricValue())
          .as("perfect predictions: %s should stay at no-drift floor", m.metricType())
          .isLessThan(0.05);
    }
  }

  // --- helpers ----------------------------------------------------------

  /** Capture the single DriftMetric written; convenience for jobs that write exactly one row. */
  private static DriftMetric captureSingle(DriftMetricsRepository repo) throws Exception {
    List<DriftMetric> batch = captureAll(repo);
    assertThat(batch).hasSize(1);
    return batch.get(0);
  }

  /** Capture the full batch written. */
  @SuppressWarnings("unchecked")
  private static List<DriftMetric> captureAll(DriftMetricsRepository repo) throws Exception {
    ArgumentCaptor<List<DriftMetric>> cap = ArgumentCaptor.forClass(List.class);
    verify(repo).insertBatch(cap.capture());
    return cap.getValue();
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

  // Quiet "unused import" warnings the formatter sometimes complains about.
  @SuppressWarnings("unused")
  private static HashMap<String, double[]> unused() {
    return new HashMap<>();
  }
}
