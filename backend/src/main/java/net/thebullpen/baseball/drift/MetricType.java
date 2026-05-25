package net.thebullpen.baseball.drift;

import java.util.Locale;

/**
 * Mirror of the V013 {@code drift_metrics.metric_type} Enum8. Each variant maps to a specific drift
 * batch job (3c.2–3c.5) and to a specific frontend display formatting (the units differ — PSI is
 * 0..∞, Brier is 0..1, calibration error is 0..1).
 *
 * <ul>
 *   <li>{@link #PSI_FEATURE} — Population Stability Index per input feature (3c.2). Compares the
 *       current 7-day distribution of a feature against the training-snapshot baseline.
 *   <li>{@link #PSI_PREDICTION} — PSI on the model's output distribution (3c.3). Catches shifts
 *       even when individual features don't show drift (e.g., correlated drift).
 *   <li>{@link #BRIER} — Brier score on settled-truth predictions (3c.4). Compared to the
 *       training-time eval baseline.
 *   <li>{@link #CALIBRATION_ERROR} — ECE on the same paired-and-truth set (3c.4 sibling).
 *   <li>{@link #SEGMENT_BRIER} — Brier sliced by segment (3c.5 weekly). Surfaces per-park /
 *       per-pitch-type calibration regressions that the global Brier averages out.
 * </ul>
 */
public enum MetricType {
  PSI_FEATURE,
  PSI_PREDICTION,
  BRIER,
  CALIBRATION_ERROR,
  SEGMENT_BRIER;

  public String dbValue() {
    return name().toLowerCase(Locale.ROOT);
  }

  public static MetricType fromDbValue(String s) {
    return MetricType.valueOf(s.toUpperCase(Locale.ROOT));
  }
}
