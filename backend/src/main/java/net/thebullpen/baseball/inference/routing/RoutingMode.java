package net.thebullpen.baseball.inference.routing;

import java.util.Locale;

/**
 * Mirror of the V011 {@code model_routing.mode} CHECK constraint. Two modes:
 *
 * <ul>
 *   <li>{@link #SHADOW} — the default new-model state (rule 6 / decision [71]). The challenger runs
 *       alongside the champion on every request but its prediction is logged-only; user-facing
 *       traffic always sees the champion.
 *   <li>{@link #AB} — real A/B routing. {@code challenger_traffic_pct} fraction of requests get
 *       routed to the challenger as the served prediction; the rest get the champion. Murmur3
 *       game-id bucketing (leaf 3b.2) is the assignment mechanism so a single game's predictions
 *       are consistently served by the same head.
 * </ul>
 *
 * <p>{@link #SHADOW} with {@code traffic_pct > 0} is a contradictory configuration — the routing
 * service rejects it (leaf "Known edge cases").
 */
public enum RoutingMode {
  SHADOW,
  AB;

  /** Lowercase value persisted to {@code model_routing.mode}. */
  public String dbValue() {
    return name().toLowerCase(Locale.ROOT);
  }

  public static RoutingMode fromDbValue(String s) {
    return RoutingMode.valueOf(s.toUpperCase(Locale.ROOT));
  }
}
