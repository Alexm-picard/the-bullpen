package net.thebullpen.baseball.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.thebullpen.baseball.api.dto.PitchTypeRequest;
import net.thebullpen.baseball.data.PitchTypeArsenalDeriver;
import net.thebullpen.baseball.inference.AsyncPredictionLogger;
import net.thebullpen.baseball.inference.FeaturePipelinePitchType;
import net.thebullpen.baseball.inference.InferenceMetrics;
import net.thebullpen.baseball.inference.InferenceRouter;
import net.thebullpen.baseball.inference.LoadedPitchTypeModel;
import net.thebullpen.baseball.inference.ModelLoader;
import net.thebullpen.baseball.inference.PredictionLogEvent;
import net.thebullpen.baseball.inference.RoutedPrediction;
import net.thebullpen.baseball.inference.routing.Role;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Serves the pitch-TYPE PRIOR (decision [183]).
 *
 * <p>WHAT THIS RETURNS IS A PRIOR, NOT A PREDICTION. Top-1 accuracy is around 0.45 because pitch
 * selection is high-entropy, so the model is promoted on CALIBRATION (ECE) and never on accuracy.
 * Nothing in this class, its response, or its errors may frame the output as "the next pitch will
 * be X". That constraint is [183]'s and is not negotiable at the serving layer.
 *
 * <p>WHY THIS LIVES IN {@code api/} RATHER THAN {@code inference/}. It needs both the ONNX model
 * ({@code inference/}) and the career-expanding arsenal deriver ({@code data/}), and ArchUnit's
 * {@code inferenceMustNotDependOnPersistenceExceptItsOwnRepository} forbids the second edge:
 * serving reads models and routing, not application tables. {@code api/} may depend on both -
 * GameController and MatchupController already reach into {@code data/} - and the frozen {@code
 * apiLayerMustBeALeaf} rule only forbids anything depending INWARD on api. So the orchestration
 * belongs here; the alternative would have been to route data access through inference, which is
 * precisely the edge the rule exists to prevent.
 *
 * <p>ROUTED THROUGH {@link InferenceRouter}, not resolved with findChampion. Resolving the champion
 * directly can never emit a SHADOW row, so {@code prediction_log.role} has no correct value
 * available and the v1-to-v2 evidence path has nothing to write. Routing buys the correct role and
 * the shadow dual-log.
 *
 * <p>IT DOES NOT BUY SHADOW-STAGE SERVING, and an earlier version of this comment claimed it did.
 * {@code model_routing} rows are created only by {@code
 * RegistryService.promoteToChampionAtomically}, behind the rule-5 evidence gate, so a model with no
 * champion has no routing config, takes the fallback, and 503s exactly as it did before this
 * change. The false version of this claim is worse than merely wrong: it points a reader who needs
 * first-champion evidence at hand-inserting a {@code model_routing} row, which V011 does not
 * stage-constrain - that would serve an unpromoted model as {@code role=champion} and breach rules
 * 5 and 6 together.
 *
 * <p>The first champion for this family promotes via the OFFLINE GATE ([182]/#334), as {@link
 * net.thebullpen.baseball.registry.RegistryBaselines} already records against this exact model
 * name. Shadow rows are the v1-to-v2 path, not the v0-to-v1 path.
 *
 * <p>SERVE-LIVE-CHAMPION-ELSE-503 otherwise, matching the pitch-outcome heads: 503 rather than 404
 * because the route exists and it is the model that is absent. {@code pitch_type_pre} has neither a
 * champion nor a routing config today, so every call takes that path until one is registered.
 */
@Service
@Profile("api")
@ConditionalOnProperty(name = "bullpen.clickhouse.enabled", havingValue = "true")
public class PitchTypePredictionService {

  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(PitchTypePredictionService.class);

  public static final String MODEL_NAME = "pitch_type_pre";

  private final ModelLoader modelLoader;
  private final InferenceRouter router;
  private final PitchTypeArsenalDeriver arsenal;
  private final AsyncPredictionLogger logger;
  private final InferenceMetrics metrics;
  private final ObjectMapper objectMapper;

  public PitchTypePredictionService(
      ModelLoader modelLoader,
      InferenceRouter router,
      PitchTypeArsenalDeriver arsenal,
      AsyncPredictionLogger logger,
      InferenceMetrics metrics,
      ObjectMapper objectMapper) {
    this.modelLoader = modelLoader;
    this.router = router;
    this.arsenal = arsenal;
    this.logger = logger;
    this.metrics = metrics;
    this.objectMapper = objectMapper;
  }

