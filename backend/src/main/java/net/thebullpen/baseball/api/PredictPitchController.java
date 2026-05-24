package net.thebullpen.baseball.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Timer;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import net.thebullpen.baseball.api.dto.PitchPredictionResponse;
import net.thebullpen.baseball.api.dto.PitchRequest;
import net.thebullpen.baseball.inference.AsyncPredictionLogger;
import net.thebullpen.baseball.inference.FeaturePipelinePitchPre;
import net.thebullpen.baseball.inference.InferenceMetrics;
import net.thebullpen.baseball.inference.PitchInferenceService;
import net.thebullpen.baseball.inference.PredictionLogEvent;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /v1/predict/pitch} — Phase 2a.8.
 *
 * <p>Returns the calibrated 5-class distribution synchronously and enqueues a {@link
 * PredictionLogEvent} for the async batch logger (same backbone as the toy endpoint).
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
  private final String featureSchemaHash;

  public PredictPitchController(
      PitchInferenceService inference, InferenceMetrics metrics, AsyncPredictionLogger logger) {
    this.inference = inference;
    this.metrics = metrics;
    this.logger = logger;
    this.featureSchemaHash = inference.pipelineSpec().schemaHash();
  }

  @PostMapping("/pitch")
  public PitchPredictionResponse predict(@Valid @RequestBody PitchRequest req) throws Exception {
    Timer.Sample sample = metrics.startTimer();
    Instant requestAt = Instant.now();
    try {
      Map<String, Double> probs = inference.predict(toPipelineRequest(req));
      long elapsedNanos = sample.stop(metrics.timer(PitchInferenceService.MODEL_NAME));
      metrics.incrementPrediction(PitchInferenceService.MODEL_NAME, ROLE);
      String correlationId = MDC.get("correlation_id");
      String winner = argmax(probs);

      logger.enqueue(
          new PredictionLogEvent(
              UUID.randomUUID(),
              requestAt,
              PitchInferenceService.MODEL_NAME,
              PitchInferenceService.MODEL_VERSION,
              PredictionLogEvent.Role.CHAMPION,
              featureSchemaHash,
              serializeFeatures(req),
              serializePrediction(probs, winner),
              elapsedNanos / 1_000_000.0f,
              correlationId));

      return new PitchPredictionResponse(
          probs,
          winner,
          PitchInferenceService.MODEL_NAME,
          PitchInferenceService.MODEL_VERSION,
          elapsedNanos / 1_000L,
          correlationId);
    } catch (Exception e) {
      metrics.incrementError(PitchInferenceService.MODEL_NAME, e.getClass().getSimpleName());
      throw e;
    }
  }

  private static FeaturePipelinePitchPre.Request toPipelineRequest(PitchRequest req) {
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
