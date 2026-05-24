package net.thebullpen.baseball.registry.dto;

import java.time.Instant;
import java.util.Objects;

/**
 * Input to {@code RegistryService.register(...)}. Carries everything needed to populate a {@link
 * ModelVersion} row at {@link Stage#CANDIDATE} — the service generates the surrogate id +
 * timestamps + sets the stage itself.
 *
 * <p>The required fields (everything except {@code createdBy} + {@code notes}) line up with the
 * {@code NOT NULL} columns in migration V010. The service validates artifact presence on disk and
 * (in 3a.3) the feature schema hash against {@code contracts/feature_pipeline.json}.
 */
public record RegisterRequest(
    String modelName,
    String version,
    String artifactPath,
    String metadataPath,
    String trainingDataHash,
    String trainingDataWindow,
    String featureSchemaHash,
    String evalMetricsJson,
    Instant trainedAt,
    String createdBy,
    String notes) {

  public RegisterRequest {
    Objects.requireNonNull(modelName, "modelName");
    Objects.requireNonNull(version, "version");
    Objects.requireNonNull(artifactPath, "artifactPath");
    Objects.requireNonNull(metadataPath, "metadataPath");
    Objects.requireNonNull(trainingDataHash, "trainingDataHash");
    Objects.requireNonNull(trainingDataWindow, "trainingDataWindow");
    Objects.requireNonNull(featureSchemaHash, "featureSchemaHash");
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
