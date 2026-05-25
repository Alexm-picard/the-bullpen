package net.thebullpen.baseball.registry.experiment.dto;

import java.util.Map;

/**
 * Output of {@code ExperimentService.evaluate(...)} — the would-be verdict computed from
 * currently-observed paired predictions WITHOUT mutating state. {@code complete} consults this +
 * the sample-size target to decide passed / failed / refuses-to-complete.
 *
 * <p>{@code outcome} is one of:
 *
 * <ul>
 *   <li>{@link Outcome#WOULD_PASS} — challenger beats champion on the primary metric by {@code
 *       threshold} AND no guardrail is violated.
 *   <li>{@link Outcome#WOULD_FAIL_PRIMARY} — primary-metric threshold not met (challenger didn't
 *       beat champion by enough).
 *   <li>{@link Outcome#WOULD_FAIL_GUARDRAIL} — at least one guardrail metric regressed beyond the
 *       pre-declared max delta.
 * </ul>
 *
 * <p>{@code guardrailDeltas} reports every guardrail's observed (challenger - champion) delta —
 * useful for the admin UI even when no violation fired. {@code guardrailsViolated} is the subset
 * whose delta exceeded the allowed max.
 */
public record ExperimentVerdict(
    Outcome outcome,
    long sampleSizeObserved,
    double championMetric,
    double challengerMetric,
    Map<String, Double> guardrailDeltas,
    Map<String, Double> guardrailsViolated) {

  public enum Outcome {
    WOULD_PASS,
    WOULD_FAIL_PRIMARY,
    WOULD_FAIL_GUARDRAIL
  }

  /** True if the primary metric delta meets or exceeds the pre-declared threshold. */
  public boolean primaryThresholdMet() {
    return outcome == Outcome.WOULD_PASS;
  }
}
