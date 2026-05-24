package net.thebullpen.baseball.registry.dto;

import java.time.Instant;
import java.util.Objects;

/**
 * Input to {@code RegistryService.register(...)}. Carries everything needed to populate a {@link
 * ModelVersion} row at {@link Stage#CANDIDATE} — the service generates the surrogate id +
 * timestamps + sets the stage itself + computes the {@code featureSchemaHash} from the file at
 * {@code featurePipelinePath} (so the hash + the file are guaranteed to agree).
 *
 * <p>The required fields line up with the {@code NOT NULL} columns in migration V010 plus the paths
 * the service validates exist on disk. Eval metrics are passed as a JSON string so the caller can
 * put any model-specific shape in there (the registry doesn't interpret the structure).
 */
public record RegisterRequest(
    String modelName,
    String version,
    String artifactPath,
    String metadataPath,
    String featurePipelinePath,
    String trainingDataHash,
    String trainingDataWindow,
    String evalMetricsJson,
    Instant trainedAt,
    String createdBy,
    String notes) {

  public RegisterRequest {
    Objects.requireNonNull(modelName, "modelName");
    Objects.requireNonNull(version, "version");
    Objects.requireNonNull(artifactPath, "artifactPath");
    Objects.requireNonNull(metadataPath, "metadataPath");
    Objects.requireNonNull(featurePipelinePath, "featurePipelinePath");
    Objects.requireNonNull(trainingDataHash, "trainingDataHash");
    Objects.requireNonNull(trainingDataWindow, "trainingDataWindow");
    Objects.requireNonNull(evalMetricsJson, "evalMetricsJson");
    Objects.requireNonNull(trainedAt, "trainedAt");
    if (modelName.isBlank()) {
      throw new IllegalArgumentException("modelName must not be blank");
    }
    if (version.isBlank()) {
      throw new IllegalArgumentException("version must not be blank");
    }
  }
}
