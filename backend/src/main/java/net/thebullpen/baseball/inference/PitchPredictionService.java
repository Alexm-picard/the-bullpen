package net.thebullpen.baseball.inference;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Timer;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.thebullpen.baseball.api.dto.PitchRequest;
import net.thebullpen.baseball.config.InferenceProperties;
import net.thebullpen.baseball.inference.routing.Role;
import net.thebullpen.baseball.registry.RegistryService;
import net.thebullpen.baseball.registry.dto.ModelVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Pitch-prediction orchestration behind {@code POST /v1/predict/pitch} - extracted from {@code
 * PredictPitchController} so the controller stays a thin HTTP adapter (parse {@code head}, validate
 * the Tier-4 precondition, build the response DTO) and all the ML-systems wiring lives in {@code
 * inference/}. This owns routing through the {@link InferenceRouter} (rule 9: {@code
 * pitch_outcome_pre} and {@code pitch_outcome_post} are separate registered names), the champion +
 * shadow dual-logging, argmax, feature/prediction serialization, metrics, and the
 * serve-live-champion-else-503 fallback (including the flagged dev-direct artifact path).
 *
 * <p>Behaviour is identical to the pre-extraction controller: this is a move, not a rewrite. See
 * {@code PredictPitchController}'s Javadoc for the dispatch matrix and the rule-7 schema-hash note.
 */
@Service
@Profile("api")
public class PitchPredictionService {

  private static final Logger log = LoggerFactory.getLogger(PitchPredictionService.class);

  public static final String PRE_MODEL_NAME = "pitch_outcome_pre";
  public static final String POST_MODEL_NAME = "pitch_outcome_post";

  private final ObjectMapper objectMapper;
  private final ModelLoader modelLoader;
  private final InferenceRouter router;
  private final RegistryService registry;
  private final AsyncPredictionLogger logger;
  private final InferenceMetrics metrics;
  private final Optional<PitchInferenceService> devInference;
  private final boolean devDirectServing;

  public PitchPredictionService(
      ModelLoader modelLoader,
      InferenceRouter router,
      RegistryService registry,
      AsyncPredictionLogger logger,
      InferenceMetrics metrics,
      Optional<PitchInferenceService> devInference,
      InferenceProperties props,
      ObjectMapper objectMapper) {
    this.modelLoader = modelLoader;
    this.router = router;
    this.registry = registry;
    this.logger = logger;
    this.metrics = metrics;
    this.devInference = devInference;
    this.devDirectServing = props.pitch().devDirectServing();
    this.objectMapper = objectMapper;
  }

  /** The served pitch prediction plus the identity the controller needs to build its response. */
  public record Served(
      Map<String, Double> probabilities,
      String winner,
      String modelName,
      String servingVersion,
      long elapsedMicros) {}

  /**
   * Route + serve one pitch prediction for {@code head}, log the champion (and any shadow), and
   * return the served distribution + identity. {@code head} parsing and the Tier-4 precondition are
   * the caller's (HTTP) concern. The prediction-log {@code request_at} is captured here just after
   * the timer starts (as it was before the extraction). Rethrows a {@link ResponseStatusException}
   * (503 / client error) untouched; any other failure is counted against the model's error metric
   * and rethrown.
   */
  public Served predict(Head head, PitchRequest req, String correlationId) throws Exception {
    String modelName = head == Head.PRE ? PRE_MODEL_NAME : POST_MODEL_NAME;
    Timer.Sample sample = metrics.startTimer();
    Instant requestAt = Instant.now();
    long gameId = ThreadLocalRandom.current().nextLong();

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

      // Shadow row logged FIRE-AND-FORGET off the request path (F1.4): the champion already
      // returned; the shadow logs its row when it completes (the router surfaced any failure).
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
                        LoadedPitchModel shadowModel = load(head, shadowVid);
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
                      } catch (JsonProcessingException je) {
                        log.warn(
                            "shadow row serialization failed for {}: {}", modelName, je.toString());
                      }
                    });
              });

      return new Served(probs, winner, modelName, servingVersion, elapsedNanos / 1_000L);
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

  static String argmax(Map<String, Double> probs) {
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

  private String serializeFeatures(PitchRequest req) throws JsonProcessingException {
    return objectMapper.writeValueAsString(req);
  }

  private String serializePrediction(Map<String, Double> probs, String winner)
      throws JsonProcessingException {
    return objectMapper.writeValueAsString(Map.of("probabilities", probs, "winner", winner));
  }
}
