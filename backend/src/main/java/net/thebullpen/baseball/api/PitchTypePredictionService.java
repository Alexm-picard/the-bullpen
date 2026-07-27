package net.thebullpen.baseball.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Timer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.thebullpen.baseball.api.dto.PitchTypeRequest;
import net.thebullpen.baseball.data.PitchTypeArsenalDeriver;
import net.thebullpen.baseball.data.PitcherOutingSequence;
import net.thebullpen.baseball.inference.AsyncPredictionLogger;
import net.thebullpen.baseball.inference.FeaturePipelinePitchType;
import net.thebullpen.baseball.inference.InferenceMetrics;
import net.thebullpen.baseball.inference.InferenceRouter;
import net.thebullpen.baseball.inference.LoadedPitchTypeModel;
import net.thebullpen.baseball.inference.ModelLoader;
import net.thebullpen.baseball.inference.PredictionLogEvent;
import net.thebullpen.baseball.inference.RoutedPrediction;
import net.thebullpen.baseball.inference.routing.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
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
 * available. Routing buys the correct role and the shadow dual-log MECHANISM. Note the scope: rows
 * from this HTTP endpoint carry NULL live keys and are excluded from every evidence reader by
 * explicit filter, so they are promotion evidence for nothing - the mechanism becomes
 * evidence-bearing only when a live-ingest poller leg logs through it with real live keys.
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
 * name. Later promotions need evidence rows with real live keys - the poller leg or the offline
 * gate, never this endpoint's rows (see the note on {@link #predict}).
 *
 * <p>SERVE-LIVE-CHAMPION-ELSE-503 otherwise, matching the pitch-outcome heads: 503 rather than 404
 * because the route exists and it is the model that is absent. {@code pitch_type_pre} has neither a
 * champion nor a routing config today, so every call takes that path until one is registered.
 */
@Service
@Profile("api")
@ConditionalOnProperty(name = "bullpen.clickhouse.enabled", havingValue = "true")
public class PitchTypePredictionService {

  private static final Logger log = LoggerFactory.getLogger(PitchTypePredictionService.class);

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

