package net.thebullpen.baseball.registry.dto;

import java.time.Instant;

/**
 * Pure data record mirroring one row of the {@code model_versions} table (migration V010).
 *
 * <p>Lives under {@code dto/} per the leaf body. The CLAUDE.md hexagonal-lite split keeps {@code
 * domain/} for pure data classes shared across the package boundary; for now this stays
 * registry-local because no other package consumes it yet. If 3b's A/B router or 3c's drift
 * detector start passing ModelVersion around, move it to {@code domain/} in the same commit.
 *
 * <p>{@code eval_metrics}, {@code training_data_window}, and {@code feature_schema_hash} are
 * required at registration time (per decisions [66] [68] and rule 7). {@code promoted_at} is null
 * until the row reaches {@link Stage#SHADOW} or {@link Stage#CHAMPION}.
 */
public record ModelVersion(
    long id,
    String modelName,
    String version,
    String artifactPath,
    String metadataPath,
    String trainingDataHash,
    String trainingDataWindow,
    String featureSchemaHash,
    String evalMetrics, // JSON
    Instant trainedAt,
    Instant promotedAt, // nullable
    Stage stage,
    String createdBy, // nullable
    String notes, // nullable
    Instant createdAt,
    Instant updatedAt) {

  /** Convenience: the natural key the UNIQUE constraint guards. */
  public String naturalKey() {
    return modelName + "/" + version;
  }
}
