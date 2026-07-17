package net.thebullpen.baseball.inference;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Timer;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import net.thebullpen.baseball.inference.routing.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * The shared HTTP-prediction orchestration skeleton (M5). {@link
 * net.thebullpen.baseball.inference.PitchPredictionService} (#185) is the TEMPLATE this follows;
 * this class extracts the same skeleton the two batted-ball controllers had each duplicated, so
 * they shrink to web-layer shells the way the pitch controller did.
 *
 * <p><b>What the skeleton owns</b> (identical across families, moved here verbatim): timer + {@code
 * requestAt} capture (requestAt immediately after timer start - behavior preservation), {@link
 * InferenceRouter} dispatch, the served-latency timer stop + role-tagged prediction counter, the
 * CHAMPION/CHALLENGER {@code prediction_log} row, the F1.4 fire-and-forget SHADOW row, and the
 * error counter with the {@link ResponseStatusException} carve-out (a 503/4xx is a routing outcome,
 * not an inference error).
 *
 * <p><b>What a {@link Family} supplies</b> (the per-model divergences, instantiated PER REQUEST so
 * the predict legs can close over the request): the two predict closures, prediction serialization,
 * identity resolution for routed version ids, and - the load-bearing one - the {@code -1L}
 * legacy-identity policy.
 *
 * <p><b>The -1L contract, per family - PRESERVED, deliberately NOT unified.</b> {@link
 * InferenceRouter} returns {@code servingVersionId == -1L} to mean exactly "no A/B routing row
 * existed; the legacy fallback closure served". What that implies for the LOGGED identity differs
 * by family and each meaning is a data contract:
 *
 * <ul>
 *   <li><b>Single-park toy</b> ({@code _toy_batted_ball}): the toy bean served - no registry row
 *       backs the serve. Identity = hardcoded {@code v0} + the ctor-cached toy pipeline schema hash
 *       + <b>null</b> FK. Reconciliation joins {@code prediction_log.model_version_id} against the
 *       registry and depends on legacy rows being NULL (never -1; the sentinel is never persisted).
 *   <li><b>All-parks</b> ({@code battedball_outcome}): the fallback served the registry LIVE
 *       champion (503 when none) - a real registry row DID serve. Identity = the re-resolved
 *       champion's real id/version/hash; the FK is never null on this family.
 * </ul>
 *
 * <p><b>Other preserved data-contract details</b> (do not "fix" without a /decide): the {@code
 * featureHash} log slot carries the model's feature-pipeline SCHEMA hash, not a per-request hash
 * (drift/reconciliation join semantics); the SHADOW row reuses the champion leg's {@code
 * requestAt}, {@code latencyMs}, and correlation id (shadow wall time lives only on the router's
 * shadow-latency metric); every request logs a serving row unconditionally - it is the drift
 * observed side.
 *
 * <p><b>One deliberate improvement over the moved code</b>: the shadow {@code whenComplete} used to
 * catch only {@link JsonProcessingException}, so any other failure (e.g. a model-load error
 * resolving the shadow identity) vanished silently inside the future. It now catches {@link
 * Throwable} and WARNs - same outcome (row dropped), visible instead of silent.
 *
 * <p>{@code PitchPredictionService} is deliberately NOT ported onto this class: its Head-enum
 * rule-9 dispatch, Tier-4 precondition, and flagged dev-direct tier are pitch-specific. Convergence
 * is a possible follow-up, not part of the M5 extraction.
 */
@Service
@Profile("api")
public class PredictionOrchestrator {

  private static final Logger log = LoggerFactory.getLogger(PredictionOrchestrator.class);

  private final InferenceRouter router;
  private final AsyncPredictionLogger logger;
  private final InferenceMetrics metrics;
  private final ObjectMapper objectMapper;

  public PredictionOrchestrator(
      InferenceRouter router,
      AsyncPredictionLogger logger,
      InferenceMetrics metrics,
      ObjectMapper objectMapper) {
    this.router = router;
    this.logger = logger;
    this.metrics = metrics;
    this.objectMapper = objectMapper;
  }

  /** The logged/served identity of one serve: version label + schema hash + registry FK. */
  public record Identity(String versionLabel, String schemaHash, Long versionFk) {}

  /** What the skeleton hands back to the controller for response mapping. */
  public record Served<R>(R response, Identity identity, long elapsedNanos) {}

  /**
   * The per-family divergences. Instantiated per request (the predict legs close over the request
   * DTO); identity methods are request-independent.
   */
  public interface Family<R> {
    /** The routed + logged model name (also the metrics tag). */
    String modelName();

    /**
     * What gets serialized into the {@code prediction_log.features} column - the RAW request DTO
     * (never a transformed pipeline row): the drift observed side JSONExtracts from it by
     * request-field name. On the interface (not a separate parameter) so the what-gets-logged
     * contract lives in one place.
     */
    Object featurePayload();

    /** Registry-routed leg: predict with a specific {@code model_versions.id}. */
    R predictByVersionId(long versionId) throws Exception;

    /** Unrouted leg (no A/B routing row): the family's fallback serve. */
    R legacyFallback() throws Exception;

