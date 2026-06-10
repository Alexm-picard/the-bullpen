package net.thebullpen.baseball.inference;

/**
 * A registered model exists but cannot be loaded or served right now: a stale or archived snapshot,
 * a missing artifact file, or an ORT load failure at serve time. This is a transient
 * SERVICE-UNAVAILABLE condition, distinct from a genuine programming or config bug (a bad contract,
 * an unknown feature transform) which stays a 500.
 *
 * <p>Two consumers act on the distinction:
 *
 * <ul>
 *   <li>the HTTP path maps it to a structured 503 (retryable), not an opaque 500 ({@code
 *       ApiErrorAdvice}). This holds on the direct/synchronous serving path AND across the A/B
 *       router's async boundary - {@code CompletableFuture.join} wraps the cause in a {@code
 *       CompletionException}, which the advice unwraps. (Known edge, moot today: the single-park
 *       pitch/toy controllers re-wrap a load failure in a plain {@code RuntimeException} on the
 *       synchronous fallback, which still surfaces 500 - pitch is champion-less per decision [154]
 *       and the toy path never calls the loader, so neither is reachable; unwrapping those is a
 *       follow-up.)
 *   <li>the live poller degrades that one game's prediction instead of aborting the whole tick
 *       ({@code LivePollingService#predictNextPitch}).
 * </ul>
 *
 * <p>It extends {@link IllegalStateException} so the existing fail-loud load assertions and broad
 * {@code catch (Exception)} sites (the promotion load gate {@code ModelLoadValidator}, the poller's
 * containment catch) keep working unchanged; only the HTTP advice singles out the subtype for 503.
 */
public class ModelUnavailableException extends IllegalStateException {

  public ModelUnavailableException(String message) {
    super(message);
  }

  public ModelUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
