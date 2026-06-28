package net.thebullpen.baseball.registry.experiment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.util.Objects;

/**
 * Input to the {@code POST /v1/admin/experiments/import-offline} admin endpoint: ingest a committed
 * OFFLINE promotion-gate artifact ({@code artifactName}, bundled per {@code
 * OfflineGateEvidenceRepository}) as a terminal {@code passed} experiment_results row binding
 * {@code championVersionId} (which must be the CURRENT champion) to {@code challengerVersionId}.
 *
 * <p>The criteria + metrics come from the committed ARTIFACT, never from this request - this only
 * declares WHICH committed evidence and WHICH registered versions. See ADR-0012 / decision [166].
 */
public record ImportOfflineGateRequest(
    @NotBlank String modelName,
    @Positive long championVersionId,
    @Positive long challengerVersionId,
    @NotBlank String artifactName,
    @NotBlank String reason) {

  public ImportOfflineGateRequest {
    Objects.requireNonNull(modelName, "modelName");
    Objects.requireNonNull(artifactName, "artifactName");
    Objects.requireNonNull(reason, "reason");
    if (championVersionId == challengerVersionId) {
      throw new IllegalArgumentException(
          "championVersionId and challengerVersionId must differ; got " + championVersionId);
    }
  }
}
