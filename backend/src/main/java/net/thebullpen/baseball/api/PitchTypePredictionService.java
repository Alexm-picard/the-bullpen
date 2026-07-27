package net.thebullpen.baseball.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import net.thebullpen.baseball.api.dto.PitchTypeRequest;
import net.thebullpen.baseball.data.PitchTypeArsenalDeriver;
import net.thebullpen.baseball.inference.FeaturePipelinePitchType;
import net.thebullpen.baseball.inference.LoadedPitchTypeModel;
import net.thebullpen.baseball.inference.ModelLoader;
import net.thebullpen.baseball.registry.RegistryService;
import net.thebullpen.baseball.registry.dto.ModelVersion;
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
 * <p>SERVE-LIVE-CHAMPION-ELSE-503, matching the pitch-outcome heads. {@code pitch_type_pre} has no
 * champion today, so every call takes that path by design; 503 rather than 404 because the route
 * exists and it is the champion that is absent.
 */
@Service
@Profile("api")
@ConditionalOnProperty(name = "bullpen.clickhouse.enabled", havingValue = "true")
public class PitchTypePredictionService {

  public static final String MODEL_NAME = "pitch_type_pre";

  private final ModelLoader modelLoader;
  private final RegistryService registry;
  private final PitchTypeArsenalDeriver arsenal;
  private final ObjectMapper objectMapper;

  public PitchTypePredictionService(
      ModelLoader modelLoader,
      RegistryService registry,
      PitchTypeArsenalDeriver arsenal,
      ObjectMapper objectMapper) {
    this.modelLoader = modelLoader;
    this.registry = registry;
    this.arsenal = arsenal;
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
    long startNanos = System.nanoTime();

    ModelVersion champion =
        registry
            .findChampion(MODEL_NAME)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        MODEL_NAME
                            + " has no LIVE champion; register and promote a model first. 503"
                            + " rather than 404: the route exists, the champion does not."));

    // Derived server-side, career-expanding, strictly before this pitch. The deriver refuses
    // rather than guessing when the snapshot is missing or stale, and that refusal surfaces as a
    // 503 too: a calibrated prior computed over the wrong history is worse than no answer, which
    // is the whole reason this model is promoted on calibration.
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
    Map<String, Double> probs;
    try {
      LoadedPitchTypeModel model = modelLoader.loadPitchType(champion.id());
      probs = model.predict(features);
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("pitch-type inference failed", e);
    }

    return new Served(
        probs,
        MODEL_NAME,
        champion.version(),
        ars.pitcherPriorN(),
        (System.nanoTime() - startNanos) / 1_000L);
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

  Optional<ModelVersion> champion() {
    return registry.findChampion(MODEL_NAME);
  }
}
