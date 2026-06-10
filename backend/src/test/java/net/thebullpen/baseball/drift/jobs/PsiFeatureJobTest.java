package net.thebullpen.baseball.drift.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import net.thebullpen.baseball.drift.DriftMetric;
import net.thebullpen.baseball.drift.DriftMetricsRepository;
import net.thebullpen.baseball.drift.FeatureDistributionFetcher;
import net.thebullpen.baseball.drift.MetricType;
import net.thebullpen.baseball.drift.TrainingDistributionLoader;
import net.thebullpen.baseball.drift.TrainingDistributionLoader.ReferenceDistributions;
import net.thebullpen.baseball.registry.RegistryRepository;
import net.thebullpen.baseball.registry.dto.ModelVersion;
import net.thebullpen.baseball.registry.dto.Stage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for {@link PsiFeatureJob}. Mocks the registry repo, loader, fetcher, and drift repo so
 * the job's orchestration + skip logic gets exercised without a Spring context.
 */
class PsiFeatureJobTest {

  private RegistryRepository registryRepo;
  private TrainingDistributionLoader trainingLoader;
  private FeatureDistributionFetcher fetcher;
  private DriftMetricsRepository driftRepo;
  private PsiFeatureJob job;

  @BeforeEach
  void setUp() {
    registryRepo = mock(RegistryRepository.class);
    trainingLoader = mock(TrainingDistributionLoader.class);
    fetcher = mock(FeatureDistributionFetcher.class);
    driftRepo = mock(DriftMetricsRepository.class);
    job = new PsiFeatureJob(registryRepo, trainingLoader, fetcher, driftRepo);
  }

  @Test
  void no_active_champions_results_in_zero_rows_and_no_writes() throws Exception {
    when(registryRepo.findActiveServingVersions()).thenReturn(List.of());
    int rows = job.runOnce(Instant.now());
    assertThat(rows).isEqualTo(0);
    verify(driftRepo, never()).insertBatch(any());
  }

  @Test
  void champion_with_no_training_distributions_writes_no_rows() throws Exception {
    ModelVersion champ = champion("model_a", 1L, "/tmp/no_meta.json");
    when(registryRepo.findActiveServingVersions()).thenReturn(List.of(champ));
    when(trainingLoader.load(eq(1L), any(Path.class))).thenReturn(ReferenceDistributions.empty());

    int rows = job.runOnce(Instant.now());
    assertThat(rows).isEqualTo(0);
    verify(driftRepo, never()).insertBatch(any());
  }

  @Test
  void continuous_feature_with_no_observed_sample_is_skipped() throws Exception {
    ModelVersion champ = champion("model_a", 1L, "/tmp/meta.json");
    when(registryRepo.findActiveServingVersions()).thenReturn(List.of(champ));
    ReferenceDistributions refs =
        new ReferenceDistributions(
            Map.of("launch_speed", new double[] {88.0, 90.0, 92.0, 94.0, 96.0}), Map.of());
    when(trainingLoader.load(eq(1L), any(Path.class))).thenReturn(refs);
    when(fetcher.fetchContinuous(
            anyString(), anyLong(), anyString(), any(Instant.class), any(Instant.class)))
        .thenReturn(List.of());

    int rows = job.runOnce(Instant.now());
    assertThat(rows).isEqualTo(0);
  }

  @Test
  void continuous_feature_with_observed_sample_writes_psi_row() throws Exception {
    ModelVersion champ = champion("model_a", 1L, "/tmp/meta.json");
    when(registryRepo.findActiveServingVersions()).thenReturn(List.of(champ));
    double[] ref = new double[1000];
    java.util.Random r = new java.util.Random(42);
    for (int i = 0; i < ref.length; i++) {
      ref[i] = r.nextGaussian();
    }
    when(trainingLoader.load(eq(1L), any(Path.class)))
        .thenReturn(new ReferenceDistributions(Map.of("f1", ref), Map.of()));
    List<Double> observed = new java.util.ArrayList<>();
    for (int i = 0; i < 1000; i++) {
      observed.add(r.nextGaussian() + 0.5); // mild shift
    }
    when(fetcher.fetchContinuous(
            eq("model_a"), eq(1L), eq("f1"), any(Instant.class), any(Instant.class)))
        .thenReturn(observed);

    int rows = job.runOnce(Instant.now());
    assertThat(rows).isEqualTo(1);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<DriftMetric>> cap = ArgumentCaptor.forClass(List.class);
    verify(driftRepo).insertBatch(cap.capture());
    List<DriftMetric> batch = cap.getValue();
    assertThat(batch).hasSize(1);
    DriftMetric m = batch.get(0);
    assertThat(m.modelName()).isEqualTo("model_a");
    assertThat(m.modelVersionId()).isEqualTo(1L);
    assertThat(m.metricType()).isEqualTo(MetricType.PSI_FEATURE);
    assertThat(m.featureOrSegment()).isEqualTo("f1");
    assertThat(m.sampleSize()).isEqualTo(1000L);
    assertThat(m.metricValue()).isGreaterThan(0.0);
  }

  @Test
  void categorical_feature_writes_chi_squared_row() throws Exception {
    ModelVersion champ = champion("model_a", 1L, "/tmp/meta.json");
    when(registryRepo.findActiveServingVersions()).thenReturn(List.of(champ));
    Map<String, Integer> ref = Map.of("NYY", 1000, "BOS", 800);
    when(trainingLoader.load(eq(1L), any(Path.class)))
        .thenReturn(new ReferenceDistributions(Map.of(), Map.of("park_id", ref)));
    Map<String, Integer> observed = Map.of("NYY", 1000, "BOS", 800, "NEW", 50);
    when(fetcher.fetchCategorical(
            eq("model_a"), eq(1L), eq("park_id"), any(Instant.class), any(Instant.class)))
        .thenReturn(observed);

    int rows = job.runOnce(Instant.now());
    assertThat(rows).isEqualTo(1);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<DriftMetric>> cap = ArgumentCaptor.forClass(List.class);
    verify(driftRepo).insertBatch(cap.capture());
    DriftMetric m = cap.getValue().get(0);
    assertThat(m.sampleSize()).isEqualTo(1850L); // sum of observed counts
    assertThat(m.metricValue()).isGreaterThan(0.0);
  }

  @Test
  void s3_archived_metadata_is_skipped_with_log() throws Exception {
    ModelVersion champ =
        champion("model_a", 1L, "s3://bucket/models-archive/model_a/v1/metadata.json");
    when(registryRepo.findActiveServingVersions()).thenReturn(List.of(champ));

    int rows = job.runOnce(Instant.now());
    assertThat(rows).isEqualTo(0);
    verify(trainingLoader, never()).load(anyLong(), any(Path.class));
  }

  private static ModelVersion champion(String name, long id, String metadataPath) {
    return new ModelVersion(
        id,
        name,
        "v1",
        "/tmp/" + name + "/v1/model.onnx",
        metadataPath,
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
}
