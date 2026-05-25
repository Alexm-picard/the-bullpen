package net.thebullpen.baseball.drift.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import net.thebullpen.baseball.drift.DriftMetric;
import net.thebullpen.baseball.drift.DriftMetricsRepository;
import net.thebullpen.baseball.drift.MetricType;
import net.thebullpen.baseball.drift.TruthJoinedPredictionFetcher;
import net.thebullpen.baseball.drift.TruthJoinedPredictionFetcher.TruthJoinedRow;
import net.thebullpen.baseball.registry.RegistryRepository;
import net.thebullpen.baseball.registry.dto.ModelVersion;
import net.thebullpen.baseball.registry.dto.Stage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CalibrationJobTest {

  private RegistryRepository registryRepo;
  private TruthJoinedPredictionFetcher fetcher;
  private DriftMetricsRepository driftRepo;
  private CalibrationJob job;

  @BeforeEach
  void setUp() {
    registryRepo = mock(RegistryRepository.class);
    fetcher = mock(TruthJoinedPredictionFetcher.class);
    driftRepo = mock(DriftMetricsRepository.class);
    job = new CalibrationJob(registryRepo, fetcher, driftRepo);
  }

  @Test
  void no_champions_writes_zero_rows() throws Exception {
    when(registryRepo.findAllNameVersionPairs()).thenReturn(List.of());
    assertThat(job.runOnce(Instant.now())).isEqualTo(0);
    verify(driftRepo, never()).insertBatch(any());
  }

  @Test
  void empty_truth_join_writes_zero_rows() throws Exception {
    ModelVersion champ = champion("model_a", 1L);
    when(registryRepo.findAllNameVersionPairs())
        .thenReturn(List.<String[]>of(new String[] {"model_a", "v1"}));
    when(registryRepo.findByName("model_a")).thenReturn(List.of(champ));
    when(fetcher.fetch(eq("model_a"), eq(1L), any(Instant.class), any(Instant.class)))
        .thenReturn(List.of());

    assertThat(job.runOnce(Instant.now())).isEqualTo(0);
    verify(driftRepo, never()).insertBatch(any());
  }

  @Test
  void single_champion_with_perfect_predictions_writes_two_rows_brier_zero_ece_zero()
      throws Exception {
    ModelVersion champ = champion("model_a", 1L);
    when(registryRepo.findAllNameVersionPairs())
        .thenReturn(List.<String[]>of(new String[] {"model_a", "v1"}));
    when(registryRepo.findByName("model_a")).thenReturn(List.of(champ));
    List<TruthJoinedRow> joined = new ArrayList<>();
    for (int i = 0; i < 100; i++) {
      joined.add(new TruthJoinedRow(new double[] {1.0, 0.0}, 0));
    }
    when(fetcher.fetch(eq("model_a"), eq(1L), any(Instant.class), any(Instant.class)))
        .thenReturn(joined);

    int rows = job.runOnce(Instant.now());
    assertThat(rows).isEqualTo(2);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<DriftMetric>> cap = ArgumentCaptor.forClass(List.class);
    verify(driftRepo).insertBatch(cap.capture());
    List<DriftMetric> batch = cap.getValue();
    assertThat(batch)
        .extracting(DriftMetric::metricType)
        .containsExactlyInAnyOrder(MetricType.BRIER, MetricType.CALIBRATION_ERROR);
    for (DriftMetric m : batch) {
      assertThat(m.featureOrSegment()).isEqualTo("all");
      assertThat(m.sampleSize()).isEqualTo(100L);
      assertThat(m.metricValue()).isCloseTo(0.0, org.assertj.core.data.Offset.offset(1e-10));
    }
  }

  @Test
  void window_is_24h_lag_behind_computed_at() throws Exception {
    ModelVersion champ = champion("model_a", 1L);
    when(registryRepo.findAllNameVersionPairs())
        .thenReturn(List.<String[]>of(new String[] {"model_a", "v1"}));
    when(registryRepo.findByName("model_a")).thenReturn(List.of(champ));
    ArgumentCaptor<Instant> startCap = ArgumentCaptor.forClass(Instant.class);
    ArgumentCaptor<Instant> endCap = ArgumentCaptor.forClass(Instant.class);
    when(fetcher.fetch(eq("model_a"), eq(1L), startCap.capture(), endCap.capture()))
        .thenReturn(List.of());

    Instant computedAt = Instant.parse("2026-05-25T02:30:00Z");
    job.runOnce(computedAt);

    // windowEnd = computedAt - 24h; windowStart = windowEnd - 24h.
    assertThat(endCap.getValue())
        .isEqualTo(computedAt.minus(24, java.time.temporal.ChronoUnit.HOURS));
    assertThat(startCap.getValue())
        .isEqualTo(computedAt.minus(48, java.time.temporal.ChronoUnit.HOURS));
  }

  @Test
  void multiple_champions_each_get_two_rows() throws Exception {
    ModelVersion champA = champion("model_a", 1L);
    ModelVersion champB = champion("model_b", 2L);
    when(registryRepo.findAllNameVersionPairs())
        .thenReturn(
            java.util.List.<String[]>of(
                new String[] {"model_a", "v1"}, new String[] {"model_b", "v1"}));
    when(registryRepo.findByName("model_a")).thenReturn(List.of(champA));
    when(registryRepo.findByName("model_b")).thenReturn(List.of(champB));
    List<TruthJoinedRow> joined = java.util.List.of(new TruthJoinedRow(new double[] {0.7, 0.3}, 0));
    when(fetcher.fetch(any(String.class), anyLong(), any(Instant.class), any(Instant.class)))
        .thenReturn(joined);

    int rows = job.runOnce(Instant.now());
    assertThat(rows).isEqualTo(4); // 2 metrics × 2 champions
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
}
