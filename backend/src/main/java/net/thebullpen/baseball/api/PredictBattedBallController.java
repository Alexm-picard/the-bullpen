package net.thebullpen.baseball.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Timer;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.UUID;
import net.thebullpen.baseball.api.dto.BattedBallRequest;
import net.thebullpen.baseball.api.dto.PredictionResponse;
import net.thebullpen.baseball.inference.AsyncPredictionLogger;
import net.thebullpen.baseball.inference.InferenceMetrics;
import net.thebullpen.baseball.inference.PredictionLogEvent;
import net.thebullpen.baseball.inference.ToyBattedBallInference;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /v1/predict/batted-ball — Phase 1.5 + 1.7.
 *
 * <p>Returns the prediction synchronously and enqueues a {@link PredictionLogEvent} for the async
 * batch logger. Dropping on overload is the contract — see {@link AsyncPredictionLogger}.
 */
@RestController
@RequestMapping("/v1/predict")
@Profile("api")
public class PredictBattedBallController {

  private static final String ROLE = "champion";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final ToyBattedBallInference inference;
  private final InferenceMetrics metrics;
  private final AsyncPredictionLogger logger;
  private final String featureSchemaHash;

  public PredictBattedBallController(
      ToyBattedBallInference inference, InferenceMetrics metrics, AsyncPredictionLogger logger) {
    this.inference = inference;
    this.metrics = metrics;
    this.logger = logger;
    this.featureSchemaHash = inference.pipelineSpec().schemaHash();
  }

  @PostMapping("/batted-ball")
  public PredictionResponse predict(@Valid @RequestBody BattedBallRequest req) throws Exception {
    Timer.Sample sample = metrics.startTimer();
    Instant requestAt = Instant.now();
    try {
      float prob =
          inference.predict(
              req.launchSpeedMph(),
              req.launchAngleDeg(),
              req.releaseSpeedMph(),
              req.parkId(),
              req.stand());
      long elapsedNanos = sample.stop(metrics.timer(ToyBattedBallInference.MODEL_NAME));
      metrics.incrementPrediction(ToyBattedBallInference.MODEL_NAME, ROLE);
      float elapsedMs = elapsedNanos / 1_000_000.0f;
      String correlationId = MDC.get("correlation_id");

      logger.enqueue(
          new PredictionLogEvent(
              UUID.randomUUID(),
              requestAt,
              ToyBattedBallInference.MODEL_NAME,
              ToyBattedBallInference.MODEL_VERSION,
              PredictionLogEvent.Role.CHAMPION,
              featureSchemaHash,
              serializeFeatures(req),
              serializePrediction(prob),
              elapsedMs,
              correlationId));

      return new PredictionResponse(
          prob,
          ToyBattedBallInference.MODEL_NAME,
          ToyBattedBallInference.MODEL_VERSION,
          elapsedNanos / 1_000L,
          correlationId);
    } catch (Exception e) {
      metrics.incrementError(ToyBattedBallInference.MODEL_NAME, e.getClass().getSimpleName());
      throw e;
    }
  }

  private static String serializeFeatures(BattedBallRequest req) throws JsonProcessingException {
    return MAPPER.writeValueAsString(req);
  }

  private static String serializePrediction(float prob) throws JsonProcessingException {
    return MAPPER.writeValueAsString(java.util.Map.of("prob_hr", prob));
  }

  /** Visible for tests — stable hash of the request as a coarse de-dup key. */
  static String stableFeatureHash(BattedBallRequest req) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] digest = md.digest(MAPPER.writeValueAsString(req).getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(digest.length * 2);
      for (byte b : digest) hex.append(String.format("%02x", b));
      return hex.toString();
    } catch (NoSuchAlgorithmException | JsonProcessingException e) {
      throw new IllegalStateException("unable to hash request", e);
    }
  }
}
