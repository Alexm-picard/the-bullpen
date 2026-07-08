package net.thebullpen.baseball.inference;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.thebullpen.baseball.inference.routing.Role;

/**
 * Output of {@link InferenceRouter#route}. Carries the prediction the user sees plus (when
 * shadow-mode dispatch ran) a FUTURE for the parallel shadow prediction the caller should log.
 *
 * <p>{@code servingVersionId} is the {@code model_versions.id} of the version that produced {@link
 * #servingResponse()} - used by the caller to construct the prediction-log row's {@code model_name
 * + model_version} fields by re-resolving against the registry, OR just stamped on the response if
 * the controller has the version string cached. The sentinel value {@code -1L} means "no routing
 * config existed; the legacy fallback served this prediction" - the caller substitutes its
 * hardcoded version string in that case.
 *
 * <p>{@code shadowFuture} is present iff a separate shadow-row log entry should be written with
 * {@link Role#SHADOW}. Since F1.4 the shadow challenger runs FIRE-AND-FORGET off the request path:
 * {@code route()} returns the champion immediately without waiting on it, so this is a {@link
 * CompletableFuture} the caller attaches a {@code whenComplete} to (log the {@link Role#SHADOW} row
 * on success; failures are already surfaced by the router). It has already had an {@code orTimeout}
 * and the shadow-latency metric applied by the router. The caller must NOT block on it - doing so
 * would re-introduce the exact user-facing shadow-join stall F1.4 removed.
 *
 * <p>In AB mode bucketed to challenger, the served prediction IS the challenger - there's no
 * separate shadow row, just a {@link Role#CHALLENGER} serving row, so {@code shadowFuture} stays
 * empty.
 */
public record RoutedPrediction<Resp>(
    Resp servingResponse,
    long servingVersionId,
    Role servingRole,
    Optional<CompletableFuture<Resp>> shadowFuture,
    Optional<Long> shadowVersionId) {

  /** True when the caller should attach a fire-and-forget {@link Role#SHADOW} logging callback. */
  public boolean hasShadowRow() {
    return shadowFuture.isPresent();
  }
}
