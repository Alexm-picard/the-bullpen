package net.thebullpen.baseball.drift;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared drift-window helpers used by the daily alert evaluator ({@link
 * net.thebullpen.baseball.drift.alerting.DriftAlertEvaluator}) and the drift retrain trigger
 * ({@code net.thebullpen.baseball.retraining.triggers.DriftTrigger}).
 *
 * <p>Both reason about "N consecutive DAYS over threshold", so both must first collapse the raw
 * {@code drift_metrics} rows to one canonical value per calendar day. Extracted here so the two
 * callers share one implementation instead of drifting apart (the trigger previously counted raw
 * rows, which was the DEF-M3 false-fire bug for the retrain path).
 */
public final class DriftWindows {

  private DriftWindows() {}

  /**
   * Collapse drift rows to one canonical value per calendar day in {@code zone}, latest sample
   * winning per day, returned ascending by day.
   *
   * <p>A same-day rerun of a drift batch writes multiple rows for the same day; counting rows would
   * let K reruns on one day masquerade as K consecutive days and fire a false alert / retrain
   * (DEF-M3). Collapsing to distinct days first is the fix. The latest sample per day supersedes
   * earlier reruns (a rerun is a correction, not a new day).
   *
   * @param rows raw {@code drift_metrics} rows (any order); an empty list yields an empty result.
   * @param zone the calendar zone in which "a day" is measured (the batch schedule's zone).
   * @return one {@code metricValue} per distinct calendar day, ascending by day.
   */
  public static List<Double> dailyCanonical(List<DriftMetric> rows, ZoneId zone) {
    Map<LocalDate, DriftMetric> latestPerDay = new HashMap<>();
    for (DriftMetric m : rows) {
      LocalDate day = m.computedAt().atZone(zone).toLocalDate();
      DriftMetric cur = latestPerDay.get(day);
      if (cur == null || m.computedAt().isAfter(cur.computedAt())) {
        latestPerDay.put(day, m);
      }
    }
    return latestPerDay.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .map(e -> e.getValue().metricValue())
        .toList();
  }
}
