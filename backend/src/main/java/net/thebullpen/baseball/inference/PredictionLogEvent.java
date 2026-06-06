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
    String correlationId,
    // Live-game truth-join key (issue #1 step 3 / decision [143]). Nullable: only the
    // predict-on-live-pitch path (step 4) sets these; HTTP-path and shadow predictions leave them
    // null, so they never match a pitches_live row in the step-5 LEFT JOIN.
    Long gameId,
    Integer atBatIndex,
    Integer pitchNumber) {

  public enum Role {
    CHAMPION,
    CHALLENGER,
    SHADOW;

    public String dbValue() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  /**
   * Router-path constructor with a resolved registry FK but no live-game key - the prior canonical
   * shape. HTTP-path and shadow predictions use this; the {@code (game_id, at_bat_index,
   * pitch_number)} key stays null until the live poller assembles a keyed event (issue #1 step 3).
   */
  public PredictionLogEvent(
      UUID requestId,
      Instant requestAt,
      String modelName,
      String modelVersion,
      Long modelVersionId,
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
        modelVersionId,
        role,
        featureHash,
        features,
        prediction,
        latencyMs,
        correlationId,
        null,
        null,
        null);
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
