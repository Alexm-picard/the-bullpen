package net.thebullpen.baseball.inference.routing;

/**
 * The role assigned to a single inference request by {@link Bucketer#route} — captured in the
 * prediction log so post-hoc evaluation can split champion vs challenger metrics.
 *
 * <ul>
 *   <li>{@link #CHAMPION} — the request is served by the model_routing row's champion. The
 *       user-facing prediction comes from this head.
 *   <li>{@link #CHALLENGER} — A/B mode only. The request is served by the challenger version; the
 *       prediction comes from this head.
 *   <li>{@link #SHADOW} — shadow mode (decision [71]). The request is served by the champion (so
 *       this role's actual user-facing prediction is the champion's) AND the challenger runs
 *       alongside with its prediction logged-only. Distinguished from {@link #CHAMPION} in the
 *       prediction log so the shadow-run row can be matched back to the shadow head.
 * </ul>
 */
public enum Role {
  CHAMPION,
  CHALLENGER,
  SHADOW
}
