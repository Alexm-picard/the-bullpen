package net.thebullpen.baseball.registry.experiment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.Map;
import java.util.Objects;

/**
 * Input to {@code ExperimentService.start(...)} — pre-declared promotion criteria per rule 5 /
 * decision [72]. Every field is locked at start-time; nothing here can be edited after the row is
 * inserted (the gate can't be moved post-hoc).
 *
 * <p>{@code primaryThreshold} is the margin the challenger must beat the champion by on the primary
 * metric, in metric-units. e.g. {@code primaryMetric=BRIER, primaryThreshold=0.005} means
 * "challenger's Brier must be at most champion's Brier minus 0.005."
 *
 * <p>{@code guardrails} is a map of {metric-name → max-allowed-delta}: any guardrail metric whose
 * challenger value exceeds champion + delta triggers {@code failed} regardless of the primary
 * metric. The keys are free-form strings the evaluator recognizes (e.g. {@code "ece"}, {@code
 * "log_loss"}); unknown keys are ignored with a log line.
 */
public record StartExperimentRequest(
    @NotBlank String modelName,
    @Positive long championVersionId,
    @Positive long challengerVersionId,
    @NotNull PrimaryMetric primaryMetric,
    double primaryThreshold,
    @Positive long sampleSizeTarget,
    @NotNull Map<String, Double> guardrails,
    @NotBlank String reason) {

  public StartExperimentRequest {
    Objects.requireNonNull(modelName, "modelName");
    Objects.requireNonNull(primaryMetric, "primaryMetric");
    Objects.requireNonNull(guardrails, "guardrails");
    if (championVersionId == challengerVersionId) {
      throw new IllegalArgumentException(
          "championVersionId and challengerVersionId must differ; got " + championVersionId);
    }
    if (primaryThreshold < 0) {
      throw new IllegalArgumentException(
          "primaryThreshold must be >= 0 (margin in metric units); got " + primaryThreshold);
    }
  }
}