  /** The served distribution plus the identity the controller needs for its response. */
  public record Served(
      Map<String, Double> probabilities,
      String modelName,
      String servingVersion,
      long priorPitches,
      long elapsedMicros) {}

  public Served predict(PitchTypeRequest req) {
    Instant requestAt = Instant.now();
    String correlationId = org.slf4j.MDC.get("correlation_id");

    // Derived server-side, career-expanding, strictly before this pitch. Runs BEFORE routing so a
    // missing or stale prior refuses once rather than once per routed leg. The deriver refuses
    // instead of guessing: a calibrated prior computed over the wrong history is worse than no
    // answer, which is the whole reason this model is promoted on calibration.
    PitchTypeArsenalDeriver.Arsenal ars;
    try {
      ars =
          arsenal.derive(
              req.pitcherId(),
              req.gameDate(),
              req.gameId(),
              req.atBatIndex(),
              req.pitchNumber(),
              req.balls(),
              req.strikes(),
              LocalDate.now(java.time.ZoneId.of("America/New_York")));
    } catch (PitchTypeArsenalDeriver.PriorUnavailable e) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage(), e);
    }
    FeaturePipelinePitchType.Request features = toPipelineRequest(req, ars);

    // Timed from HERE, after the arsenal reads. latency_ms feeds the Ops dashboard's p50/p95/p99
    // grouped by (model_name, model_version) alongside the pitch-outcome heads, whose timers cover
    // routing plus inference with no DB call on the path. Including two ClickHouse reads would
    // make pitch_type read an order of magnitude slower in the same column for reasons that have
    // nothing to do with inference.
    long startNanos = System.nanoTime();
    // "unknown" until routing resolves, so a failure BEFORE that point is attributed honestly
    // rather than blamed on a role that was never chosen. Mirrors the pitch-outcome shape.
    String role = "unknown";

    try {
      // ROUTED, NOT findChampion. Resolving the champion directly could never emit a SHADOW row, so
      // prediction_log.role would have no correct value to take. Routing buys the role and the
      // shadow dual-log - NOT shadow-stage serving: no champion means no model_routing row, which
      // means the fallback below and a 503, exactly as before. See the class javadoc.
      RoutedPrediction<Map<String, Double>> routed =
          router.route(
              MODEL_NAME,
              req.gameId(),
              versionId -> predictWith(versionId, features),
              () -> {
                throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    MODEL_NAME
                        + " has no promoted champion and no A/B routing config; register and promote"
                        + " a model first. 503 rather than 404: the route exists, the model does"
                        + " not.");
              });

      role = routed.servingRole().name().toLowerCase(java.util.Locale.ROOT);
      metrics.incrementPrediction(MODEL_NAME, role);
      Map<String, Double> probs = routed.servingResponse();
      long elapsedMicros = (System.nanoTime() - startNanos) / 1_000L;
      metrics.timer(MODEL_NAME).record(elapsedMicros, java.util.concurrent.TimeUnit.MICROSECONDS);
      float elapsedMs = elapsedMicros / 1_000.0f;

      LoadedPitchTypeModel serving = modelLoader.loadPitchType(routed.servingVersionId());
      logger.enqueue(
          new PredictionLogEvent(
              UUID.randomUUID(),
              requestAt,
              MODEL_NAME,
              serving.version(),
              routed.servingVersionId(),
              toLogRole(routed.servingRole()),
              serving.schemaHash(),
              serializeFeatures(req),
              serializePrediction(probs),
              elapsedMs,
              correlationId));

      // Shadow row fire-and-forget off the request path: the serving leg already returned, and the
      // shadow logs when it completes. These are the rows promotion evidence is built from, so this
      // leg is the point of the whole change rather than an extra.
      routed
          .shadowFuture()
          .ifPresent(
              shadowFut -> {
                long shadowVid = routed.shadowVersionId().orElseThrow();
                shadowFut.whenComplete(
                    (shadowProbs, ex) -> {
                      if (ex != null) {
                        return;
                      }
                      try {
                        LoadedPitchTypeModel shadowModel = modelLoader.loadPitchType(shadowVid);
                        logger.enqueue(
                            new PredictionLogEvent(
                                UUID.randomUUID(),
                                requestAt,
                                MODEL_NAME,
                                shadowModel.version(),
                                shadowVid,
                                PredictionLogEvent.Role.SHADOW,
                                shadowModel.schemaHash(),
                                serializeFeatures(req),
                                serializePrediction(shadowProbs),
                                elapsedMs,
                                correlationId));
                      } catch (RuntimeException logEx) {
                        // A throwing whenComplete action completes the DERIVED future
                        // exceptionally, and that future is discarded - so without this the failure
                        // vanishes with no log and no metric. These rows ARE rule 5's evidence, so
                        // a silently dropped one does not merely cost observability, it biases the
                        // sample the promotion gate will read.
                        metrics.incrementShadowDropped(MODEL_NAME, "log_failed");
                        log.warn("pitch-type shadow row dropped for version {}", shadowVid, logEx);
                      }
                    });
              });

      return new Served(probs, MODEL_NAME, serving.version(), ars.pitcherPriorN(), elapsedMicros);
    } catch (ResponseStatusException e) {
      // 503 is the designed no-champion / stale-prior answer, not a model fault. Counting it
      // against the model's error rate would make an unregistered model look broken.
      throw e;
    } catch (RuntimeException e) {
      metrics.incrementError(MODEL_NAME, role, e.getClass().getSimpleName());
      throw e;
    }
  }

  private Map<String, Double> predictWith(
      long versionId, FeaturePipelinePitchType.Request features) {
    try {
      return modelLoader.loadPitchType(versionId).predict(features);
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("pitch-type inference failed", e);
    }
  }

  private static PredictionLogEvent.Role toLogRole(Role role) {
    return switch (role) {
      case CHAMPION -> PredictionLogEvent.Role.CHAMPION;
      case CHALLENGER -> PredictionLogEvent.Role.CHALLENGER;
      case SHADOW -> PredictionLogEvent.Role.SHADOW;
    };
  }

  /**
   * The distribution as logged. NO argmax, deliberately: the pitch-outcome logger records a winner
   * because that head makes a top-1 claim, and this one does not. Writing an argmax here would put
   * the reading [183] forbids into the audit trail, where a later reader would take it as intended.
   */
  private String serializePrediction(Map<String, Double> probs) {
    try {
      return objectMapper.writeValueAsString(Map.of("probabilities", probs));
    } catch (Exception e) {
      throw new IllegalStateException("failed to serialize pitch-type prediction", e);
    }
  }

  /**
   * Wire DTO plus derived history into the pipeline's input record.
   *
   * <p>The SEQ features are not yet derived live and are passed as their declared cold-start
   * values. That is a stated gap rather than a silent default: prev-pitch derivation is outing
   * scoped and cheap (measured ~2ms) but is not built, and a fabricated sequence would be worse
   * than an honest cold start, which the model already handles natively.
   */
  private static FeaturePipelinePitchType.Request toPipelineRequest(
      PitchTypeRequest req, PitchTypeArsenalDeriver.Arsenal ars) {
    return new FeaturePipelinePitchType.Request(
        req.balls(),
        req.strikes(),
        req.outs(),
        req.inning(),
        req.baseState(),
        req.stand(),
        req.pThrows(),
        req.parkId(),
        req.timesThroughOrder(),
        req.atBatNumberInGame(),
        req.timesFacedToday(),
        ars.arsFf(),
        ars.arsSi(),
        ars.arsFc(),
        ars.arsSl(),
        ars.arsCu(),
        ars.arsCh(),
        ars.arsOff(),
        ars.arsFfByCount(),
        (int) ars.pitcherPriorN(),
        -1,
        -1,
        1,
        0);
  }

  /** The request as logged to {@code prediction_log.features}: the WIRE keys, flat. */
  String serializeFeatures(PitchTypeRequest req) {
    Map<String, Object> flat = new LinkedHashMap<>();
    flat.put("balls", req.balls());
    flat.put("strikes", req.strikes());
    flat.put("outs", req.outs());
    flat.put("inning", req.inning());
    flat.put("baseState", req.baseState());
    flat.put("stand", req.stand());
    flat.put("pThrows", req.pThrows());
    flat.put("parkId", req.parkId());
    flat.put("timesThroughOrder", req.timesThroughOrder());
    flat.put("atBatNumberInGame", req.atBatNumberInGame());
    flat.put("timesFacedToday", req.timesFacedToday());
    try {
      return objectMapper.writeValueAsString(flat);
    } catch (Exception e) {
      throw new IllegalStateException("failed to serialize pitch-type features", e);
    }
  }
}