    /**
     * How an unrouted ({@code -1L}) serve resolves its LOGGED identity - the per-family policy
     * documented on the class javadoc. May throw {@link ResponseStatusException} (all-parks 503).
     */
    Identity legacyIdentity();

    /** Identity for a real registry version id (used for routed serves and shadow rows). */
    Identity identityFor(long versionId);

    /** Serialize the family's prediction payload for the {@code prediction_log} row. */
    String serializePrediction(R response) throws JsonProcessingException;
  }

  public <R> Served<R> predict(Family<R> family, long gameId, String correlationId)
      throws Exception {
    String modelName = family.modelName();
    Timer.Sample sample = metrics.startTimer();
    Instant requestAt = Instant.now();
    // Serving role for the error counter: stays "unknown" until routing resolves it, so an error
    // before then (e.g. a non-503 routing failure) is attributed honestly rather than mislabeled.
    String role = "unknown";

    try {
      RoutedPrediction<R> routed =
          router.route(
              modelName,
              gameId,
              versionId -> {
                try {
                  return family.predictByVersionId(versionId);
                } catch (Exception e) {
                  throw wrap(e);
                }
              },
              () -> {
                try {
                  return family.legacyFallback();
                } catch (Exception e) {
                  throw wrap(e);
                }
              });

      // Set the moment routing resolves, so an error after this point carries the real serving role
      // while anything before it stays "unknown" (an honest pre-routing bucket).
      role = routed.servingRole().name().toLowerCase(Locale.ROOT);
      long elapsedNanos = sample.stop(metrics.timer(modelName));
      metrics.incrementPrediction(modelName, role);
      float elapsedMs = elapsedNanos / 1_000_000.0f;

      Identity identity =
          routed.servingVersionId() == -1L
              ? family.legacyIdentity()
              : family.identityFor(routed.servingVersionId());

      // Serialized once and shared with the shadow row - both legs log the identical request
      // JSON today (the drift observed side JSONExtracts from it by request-field name).
      String features = objectMapper.writeValueAsString(family.featurePayload());

      logger.enqueue(
          new PredictionLogEvent(
              UUID.randomUUID(),
              requestAt,
              modelName,
              identity.versionLabel(),
              identity.versionFk(),
              toLogRole(routed.servingRole()),
              identity.schemaHash(),
              features,
              family.serializePrediction(routed.servingResponse()),
              elapsedMs,
              correlationId));

      // Shadow-mode dispatch: log the parallel SHADOW prediction FIRE-AND-FORGET off the request
      // path (F1.4), with the challenger's metadata. On shadow failure the router already logged
      // + counted the drop - no row here.
      routed
          .shadowFuture()
          .ifPresent(
              shadowFut -> {
                long shadowVid = routed.shadowVersionId().orElseThrow();
                shadowFut.whenComplete(
                    (shadowResp, ex) -> {
                      if (ex != null) {
                        return;
                      }
                      try {
                        Identity shadowIdentity = family.identityFor(shadowVid);
                        logger.enqueue(
                            new PredictionLogEvent(
                                UUID.randomUUID(),
                                requestAt,
                                modelName,
                                shadowIdentity.versionLabel(),
                                shadowVid,
                                PredictionLogEvent.Role.SHADOW,
                                shadowIdentity.schemaHash(),
                                features,
                                family.serializePrediction(shadowResp),
                                elapsedMs, // champion leg's latency - preserved data contract
                                correlationId));
                      } catch (Throwable t) {
                        // Anything escaping whenComplete would vanish into the future. Row
                        // dropped either way; make it visible (was JsonProcessingException-only).
                        log.warn("shadow row dropped for {}: {}", modelName, t.toString());
                      }
                    });
              });

      return new Served<>(routed.servingResponse(), identity, elapsedNanos);
    } catch (ResponseStatusException e) {
      throw e; // 503 (no champion) / client errors are routing outcomes, not inference errors
    } catch (Exception e) {
      metrics.incrementError(modelName, role, e.getClass().getSimpleName());
      throw e;
    }
  }

  private static RuntimeException wrap(Exception e) {
    // Faithful union of the two originals' (different!) wrapping boundaries:
    // - ResponseStatusException passes through UNWRAPPED: the all-parks legacy leg's 503
    //   (requireChampionId) ran OUTSIDE its helper's try/catch, so its RSE always reached the
    //   carve-out bare (test-pinned: returns_503_when_no_champion_and_no_routing_config).
    // - Everything else gets the unconditional fresh RuntimeException wrapper both originals
    //   applied to their predict bodies - the error-counter label (getClass().getSimpleName())
    //   and router-side cause chains are shaped by it. Move-not-rewrite: preserve both exactly.
    return e instanceof ResponseStatusException rse ? rse : new RuntimeException(e);
  }

  private static PredictionLogEvent.Role toLogRole(Role role) {
    return switch (role) {
      case CHAMPION -> PredictionLogEvent.Role.CHAMPION;
      case CHALLENGER -> PredictionLogEvent.Role.CHALLENGER;
      case SHADOW -> PredictionLogEvent.Role.SHADOW;
    };
  }
}
