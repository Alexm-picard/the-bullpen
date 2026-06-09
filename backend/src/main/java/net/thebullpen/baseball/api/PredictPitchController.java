package net.thebullpen.baseball.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Timer;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.thebullpen.baseball.api.dto.PitchPredictionResponse;
import net.thebullpen.baseball.api.dto.PitchRequest;
import net.thebullpen.baseball.inference.AsyncPredictionLogger;
import net.thebullpen.baseball.inference.FeaturePipelinePitchPost;
import net.thebullpen.baseball.inference.FeaturePipelinePitchPre;
import net.thebullpen.baseball.inference.Head;
import net.thebullpen.baseball.inference.InferenceMetrics;
import net.thebullpen.baseball.inference.InferenceRouter;
import net.thebullpen.baseball.inference.LoadedPitchModel;
import net.thebullpen.baseball.inference.ModelLoader;
import net.thebullpen.baseball.inference.PitchInferenceService;
import net.thebullpen.baseball.inference.PredictionLogEvent;
import net.thebullpen.baseball.inference.RoutedPrediction;
import net.thebullpen.baseball.inference.routing.Role;
import net.thebullpen.baseball.registry.RegistryService;
import net.thebullpen.baseball.registry.dto.ModelVersion;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * {@code POST /v1/predict/pitch[?head=pre|post]} - Phase 2a.8 + 2b.3, rewired through the
 * ML-systems wrapper (W1, fixes BUG-2).
 *
 * <p>Routing goes through {@link InferenceRouter} under two SEPARATE registered model names (rule
 * 9): {@code pitch_outcome_pre} for {@code head=pre} (the default) and {@code pitch_outcome_post}
 * for {@code head=post}. Each head is its own registry entry, its own A/B routing row, its own
 * champion + shadow. The champion serves the user; any SHADOW challenger runs in parallel and is
 * logged to {@code prediction_logs} with the routed {@code servingVersionId} as the {@code
 * model_version_id} FK - so per-version drift attribution joins precisely against the registry.
 *
 * <p>Dispatch matrix per head (mirrors {@link PredictAllParksController}):
 *
 * <ul>
 *   <li>An A/B routing config present -> champion serves, any challenger runs in shadow and is
 *       logged.
 *   <li>No routing config -> serve the registry's LIVE champion directly; {@code 503} when none is
 *       live (decision: serve-live-champion-else-503), NOT 404.
 * </ul>
 *
 * <p>The legacy artifact-direct {@link PitchInferenceService} bean is kept as a flagged dev
 * fallback (locked decision): set {@code bullpen.inference.pitch.dev-direct-serving=true} to serve
 * straight off the on-disk artifact when no registry champion exists, for local no-registry dev.
 * The default ({@code false}) is the router path, so prod + CI go through the registry. The bean is
 * still {@link Optional} so the controller mounts even when no on-disk artifact exists.
 *
 * <p>{@code X-Bullpen-Game-Id} is not part of the 2a.8 pitch contract, so bucket assignment is
 * per-request (random) here - fine for dev-curl / single-prediction traffic. The live poller
 * ({@link net.thebullpen.baseball.ingest.LivePitchPredictor}) drives consistent per-game bucketing
 * on its own path.
 *
 * <p>Rule 7 note: the feature-schema hash registration gate is unchanged - the schema hash stamped
 * on a log row comes from the loaded model's own snapshot contract, never recomputed here. The ONNX
 * I/O-name surface is resolved per-session inside {@link LoadedPitchModel} and is intentionally NOT
 * part of that hash (it covers the feature pipeline, not graph tensor names).
 */
@RestController
@RequestMapping("/v1/predict")
@Profile("api")
public class PredictPitchController {

  static final String PRE_MODEL_NAME = "pitch_outcome_pre";
  static final String POST_MODEL_NAME = "pitch_outcome_post";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final ModelLoader modelLoader;
  private final InferenceRouter router;
  private final RegistryService registry;
  private final AsyncPredictionLogger logger;
  private final InferenceMetrics metrics;
  private final Optional<PitchInferenceService> devInference;
  private final boolean devDirectServing;

  public PredictPitchController(
      ModelLoader modelLoader,
      InferenceRouter router,
      RegistryService registry,
      AsyncPredictionLogger logger,
      InferenceMetrics metrics,
      Optional<PitchInferenceService> devInference,
      @Value("${bullpen.inference.pitch.dev-direct-serving:false}") boolean devDirectServing) {
    this.modelLoader = modelLoader;
    this.router = router;
    this.registry = registry;
    this.logger = logger;
    this.metrics = metrics;
    this.devInference = devInference;
    this.devDirectServing = devDirectServing;
  }

