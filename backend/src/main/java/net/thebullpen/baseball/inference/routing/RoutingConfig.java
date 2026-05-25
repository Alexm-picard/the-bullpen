package net.thebullpen.baseball.inference.routing;

import java.time.Instant;

/**
 * Pure data record mirroring one row of {@code model_routing} (migration V011). Read on every
 * inference request via {@link RoutingService#getRouting(String)} (Caffeine-cached, 30s TTL);
 * written when an admin flips a challenger or moves the traffic slider, or when the registry
 * auto-creates a row on first CHAMPION promotion.
 *
 * <p>{@code challengerVersionId} is nullable: a model can have a champion-only routing where no
 * challenger is registered yet. {@code challengerTrafficPct} is meaningful only in {@link
 * RoutingMode#AB} mode; the service rejects non-zero values in {@link RoutingMode#SHADOW}.
 */
public record RoutingConfig(
    long id,
    String modelName,
    long championVersionId,
    Long challengerVersionId,
    double challengerTrafficPct,
    RoutingMode mode,
    Instant updatedAt) {

  /** True when there's a challenger registered alongside the champion. */
  public boolean hasChallenger() {
    return challengerVersionId != null;
  }
}
