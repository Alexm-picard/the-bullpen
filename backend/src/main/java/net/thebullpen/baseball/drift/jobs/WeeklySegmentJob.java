package net.thebullpen.baseball.drift.jobs;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.thebullpen.baseball.drift.DriftMetric;
import net.thebullpen.baseball.drift.DriftMetricsRepository;
import net.thebullpen.baseball.drift.MetricType;
import net.thebullpen.baseball.drift.SegmentedTruthJoinedPredictionFetcher;
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
 * Weekly per-segment Brier batch (leaf 3c.5 / decision [61]). Sunday 23:30 ET. For every active
 * CHAMPION × every segment dimension × every analysis window, computes Brier per segment value and
 * writes a {@link MetricType#SEGMENT_BRIER} drift row. Surfaces per-park / per-pitch-type /
 * per-handedness regressions the global daily Brier averages out.
 *
 * <p>{@code feature_or_segment} format: {@code "<dimension>:<value>:<windowDays>d"} — e.g. {@code
 * "park_id:NYY:7d"}, {@code "stand:R:28d"}. Lets the dashboard pivot by dimension or window without
 * parsing JSON.
 *
 * <p>Low-sample-size segments (< 100 predictions, per leaf step 3) are still written but with the
 * suffix {@code ":lowsamp"} appended so the dashboard can fade them out. Default threshold 100;
 * configurable via {@code bullpen.drift.weekly-segment.low-sample-size}.
 */
@Component
@Profile("worker")
public class WeeklySegmentJob {

  private static final Logger log = LoggerFactory.getLogger(WeeklySegmentJob.class);

  /** Segment dimensions exposed to the dashboard (matches the leaf body's set). */
  static final List<String> SEGMENT_DIMENSIONS =
      List.of("stand", "park_id", "count_state", "inning_bucket", "pitch_type", "month");

  /** Analysis windows: 7d, 28d, season-to-date (220d). */
  static final List<Duration> WINDOWS =
      List.of(Duration.ofDays(7), Duration.ofDays(28), Duration.ofDays(220));

  static final long LOW_SAMPLE_THRESHOLD = 100L;

  private final RegistryRepository registryRepo;
  private final SegmentedTruthJoinedPredictionFetcher fetcher;
  private final DriftMetricsRepository driftRepo;

  public WeeklySegmentJob(
      RegistryRepository registryRepo,
      SegmentedTruthJoinedPredictionFetcher fetcher,
      DriftMetricsRepository driftRepo) {
    this.registryRepo = registryRepo;
    this.fetcher = fetcher;
    this.driftRepo = driftRepo;
  }

  @Scheduled(cron = "0 30 23 * * SUN", zone = "America/New_York")
  public void run() {
    try {
      runOnce(Instant.now());
    } catch (RuntimeException e) {
      log.error("WeeklySegmentJob: run failed", e);
    }
  }

  /** Visible-for-tests entry point. Returns the number of drift_metric rows written. */
  public int runOnce(Instant computedAt) {
    List<ModelVersion> champions = registryRepo.findActiveChampions();
    if (champions.isEmpty()) {
      log.info("WeeklySegmentJob: no active champions registered");
      return 0;
    }
    List<DriftMetric> rows = new ArrayList<>();
    for (ModelVersion champ : champions) {
      for (String dim : SEGMENT_DIMENSIONS) {
        for (Duration win : WINDOWS) {
          rows.addAll(computeOne(champ, dim, win, computedAt));
        }
      }
    }
    if (rows.isEmpty()) {
      log.info("WeeklySegmentJob: no rows to write (no segmented truth data)");
      return 0;
    }
    try {
      driftRepo.insertBatch(rows);
    } catch (SQLException e) {
      throw new RuntimeException("WeeklySegmentJob: failed to insert drift_metrics batch", e);
    }
    log.info(
        "WeeklySegmentJob: wrote {} segment-Brier row(s) across {} champion(s)",
        rows.size(),
        champions.size());
    return rows.size();
  }

  private List<DriftMetric> computeOne(
      ModelVersion champ, String segmentDimension, Duration window, Instant computedAt) {
    Instant windowEnd = computedAt;
    Instant windowStart = windowEnd.minus(window);
    Map<String, List<TruthJoinedRow>> byValue =
        fetcher.fetchBySegment(
            champ.modelName(), champ.id(), segmentDimension, windowStart, windowEnd);
    if (byValue.isEmpty()) {
      return List.of();
    }
    long windowDays = window.toDays();
    List<DriftMetric> out = new ArrayList<>();
    for (Map.Entry<String, List<TruthJoinedRow>> e : byValue.entrySet()) {
      List<TruthJoinedRow> rows = e.getValue();
      if (rows.isEmpty()) {
        continue;
      }
      List<double[]> probs = new ArrayList<>(rows.size());
      int[] truth = new int[rows.size()];
      for (int i = 0; i < rows.size(); i++) {
        probs.add(rows.get(i).probs());
        truth[i] = rows.get(i).truthClass();
      }
      double brier = Calibration.brier(probs, truth);
      String key = segmentDimension + ":" + e.getKey() + ":" + windowDays + "d";
      if (rows.size() < LOW_SAMPLE_THRESHOLD) {
        key = key + ":lowsamp";
      }
      out.add(
          new DriftMetric(
              computedAt,
              champ.modelName(),
              champ.id(),
              MetricType.SEGMENT_BRIER,
              key,
              brier,
              rows.size(),
              windowStart,
              windowEnd));
    }
    return out;
  }
}
