package net.thebullpen.baseball.inference;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * One row destined for the {@code prediction_log} ClickHouse table.
 *
 * <p>{@code features} and {@code prediction} are pre-serialized JSON strings — the queue keeps
 * Strings (not pojos) so the producer pays the encoding cost on its thread, not the flusher's.
 */
public record PredictionLogEvent(
    UUID requestId,
    Instant requestAt,
    String modelName,
    String modelVersion,
    Long modelVersionId, // nullable: V1 / legacy-fallback dispatches set this null (3b.5)
    Role role,
    String featureHash,
    String features,
    String prediction,
    float latencyMs,
    String correlationId) {

  public enum Role {
    CHAMPION,
    CHALLENGER,
    SHADOW;

    public String dbValue() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  /**
   * Legacy 1.7-era constructor without the registry FK. Existing call sites (pitch controller
   * not-yet-rewired through the router, all the existing tests) continue to use this; new
   * router-integrated paths use the full constructor with {@code modelVersionId} populated.
   */
  public PredictionLogEvent(
      UUID requestId,
      Instant requestAt,
      String modelName,
      String modelVersion,
      Role role,
      String featureHash,
      String features,
      String prediction,
      float latencyMs,
      String correlationId) {
    this(
        requestId,
        requestAt,
        modelName,
        modelVersion,
        null,
        role,
        featureHash,
        features,
        prediction,
        latencyMs,
        correlationId);
  }
}
