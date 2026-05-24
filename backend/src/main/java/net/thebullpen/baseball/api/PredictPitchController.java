package net.thebullpen.baseball.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Timer;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.thebullpen.baseball.api.dto.PitchPredictionResponse;
import net.thebullpen.baseball.api.dto.PitchRequest;
import net.thebullpen.baseball.inference.AsyncPredictionLogger;
import net.thebullpen.baseball.inference.FeaturePipelinePitchPost;
import net.thebullpen.baseball.inference.FeaturePipelinePitchPre;
import net.thebullpen.baseball.inference.Head;
import net.thebullpen.baseball.inference.InferenceMetrics;
import net.thebullpen.baseball.inference.PitchInferenceService;
import net.thebullpen.baseball.inference.PredictionLogEvent;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * {@code POST /v1/predict/pitch[?head=pre|post]} — Phase 2a.8 + 2b.3.
 *
 * <p>Returns the calibrated 5-class distribution synchronously and enqueues a {@link
 * PredictionLogEvent} for the async batch logger. Defaults to {@link Head#PRE} so 2a.8 callers keep
 * working unchanged; {@code head=post} requires all 10 Tier 4 fields on the body and routes to the
 * separately-registered {@code pitch_outcome_post} model (decision [35]).
 *
 * <p>{@link ConditionalOnBean} keeps this controller out of the context when the {@link
 * PitchInferenceService} bean is missing — i.e. when the production ONNX artifact hasn't been
 * trained yet, so toy-only tests stay green.
 */
@RestController
@RequestMapping("/v1/predict")
@Profile("api")
@ConditionalOnBean(PitchInferenceService.class)
public class PredictPitchController {

  private static final String ROLE = "champion";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final PitchInferenceService inference;
  private final InferenceMetrics metrics;
  private final AsyncPredictionLogger logger;
  private final String preFeatureSchemaHash;

  public PredictPitchController(
      PitchInferenceService inference, InferenceMetrics metrics, AsyncPredictionLogger logger) {
    this.inference = inference;
    this.metrics = metrics;
    this.logger = logger;
    this.preFeatureSchemaHash = inference.pipelineSpec().schemaHash();
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
      if (!inference.isPostHeadAvailable()) {
        throw new ResponseStatusException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "post head not loaded — pitch_outcome_post/v1 artifacts missing on the server");
      }
    }

    Timer.Sample sample = metrics.startTimer();
    Instant requestAt = Instant.now();
    String modelName =
        head == Head.PRE ? PitchInferenceService.MODEL_NAME : PitchInferenceService.POST_MODEL_NAME;
    String modelVersion =
        head == Head.PRE
            ? PitchInferenceService.MODEL_VERSION
            : PitchInferenceService.POST_MODEL_VERSION;
    try {
      Map<String, Double> probs =
          head == Head.PRE
              ? inference.predictPre(toPrePipelineRequest(req))
              : inference.predictPost(toPostPipelineRequest(req));
      long elapsedNanos = sample.stop(metrics.timer(modelName));
      metrics.incrementPrediction(modelName, ROLE);
      String correlationId = MDC.get("correlation_id");
      String winner = argmax(probs);
      String schemaHash =
          head == Head.PRE ? preFeatureSchemaHash : inference.postPipelineSpec().schemaHash();

      logger.enqueue(
          new PredictionLogEvent(
              UUID.randomUUID(),
              requestAt,
              modelName,
              modelVersion,
              PredictionLogEvent.Role.CHAMPION,
              schemaHash,
              serializeFeatures(req),
              serializePrediction(probs, winner),
              elapsedNanos / 1_000_000.0f,
              correlationId));

      return new PitchPredictionResponse(
          probs, winner, modelName, modelVersion, elapsedNanos / 1_000L, correlationId);
    } catch (Exception e) {
      metrics.incrementError(modelName, e.getClass().getSimpleName());
      throw e;
    }
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