  /**
   * A8, THE LOGGING CONTRACT OF THIS METHOD: HTTP-path rows carry NULL {@code (gameId, atBatIndex,
   * pitchNumber)} in {@code prediction_log}, matching the pitch-outcome precedent - an arbitrary
   * API caller must not be able to inject rows that join to real {@code pitches_live} pitches. Both
   * evidence readers exclude NULL-keyed rows by explicit filter, so NOTHING this method logs enters
   * any promotion gate.
   *
   * <p>The consequence is worth stating rather than discovering: until a live-ingest poller leg
   * exists for pitch-type, its rows are UNPAIRED - no truth join, no retrospective scorecard for
   * this family. That does not affect the first-champion path (the offline gate, [182]/#334), but
   * ECE evidence for any LATER promotion has to come from the poller leg or the offline gate, never
   * from here. Note also that the logged {@code features} carry the 11 wire keys only: ARS and SEQ
   * are server-derived and absent, so a logged row does NOT determine the scored vector and cannot
   * be replayed.
   */
  public Served predict(PitchTypeRequest req) {
    Instant requestAt = Instant.now();
    String correlationId = MDC.get("correlation_id");

    // Derived server-side, career-expanding, strictly before this pitch. Runs BEFORE routing so a
    // missing or stale prior refuses once rather than once per routed leg. The deriver refuses
    // instead of guessing: a calibrated prior computed over the wrong history is worse than no
    // answer, which is the whole reason this model is promoted on calibration.
    PitchTypeArsenalDeriver.Arsenal ars;
    PitcherOutingSequence seq;
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
              LocalDate.now(ZoneId.of("America/New_York")));
      // SEQ derived here too, once before routing, for the same pairing reason as the arsenal:
      // both routed legs must score the IDENTICAL feature vector, or champion and shadow rows
      // silently unpair. Unlike the arsenal it has nothing to refuse over - an empty outing is
      // the declared OUTING_START state the model trained on.
      seq =
          arsenal.deriveSequence(
              req.pitcherId(), req.gameId(), req.atBatIndex(), req.pitchNumber());
    } catch (PitchTypeArsenalDeriver.PriorUnavailable e) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage(), e);
    } catch (RuntimeException e) {
      // A ClickHouse failure on the derivation reads is a real model-path failure and must be
      // COUNTED, not just surfaced as an HTTP 500. The "unknown" role bucket exists precisely for
      // failures before routing has chosen a role; without this the endpoint could be fully down
      // while its error series stayed flat.
      metrics.incrementError(MODEL_NAME, "unknown", e.getClass().getSimpleName());
      throw e;
    }
    FeaturePipelinePitchType.Request features = toPipelineRequest(req, ars, seq);

    // Timed from HERE, after the FOUR ClickHouse reads above (two arsenal, two sequence).
    // latency_ms feeds the Ops dashboard's p50/p95/p99 grouped by (model_name, model_version)
    // alongside the pitch-outcome heads, whose timers cover routing plus inference with no DB
    // call on the path - including the derivation reads would make pitch_type read an order of
    // magnitude slower in the same column for reasons that have nothing to do with inference.
    Timer.Sample sample = metrics.startTimer();
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

      role = routed.servingRole().name().toLowerCase(Locale.ROOT);
      metrics.incrementPrediction(MODEL_NAME, role);
      Map<String, Double> probs = routed.servingResponse();
      long elapsedNanos = sample.stop(metrics.timer(MODEL_NAME));
      long elapsedMicros = elapsedNanos / 1_000L;
      float elapsedMs = elapsedNanos / 1_000_000.0f;

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
      // shadow logs when it completes. WHAT THIS BUYS IS THE MECHANISM AND THE CORRECT ROLE - NOT
      // promotion evidence. Rows from THIS endpoint carry NULL live keys (the A8 contract on
      // predict()), and both evidence readers exclude them by explicit game_id IS NOT NULL filter,
      // so nothing logged here enters any gate. Evidence rows arrive when a live-ingest poller leg
      // supplies real live keys; this code is what will log them correctly when it does. Do NOT
      // "fix" an evidence shortfall by logging the caller-supplied live keys - that is precisely
      // the injection the NULL keys exist to prevent, and it would poison the truth-joined sample
      // a real promotion gate reads.
      routed
          .shadowFuture()
          .ifPresent(
              shadowFut -> {
                long shadowVid = routed.shadowVersionId().orElseThrow();
                shadowFut.whenComplete(
                    (shadowProbs, ex) -> {
                      if (ex != null) {
                        // Not a silent hole: the router's onShadowComplete already counted this
                        // as shadow_dropped{timeout|defect|degraded}. This callback only owns the
                        // LOGGING failure below.
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
                        // vanishes with no log and no metric. Today that costs observability only
                        // (HTTP rows enter no gate - see the A8 note on predict()); once a poller
                        // leg logs rows with real live keys through this same path, a silent drop
                        // WOULD bias the sample a promotion gate reads, which is why the counter
                        // ships now rather than being retrofitted then.
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
   * <p>SEQ is derived LIVE from {@code pitches_live} (outing-scoped, ~2ms), mirroring {@code
   * compute_pitch_type_state.sql}: previous two labeled pitch types as y7 ints with the fold
   * applied, {@code -1} at outing start, and a labeled-only pitch count. An empty outing yields
   * exactly the cold-start values this method used to hardcode - {@code
   * PitcherOutingSequence.OUTING_START} - so the model's declared cold-start state is unchanged;
   * what changed is that a mid-outing request now carries the real sequence instead of pretending
   * every pitch is the first.
   */
  private static FeaturePipelinePitchType.Request toPipelineRequest(
      PitchTypeRequest req, PitchTypeArsenalDeriver.Arsenal ars, PitcherOutingSequence seq) {
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
        seq.prev1PtI(),
        seq.prev2PtI(),
        seq.prev1Missing(),
        seq.pitchesIntoOuting());
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
