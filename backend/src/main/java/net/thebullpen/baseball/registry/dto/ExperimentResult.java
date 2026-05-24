package net.thebullpen.baseball.registry.dto;

import java.time.Instant;

/**
 * Pure data record mirroring one row of {@code experiment_results} (migration V012). The promotion
 * gate (rule 5) reads these — every SHADOW → CHAMPION transition needs a {@link Status#PASSED} row
 * for the (modelName, challengerVersionId) pair before {@code RegistryService.transitionStage} will
 * let it through.
 *
 * <p>{@code primaryMetric}, {@code primaryThreshold}, {@code guardrails}, and {@code
 * sampleSizeTarget} are declared at experiment START (challenger registration) so the gate can't be
 * moved post-hoc. The metric columns + {@code sampleSizeObserved} get populated when status leaves
 * {@link Status#RUNNING} — written by the 3c daily drift job once the challenger has accumulated
 * enough samples.
 */
public record ExperimentResult(
    long id,
    String modelName,
    long championVersionId,
    long challengerVersionId,
    Instant startedAt,
    Instant endedAt, // nullable
    String primaryMetric, // 'brier' | 'log_loss' | 'ece'
    double primaryThreshold,
    String guardrails, // JSON
    long sampleSizeTarget,
    Long sampleSizeObserved, // nullable
    Double championMetric, // nullable
    Double challengerMetric, // nullable
    String guardrailsObserved, // JSON, nullable
    Status status,
    String notes, // nullable
    Instant createdAt) {

  /** {@code status} mirror of the V012 CHECK constraint. */
  public enum Status {
    RUNNING,
    PASSED,
    FAILED,
    ABORTED;

    public String dbValue() {
      return name().toLowerCase();
    }

    public static Status fromDbValue(String s) {
      return Status.valueOf(s.toUpperCase());
    }
  }
}
