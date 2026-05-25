package net.thebullpen.baseball.inference;

import java.util.Optional;
import net.thebullpen.baseball.inference.routing.Role;

/**
 * Output of {@link InferenceRouter#route}. Carries the prediction the user sees plus (when
 * shadow-mode dispatch ran) the parallel shadow prediction the caller should also log.
 *
 * <p>{@code servingVersionId} is the {@code model_versions.id} of the version that produced {@link
 * #servingResponse()} — used by the caller to construct the prediction-log row's {@code model_name
 * + model_version} fields by re-resolving against the registry, OR just stamped on the response if
 * the controller has the version string cached. The sentinel value {@code -1L} means "no routing
 * config existed; the legacy fallback served this prediction" — the caller substitutes its
 * hardcoded version string in that case.
 *
 * <p>{@code shadowVersionId} is present iff a separate shadow-row log entry should be written with
 * {@link Role#SHADOW}. In AB mode bucketed to challenger, the served prediction IS the challenger —
 * there's no separate shadow row, just a {@link Role#CHALLENGER} serving row, so {@code
 * shadowResponse} stays empty.
 */
public record RoutedPrediction<Resp>(
    Resp servingResponse,
    long servingVersionId,
    Role servingRole,
    Optional<Resp> shadowResponse,
    Optional<Long> shadowVersionId) {

  /** True when the caller should log a separate {@link Role#SHADOW} row. */
  public boolean hasShadowRow() {
    return shadowResponse.isPresent();
  }
}