  @PostMapping("/pitch")
  public PitchPredictionResponse predict(
      @RequestParam(name = "head", defaultValue = "pre") String headRaw,
      @Valid @RequestBody PitchRequest req)
      throws Exception {
    Head head;
    try {
      head = Head.parse(headRaw);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
    }

    if (head == Head.POST) {
      List<String> missing = missingTier4Fields(req);
      if (!missing.isEmpty()) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "head=post requires Tier 4 fields; missing: " + String.join(", ", missing));
      }
    }

    String modelName = head == Head.PRE ? PRE_MODEL_NAME : POST_MODEL_NAME;
    Timer.Sample sample = metrics.startTimer();
    Instant requestAt = Instant.now();
    long gameId = ThreadLocalRandom.current().nextLong();
    String correlationId = MDC.get("correlation_id");

    try {
      RoutedPrediction<Map<String, Double>> routed =
          router.route(
              modelName,
              gameId,
              versionId -> predict(head, versionId, req),
              () -> legacyFallback(head, req));

      long elapsedNanos = sample.stop(metrics.timer(modelName));
      metrics.incrementPrediction(modelName, routed.servingRole().name().toLowerCase(Locale.ROOT));
      float elapsedMs = elapsedNanos / 1_000_000.0f;

      Map<String, Double> probs = routed.servingResponse();
      String winner = argmax(probs);

      // Resolve the serving version's identity for the log FK + response label. When the legacy
      // fallback served (servingVersionId == -1), the dev-direct bean produced it: there is no
      // registry row, so the FK stays null and the label is the bean's hardcoded version. Otherwise
      // the ModelLoader-resolved model knows its own version + schema hash.
      boolean legacyServed = routed.servingVersionId() == -1L;
      String servingVersion;
      String servingSchemaHash;
      Long servingVersionFk;
      if (legacyServed) {
        servingVersion =
            head == Head.PRE
                ? PitchInferenceService.MODEL_VERSION
                : PitchInferenceService.POST_MODEL_VERSION;
        servingSchemaHash = legacySchemaHash(head);
        servingVersionFk = null;
      } else {
        LoadedPitchModel servingModel = load(head, routed.servingVersionId());
        servingVersion = servingModel.version();
        servingSchemaHash = servingModel.schemaHash();
        servingVersionFk = routed.servingVersionId();
      }

      logger.enqueue(
          new PredictionLogEvent(
              UUID.randomUUID(),
              requestAt,
              modelName,
              servingVersion,
              servingVersionFk,
              toLogRole(routed.servingRole()),
              servingSchemaHash,
              serializeFeatures(req),
              serializePrediction(probs, winner),
              elapsedMs,
              correlationId));

      if (routed.hasShadowRow()) {
        long shadowVid = routed.shadowVersionId().orElseThrow();
        LoadedPitchModel shadowModel = load(head, shadowVid);
        Map<String, Double> shadowProbs = routed.shadowResponse().orElseThrow();
        logger.enqueue(
            new PredictionLogEvent(
                UUID.randomUUID(),
                requestAt,
                modelName,
                shadowModel.version(),
                shadowVid,
                PredictionLogEvent.Role.SHADOW,
                shadowModel.schemaHash(),
                serializeFeatures(req),
                serializePrediction(shadowProbs, argmax(shadowProbs)),
                elapsedMs,
                correlationId));
      }

      return new PitchPredictionResponse(
          probs, winner, modelName, servingVersion, elapsedNanos / 1_000L, correlationId);
    } catch (ResponseStatusException e) {
      throw e; // 503 (no champion) / client errors pass through untouched
    } catch (Exception e) {
      metrics.incrementError(modelName, e.getClass().getSimpleName());
      throw e;
    }
  }

  /**
   * The router's per-version predictor closure: load the registered version's {@link
   * LoadedPitchModel} and run the head it carries.
   */
  private Map<String, Double> predict(Head head, long versionId, PitchRequest req) {
    try {
      LoadedPitchModel model = load(head, versionId);
      return head == Head.PRE
          ? model.predictPre(toPrePipelineRequest(req))
          : model.predictPost(toPostPipelineRequest(req));
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private LoadedPitchModel load(Head head, long versionId) {
    return head == Head.PRE
        ? modelLoader.loadPitchPre(versionId)
        : modelLoader.loadPitchPost(versionId);
  }

  /**
   * No-routing-config fallback. Serves the registry's LIVE champion for this head; when none is
   * live, serves the on-disk artifact via the dev-direct bean IF {@code dev-direct-serving} is on,
   * otherwise 503 (serve-live-champion-else-503). 503, never 404 - the route always exists; it is
   * the champion that may be absent.
   */
  private Map<String, Double> legacyFallback(Head head, PitchRequest req) {
    String modelName = head == Head.PRE ? PRE_MODEL_NAME : POST_MODEL_NAME;
    Optional<ModelVersion> champion = registry.findChampion(modelName);
    if (champion.isPresent()) {
      return predict(head, champion.get().id(), req);
    }
    if (devDirectServing && devInference.isPresent()) {
      return devDirectPredict(head, req);
    }
    throw new ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE,
        modelName
            + " has no LIVE champion and no A/B routing config; register + promote a model first"
            + " (or set bullpen.inference.pitch.dev-direct-serving=true for local artifact-direct"
            + " dev)");
  }

  /** Artifact-direct serving via the flagged dev bean (local no-registry dev only). */
  private Map<String, Double> devDirectPredict(Head head, PitchRequest req) {
    PitchInferenceService bean = devInference.orElseThrow();
    try {
      if (head == Head.PRE) {
        return bean.predictPre(toPrePipelineRequest(req));
      }
      if (!bean.isPostHeadAvailable()) {
        throw new ResponseStatusException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "post head not loaded - pitch_outcome_post/v1 artifacts missing on the server");
      }
      return bean.predictPost(toPostPipelineRequest(req));
    } catch (ResponseStatusException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Schema hash for a legacy-served (dev-direct) prediction. The bean knows its own pipeline spec;
   * post may not be loaded, in which case there is no hash to report (empty string).
   */
  private String legacySchemaHash(Head head) {
    PitchInferenceService bean = devInference.orElseThrow();
    if (head == Head.PRE) {
      return bean.pipelineSpec().schemaHash();
    }
    return bean.isPostHeadAvailable() ? bean.postPipelineSpec().schemaHash() : "";
  }

  /**
   * Returns the list of Tier 4 field names that are null on the request (post-head precondition).
   */
  private static List<String> missingTier4Fields(PitchRequest req) {
    List<String> missing = new ArrayList<>();
    if (req.pitchType() == null || req.pitchType().isBlank()) missing.add("pitchType");
    if (req.releaseSpeedMph() == null) missing.add("releaseSpeedMph");
    if (req.plateXIn() == null) missing.add("plateXIn");
    if (req.plateZIn() == null) missing.add("plateZIn");
    if (req.pfxXIn() == null) missing.add("pfxXIn");
    if (req.pfxZIn() == null) missing.add("pfxZIn");
    if (req.spinRateRpm() == null) missing.add("spinRateRpm");
    if (req.spinAxisDeg() == null) missing.add("spinAxisDeg");
    if (req.releasePosXIn() == null) missing.add("releasePosXIn");
    if (req.releasePosZIn() == null) missing.add("releasePosZIn");
    return missing;
  }

  private static FeaturePipelinePitchPre.Request toPrePipelineRequest(PitchRequest req) {
    return new FeaturePipelinePitchPre.Request(
        req.countBalls(),
        req.countStrikes(),
        req.outs(),
        req.inning(),
        req.baseState(),
        req.scoreDiff(),
        req.dow(),
        req.pitcherThrows(),
        req.batterStand(),
        req.parkId(),
        req.pitcherId(),
        req.batterId(),
        req.pitcherPitchesLast28d(),
        req.pitcherPitchesInGame(),
        req.daysSinceLastAppearance(),
        req.pitcherStrikeRate28d(),
        req.pitcherSwstrikeRate28d(),
        req.pitcherInplayRate28d(),
        req.pitcherStrikeRateStd(),
        req.batterStrikeRate28d(),
        req.batterInplayRate28d(),
        req.batterBallRate28d(),
        req.batterInplayRateStd());
  }

  private static FeaturePipelinePitchPost.Request toPostPipelineRequest(PitchRequest req) {
    return new FeaturePipelinePitchPost.Request(
        req.countBalls(),
        req.countStrikes(),
        req.outs(),
        req.inning(),
        req.baseState(),
        req.scoreDiff(),
        req.dow(),
        req.pitcherThrows(),
        req.batterStand(),
        req.parkId(),
        req.pitcherId(),
        req.batterId(),
        req.pitcherPitchesLast28d(),
        req.pitcherPitchesInGame(),
        req.daysSinceLastAppearance(),
        req.pitcherStrikeRate28d(),
        req.pitcherSwstrikeRate28d(),
        req.pitcherInplayRate28d(),
        req.pitcherStrikeRateStd(),
        req.batterStrikeRate28d(),
        req.batterInplayRate28d(),
        req.batterBallRate28d(),
        req.batterInplayRateStd(),
        req.pitchType(),
        req.releaseSpeedMph(),
        req.plateXIn(),
        req.plateZIn(),
        req.pfxXIn(),
        req.pfxZIn(),
        req.spinRateRpm(),
        req.spinAxisDeg(),
        req.releasePosXIn(),
        req.releasePosZIn());
  }

  private static PredictionLogEvent.Role toLogRole(Role role) {
    return switch (role) {
      case CHAMPION -> PredictionLogEvent.Role.CHAMPION;
      case CHALLENGER -> PredictionLogEvent.Role.CHALLENGER;
      case SHADOW -> PredictionLogEvent.Role.SHADOW;
    };
  }

  private static String argmax(Map<String, Double> probs) {
    String best = null;
    double bestVal = Double.NEGATIVE_INFINITY;
    for (Map.Entry<String, Double> entry : probs.entrySet()) {
      if (entry.getValue() > bestVal) {
        best = entry.getKey();
        bestVal = entry.getValue();
      }
    }
    return best == null ? "unknown" : best;
  }

  private static String serializeFeatures(PitchRequest req) throws JsonProcessingException {
    return MAPPER.writeValueAsString(req);
  }

  private static String serializePrediction(Map<String, Double> probs, String winner)
      throws JsonProcessingException {
    return MAPPER.writeValueAsString(Map.of("probabilities", probs, "winner", winner));
  }
}
