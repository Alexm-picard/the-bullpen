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
}
