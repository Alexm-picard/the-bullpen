package net.thebullpen.baseball.drift;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit test for {@link DriftWindows#dailyCanonical} - the per-calendar-day collapse both the drift
 * alert evaluator and the drift retrain trigger rely on to count "N consecutive DAYS over
 * threshold" rather than "N raw rows" (DEF-M3). The retrain-trigger regression this pins: a burst
 * of same-day reruns must NOT inflate the day count and false-fire a retrain.
 */
class DriftWindowsTest {

  private static final ZoneId NY = ZoneId.of("America/New_York");

  private static DriftMetric row(Instant at, double value) {
    return new DriftMetric(
        at,
        "model_a",
        1L,
        MetricType.CALIBRATION_ERROR,
        "all",
        value,
        5000L,
        at.minus(java.time.Duration.ofHours(24)),
        at);
  }

  @Test
  void empty_input_yields_empty() {
    assertThat(DriftWindows.dailyCanonical(List.of(), NY)).isEmpty();
  }

  @Test
  void collapses_same_day_reruns_to_one_value_latest_wins() {
    // Two runs on the same NY calendar day (08:00 and 14:00 EDT on 2026-06-01); the later run is a
    // correction and supersedes the earlier one.
    List<Double> daily =
        DriftWindows.dailyCanonical(
            List.of(
                row(Instant.parse("2026-06-01T12:00:00Z"), 0.11),
                row(Instant.parse("2026-06-01T18:00:00Z"), 0.22)),
            NY);
    assertThat(daily).containsExactly(0.22);
  }

  @Test
  void one_value_per_distinct_day_ordered_ascending() {
    // Fed out of order; result is ascending by calendar day.
    List<Double> daily =
        DriftWindows.dailyCanonical(
            List.of(
                row(Instant.parse("2026-06-03T12:00:00Z"), 0.3),
                row(Instant.parse("2026-06-01T12:00:00Z"), 0.1),
                row(Instant.parse("2026-06-02T12:00:00Z"), 0.2)),
            NY);
    assertThat(daily).containsExactly(0.1, 0.2, 0.3);
  }

  @Test
  void same_day_reruns_do_not_inflate_the_day_count() {
    // The DriftTrigger false-fire scenario: 8 raw rows all over the 0.10 threshold, but spanning
    // only 3 distinct days. Counting rows (the old bug) would clear a >=7 gate; counting DAYS does
    // not. dailyCanonical must return exactly 3 values.
    List<Double> daily =
        DriftWindows.dailyCanonical(
            List.of(
                row(Instant.parse("2026-06-01T10:00:00Z"), 0.15),
                row(Instant.parse("2026-06-01T14:00:00Z"), 0.16),
                row(Instant.parse("2026-06-01T18:00:00Z"), 0.17),
                row(Instant.parse("2026-06-02T10:00:00Z"), 0.15),
                row(Instant.parse("2026-06-02T14:00:00Z"), 0.16),
                row(Instant.parse("2026-06-03T10:00:00Z"), 0.15),
                row(Instant.parse("2026-06-03T14:00:00Z"), 0.16),
                row(Instant.parse("2026-06-03T18:00:00Z"), 0.18)),
            NY);
    assertThat(daily).hasSize(3);
  }
}
