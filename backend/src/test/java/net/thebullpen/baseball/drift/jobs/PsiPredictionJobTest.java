package net.thebullpen.baseball.drift.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.thebullpen.baseball.drift.DriftMetric;
import net.thebullpen.baseball.drift.DriftMetricsRepository;
import net.thebullpen.baseball.drift.MetricType;
import net.thebullpen.baseball.drift.PredictionDistributionFetcher;
import net.thebullpen.baseball.drift.TrainingDistributionLoader;
import net.thebullpen.baseball.registry.RegistryRepository;
import net.thebullpen.baseball.registry.dto.ModelVersion;
import net.thebullpen.baseball.registry.dto.Stage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PsiPredictionJobTest {

  private RegistryRepository registryRepo;
  private TrainingDistributionLoader trainingLoader;
  private PredictionDistributionFetcher fetcher;
  private DriftMetricsRepository driftRepo;
  private PsiPredictionJob job;

  @BeforeEach
  void setUp() {
    registryRepo = mock(RegistryRepository.class);
    trainingLoader = mock(TrainingDistributionLoader.class);
    fetcher = mock(PredictionDistributionFetcher.class);
    driftRepo = mock(DriftMetricsRepository.class);
    job = new PsiPredictionJob(registryRepo, trainingLoader, fetcher, driftRepo);
  }

  @Test
  void no_serving_versions_writes_zero_rows() throws Exception {
    when(registryRepo.findActiveServingVersions()).thenReturn(List.of());
    assertThat(job.runOnce(Instant.now())).isEqualTo(0);
    verify(driftRepo, never()).insertBatch(any());
  }

  @Test
  void a_shadow_version_is_observed_too() throws Exception {
    // C3: the drift jobs now watch SHADOW versions, not just the champion, so a shadow challenger
    // that nothing else observes still gets PSI computed. findActiveServingVersions() returns the
    // CHAMPION + SHADOW set; the job processes whatever it returns.
    ModelVersion shadow = serving("pitch_outcome_pre", 7L, "/tmp/meta.json", Stage.SHADOW);
    when(registryRepo.findActiveServingVersions()).thenReturn(List.of(shadow));
    when(trainingLoader.loadPerClassPredictionReference(eq(7L), any(Path.class)))
        .thenReturn(Map.of("ball", new double[] {0.3, 0.4, 0.5}));
    when(fetcher.fetchPerClassProbabilities(
            eq("pitch_outcome_pre"), eq(7L), any(Instant.class), any(Instant.class)))
        .thenReturn(Map.of("ball", List.of(0.31, 0.39, 0.52)));

    assertThat(job.runOnce(Instant.now())).isEqualTo(1);
  }

  @Test
  void empty_reference_writes_zero_rows() throws Exception {
    ModelVersion champ = champion("model_a", 1L, "/tmp/meta.json");
    when(registryRepo.findActiveServingVersions()).thenReturn(List.of(champ));
    when(trainingLoader.loadPerClassPredictionReference(eq(1L), any(Path.class)))
        .thenReturn(Map.of());

    assertThat(job.runOnce(Instant.now())).isEqualTo(0);
  }

  @Test
  void empty_observed_distributions_writes_zero_rows() throws Exception {
    ModelVersion champ = champion("model_a", 1L, "/tmp/meta.json");
    when(registryRepo.findActiveServingVersions()).thenReturn(List.of(champ));
    when(trainingLoader.loadPerClassPredictionReference(eq(1L), any(Path.class)))
        .thenReturn(Map.of("ball", new double[] {0.3, 0.4, 0.5}));
    when(fetcher.fetchPerClassProbabilities(
            eq("model_a"), eq(1L), any(Instant.class), any(Instant.class)))
        .thenReturn(Map.of());

    assertThat(job.runOnce(Instant.now())).isEqualTo(0);
  }

  @Test
  void per_class_psi_rows_are_written_per_class() throws Exception {
    ModelVersion champ = champion("pitch_outcome_pre", 1L, "/tmp/meta.json");
    when(registryRepo.findActiveServingVersions()).thenReturn(List.of(champ));

    Random r = new Random(42);
    Map<String, double[]> refs = new HashMap<>();
    Map<String, List<Double>> observed = new HashMap<>();
    for (String cls : List.of("ball", "called_strike", "swinging_strike", "foul", "in_play")) {
      double[] ref = new double[500];
      List<Double> obs = new java.util.ArrayList<>();
      for (int i = 0; i < ref.length; i++) {
        ref[i] = r.nextDouble();
        obs.add(r.nextDouble());
      }
      refs.put(cls, ref);
      observed.put(cls, obs);
    }
    when(trainingLoader.loadPerClassPredictionReference(eq(1L), any(Path.class))).thenReturn(refs);
    when(fetcher.fetchPerClassProbabilities(
            eq("pitch_outcome_pre"), eq(1L), any(Instant.class), any(Instant.class)))
        .thenReturn(observed);

    int rows = job.runOnce(Instant.now());
    assertThat(rows).isEqualTo(5);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<DriftMetric>> cap = ArgumentCaptor.forClass(List.class);
    verify(driftRepo).insertBatch(cap.capture());
    List<DriftMetric> batch = cap.getValue();
    assertThat(batch).extracting(DriftMetric::metricType).containsOnly(MetricType.PSI_PREDICTION);
    assertThat(batch)
        .extracting(DriftMetric::featureOrSegment)
        .containsExactlyInAnyOrder("ball", "called_strike", "swinging_strike", "foul", "in_play");
  }

  @Test
  void class_in_reference_but_not_in_observed_is_skipped() throws Exception {
    ModelVersion champ = champion("model_a", 1L, "/tmp/meta.json");
    when(registryRepo.findActiveServingVersions()).thenReturn(List.of(champ));
    when(trainingLoader.loadPerClassPredictionReference(eq(1L), any(Path.class)))
        .thenReturn(Map.of("class_a", new double[] {0.1, 0.2}, "class_b", new double[] {0.3, 0.4}));
    when(fetcher.fetchPerClassProbabilities(
            eq("model_a"), eq(1L), any(Instant.class), any(Instant.class)))
        .thenReturn(Map.of("class_a", List.of(0.15, 0.25))); // class_b absent

    int rows = job.runOnce(Instant.now());
    assertThat(rows).isEqualTo(1); // class_a only
  }

  @Test
  void s3_archived_metadata_is_skipped() throws Exception {
    ModelVersion champ =
        champion("model_a", 1L, "s3://bucket/models-archive/model_a/v1/metadata.json");
    when(registryRepo.findActiveServingVersions()).thenReturn(List.of(champ));

    int rows = job.runOnce(Instant.now());
    assertThat(rows).isEqualTo(0);
    verify(trainingLoader, never()).loadPerClassPredictionReference(anyLong(), any(Path.class));
  }

  private static ModelVersion champion(String name, long id, String metadataPath) {
    return serving(name, id, metadataPath, Stage.CHAMPION);
  }

  private static ModelVersion serving(String name, long id, String metadataPath, Stage stage) {
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
        stage,
        "test",
        null,
        Instant.now(),
        Instant.now());
  }
}
