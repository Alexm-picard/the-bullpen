package net.thebullpen.baseball.drift.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.thebullpen.baseball.data.JobLockRepository;
import net.thebullpen.baseball.drift.DriftMetric;
import net.thebullpen.baseball.drift.DriftMetricsRepository;
import net.thebullpen.baseball.drift.MetricType;
import net.thebullpen.baseball.drift.SegmentedTruthJoinedPredictionFetcher;
import net.thebullpen.baseball.drift.TruthJoinedPredictionFetcher.TruthJoinedRow;
import net.thebullpen.baseball.registry.RegistryRepository;
import net.thebullpen.baseball.registry.dto.ModelVersion;
import net.thebullpen.baseball.registry.dto.Stage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class WeeklySegmentJobTest {

  private RegistryRepository registryRepo;
  private SegmentedTruthJoinedPredictionFetcher fetcher;
  private DriftMetricsRepository driftRepo;
  private WeeklySegmentJob job;

  @BeforeEach
  void setUp() {
    registryRepo = mock(RegistryRepository.class);
    fetcher = mock(SegmentedTruthJoinedPredictionFetcher.class);
    driftRepo = mock(DriftMetricsRepository.class);
    job = new WeeklySegmentJob(registryRepo, fetcher, driftRepo, mock(JobLockRepository.class));
  }

  @Test
  void no_champions_writes_zero_rows() throws Exception {
    when(registryRepo.findActiveChampions()).thenReturn(List.of());
    assertThat(job.runOnce(Instant.now())).isEqualTo(0);
    verify(driftRepo, never()).insertBatch(any());
  }

  @Test
  void empty_segment_fetcher_writes_zero_rows() throws Exception {
    ModelVersion champ = champion("model_a", 1L);
    when(registryRepo.findActiveChampions()).thenReturn(List.of(champ));
    when(fetcher.fetchBySegment(any(), anyLong(), any(), any(Instant.class), any(Instant.class)))
        .thenReturn(Map.of());

    assertThat(job.runOnce(Instant.now())).isEqualTo(0);
    verify(driftRepo, never()).insertBatch(any());
  }

  @Test
  void segment_brier_rows_carry_dim_value_and_window_in_key() throws Exception {
    ModelVersion champ = champion("model_a", 1L);
    when(registryRepo.findActiveChampions()).thenReturn(List.of(champ));
    // 200 well-calibrated rows per park, 3 parks → 3 segment buckets per (dim=park_id, window).
    Map<String, List<TruthJoinedRow>> byPark = new HashMap<>();
    for (String park : List.of("NYY", "BOS", "LAD")) {
      byPark.put(park, buildRows(200));
    }
    // park_id has 3 buckets each window. Other dimensions return empty so we only count park_id.
    when(fetcher.fetchBySegment(
            eq("model_a"), eq(1L), eq("park_id"), any(Instant.class), any(Instant.class)))
        .thenReturn(byPark);

    int rows = job.runOnce(Instant.now());
    // park_id × 3 buckets × 3 windows (7d/28d/220d) = 9 rows
    assertThat(rows).isEqualTo(9);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<DriftMetric>> cap = ArgumentCaptor.forClass(List.class);
    verify(driftRepo).insertBatch(cap.capture());
    List<DriftMetric> batch = cap.getValue();
    assertThat(batch).extracting(DriftMetric::metricType).containsOnly(MetricType.SEGMENT_BRIER);
    assertThat(batch)
        .extracting(DriftMetric::featureOrSegment)
        .anyMatch(s -> s.equals("park_id:NYY:7d"))
        .anyMatch(s -> s.equals("park_id:BOS:28d"))
        .anyMatch(s -> s.equals("park_id:LAD:220d"));
  }

  @Test
  void low_sample_size_segments_get_lowsamp_suffix() throws Exception {
    ModelVersion champ = champion("model_a", 1L);
    when(registryRepo.findActiveChampions()).thenReturn(List.of(champ));
    // 50 rows < 100 threshold → :lowsamp suffix.
    when(fetcher.fetchBySegment(
            eq("model_a"), eq(1L), eq("stand"), any(Instant.class), any(Instant.class)))
        .thenReturn(Map.of("R", buildRows(50)));

    job.runOnce(Instant.now());
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<DriftMetric>> cap = ArgumentCaptor.forClass(List.class);
    verify(driftRepo).insertBatch(cap.capture());
    assertThat(cap.getValue())
        .extracting(DriftMetric::featureOrSegment)
        .allSatisfy(s -> assertThat(s).endsWith(":lowsamp"));
  }

  private static List<TruthJoinedRow> buildRows(int n) {
    List<TruthJoinedRow> rows = new java.util.ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      // Alternate truth class so Brier is meaningful (not all-correct dirac).
      rows.add(new TruthJoinedRow(new double[] {0.7, 0.3}, i % 2));
    }
    return rows;
  }

  private static long anyLong() {
    return org.mockito.ArgumentMatchers.anyLong();
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
