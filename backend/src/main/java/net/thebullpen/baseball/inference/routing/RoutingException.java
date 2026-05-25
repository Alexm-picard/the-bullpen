package net.thebullpen.baseball.inference.routing;

/**
 * Sealed exception for routing-domain failures. Mirrors the {@code RegistryException} shape from 3a
 * — the admin controller can pattern-match on the subclass to map each failure to a specific HTTP
 * status (validation errors → 400, missing-row → 404).
 */
public sealed class RoutingException extends RuntimeException
    permits RoutingException.UnknownModel,
        RoutingException.ChallengerNotInShadow,
        RoutingException.ChallengerSameAsChampion,
        RoutingException.InvalidTrafficPct,
        RoutingException.ShadowModeWithTraffic {

  protected RoutingException(String message) {
    super(message);
  }

  /** No {@code model_routing} row for the given model name yet. */
  public static final class UnknownModel extends RoutingException {
    public UnknownModel(String modelName) {
      super(
          "routing: no model_routing row for "
              + modelName
              + " — first promotion to CHAMPION auto-creates one");
    }
  }

  /**
   * Setting the challenger requires the candidate version to be at {@code Stage.SHADOW} — the leaf
   * body's rule. CHAMPION as a challenger would be a contradiction; CANDIDATE means it hasn't been
   * gated through the shadow phase yet; ARCHIVED is terminal.
   */
  public static final class ChallengerNotInShadow extends RoutingException {
    public ChallengerNotInShadow(long versionId, String currentStage) {
      super(
          "routing: cannot set version "
              + versionId
              + " as challenger — must be at SHADOW stage, currently "
              + currentStage);
    }
  }

  /** Champion and challenger can't be the same version — would be a no-op A/B. */
  public static final class ChallengerSameAsChampion extends RoutingException {
    public ChallengerSameAsChampion(long versionId) {
      super("routing: champion and challenger cannot be the same version_id (" + versionId + ")");
    }
  }

  /** {@code challenger_traffic_pct} must be in [0, 100]. */
  public static final class InvalidTrafficPct extends RoutingException {
    public InvalidTrafficPct(double pct) {
      super("routing: challenger_traffic_pct must be in [0, 100]; got " + pct);
    }
  }

  /**
   * {@link RoutingMode#SHADOW} with {@code traffic_pct > 0} is contradictory — shadow means the
   * challenger never serves user-facing traffic. Leaf "Known edge cases".
   */
  public static final class ShadowModeWithTraffic extends RoutingException {
    public ShadowModeWithTraffic(double pct) {
      super(
          "routing: mode=SHADOW must have traffic_pct = 0; got "
              + pct
              + " — switch mode to AB or set traffic_pct to 0");
    }
  }
}
