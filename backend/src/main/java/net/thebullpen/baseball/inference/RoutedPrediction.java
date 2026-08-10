package net.thebullpen.baseball.inference;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.thebullpen.baseball.inference.routing.Role;

/**
 * Output of {@link InferenceRouter#route}. Carries the prediction the user sees plus (when
 * shadow-mode dispatch ran) a FUTURE for the parallel shadow prediction the caller should log.
 *
 * <p>{@code servingVersionId} is the {@code model_versions.id} of the version that produced {@link
 * #servingResponse()}. The sentinel value {@code -1L} means EXACTLY ONE thing here: "no routing
 * config existed; the legacy fallback closure served" - nothing more. What that implies for the
 * LOGGED identity is a PER-CALLER policy (the M5 orchestrator's {@code Family.legacyIdentity()});
 * enumerated because the divergence is a data contract per leg:
 *
 * <ul>
 *   <li>Single-park batted-ball: the toy bean served - hardcoded {@code v0} label + NULL FK
 *       (reconciliation depends on legacy rows being NULL; -1 is never persisted).
 *   <li>All-parks batted-ball: the fallback served the registry LIVE champion - the caller
 *       RE-RESOLVES the champion for a real FK (503 when none is live).
 *   <li>Pitch HTTP ({@code PitchPredictionService}): dev-direct bean label + NULL FK.
 *   <li>Live-ingest ({@code LivePitchPredictor}): champion re-resolved to a real FK; no champion
 *       means no row at all.
 * </ul>
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
