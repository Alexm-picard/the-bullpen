package net.thebullpen.baseball.registry.experiment.dto;

import java.util.Locale;

/**
 * The metric an experiment evaluates the challenger against. Stored as a lowercase string in {@code
 * experiment_results.primary_metric} (V012 schema, no DB CHECK constraint — the enum is the source
 * of truth for validation).
 *
 * <ul>
 *   <li>{@link #BRIER} — mean squared error of the predicted probability vs the one-hot truth.
 *       Lower is better. Default for 5-class pitch outcome + binary batted-ball HR.
 *   <li>{@link #LOG_LOSS} — cross-entropy. Lower is better. More sensitive to confident-wrong
 *       predictions than Brier.
 *   <li>{@link #ECE} — expected calibration error. Lower is better. Measures probability
 *       miscalibration, not predictive accuracy.
 * </ul>
 *
 * <p>"Challenger wins" means {@code challengerMetric + threshold <= championMetric} — i.e.,
 * lower-is-better with a margin. The threshold is pre-declared at experiment start (decision [72])
 * so the gate can't be moved post-hoc.
 */
public enum PrimaryMetric {
  BRIER,
  LOG_LOSS,
  ECE;

  public String dbValue() {
    return name().toLowerCase(Locale.ROOT).replace('_', '-');
  }

  public static PrimaryMetric fromDbValue(String s) {
    return PrimaryMetric.valueOf(s.toUpperCase(Locale.ROOT).replace('-', '_'));
  }
}
