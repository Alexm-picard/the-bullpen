package net.thebullpen.baseball.api;

import io.micrometer.core.instrument.Timer;
import jakarta.validation.Valid;
import net.thebullpen.baseball.api.dto.BattedBallRequest;
import net.thebullpen.baseball.api.dto.PredictionResponse;
import net.thebullpen.baseball.inference.InferenceMetrics;
import net.thebullpen.baseball.inference.ToyBattedBallInference;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /v1/predict/batted-ball — Phase 1.5.
 *
 * <p>Hardcoded role={@code champion} until Phase 3b adds the A/B router. The async logging path
 * lands in 1.7; this controller just returns the prediction synchronously.
 */
@RestController
@RequestMapping("/v1/predict")
@Profile("api")
public class PredictBattedBallController {

  private static final String ROLE = "champion";

  private final ToyBattedBallInference inference;
  private final InferenceMetrics metrics;

  public PredictBattedBallController(ToyBattedBallInference inference, InferenceMetrics metrics) {
    this.inference = inference;
    this.metrics = metrics;
  }

  @PostMapping("/batted-ball")
  public PredictionResponse predict(@Valid @RequestBody BattedBallRequest req) throws Exception {
    Timer.Sample sample = metrics.startTimer();
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
      return new PredictionResponse(
          prob,
          ToyBattedBallInference.MODEL_NAME,
          ToyBattedBallInference.MODEL_VERSION,
          elapsedNanos / 1_000L,
          MDC.get("correlation_id"));
    } catch (Exception e) {
      metrics.incrementError(ToyBattedBallInference.MODEL_NAME, e.getClass().getSimpleName());
      throw e;
    }
  }
}
