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

  /**
   * {@code model_name} character class — leaf 3a.5 "Known edge cases" pins this so a slash in the
   * name can't escape the snapshot-storage directory layout. Lowercase + digits + underscores only;
   * no path separators, no whitespace, no shell metacharacters.
   */
  private static final java.util.regex.Pattern MODEL_NAME_PATTERN =
      java.util.regex.Pattern.compile("^[a-z0-9_]+$");

  /**
   * {@code version} character class — same logic as {@link #MODEL_NAME_PATTERN} plus dot and hyphen
   * (so semver-like + git-like version strings work). Still no slash, no whitespace.
   */
  private static final java.util.regex.Pattern VERSION_PATTERN =
      java.util.regex.Pattern.compile("^[a-zA-Z0-9_.-]+$");

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
    if (!MODEL_NAME_PATTERN.matcher(modelName).matches()) {
      throw new IllegalArgumentException(
          "modelName must match ^[a-z0-9_]+$ (no slashes / whitespace / uppercase); got: "
              + modelName);
    }
    if (version.isBlank()) {
      throw new IllegalArgumentException("version must not be blank");
    }
    if (!VERSION_PATTERN.matcher(version).matches()) {
      throw new IllegalArgumentException(
          "version must match ^[a-zA-Z0-9_.-]+$ (no slashes / whitespace); got: " + version);
    }
  }
}
