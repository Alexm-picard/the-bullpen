package net.thebullpen.baseball.retraining.dto;

import java.util.Locale;

/**
 * Source taxonomy for a retrain enqueue (decision [79] hybrid triggers):
 *
 * <ul>
 *   <li>{@link #SCHEDULED} — monthly floor cron. Guarantees a fresh retrain happens regardless of
 *       drift signals.
 *   <li>{@link #DRIFT} — fired by 3c.7's {@code DriftAlertEvaluator} when a PAGE-level calibration
 *       drift sustains. Adaptive: only when the model is actually misbehaving.
 *   <li>{@link #MANUAL} — admin enqueues via the 3a.4 admin API. Used for postmortem follow-up +
 *       experimental retrains.
 * </ul>
 *
 * <p>Per rule 6, regardless of trigger source the retrain produces a {@code candidate}-stage row;
 * promotion stays human-gated.
 */
public enum TriggerType {
  SCHEDULED,
  DRIFT,
  MANUAL;

  public String dbValue() {
    return name().toLowerCase(Locale.ROOT);
  }

  public static TriggerType fromDbValue(String s) {
    return TriggerType.valueOf(s.toUpperCase(Locale.ROOT));
  }
}
