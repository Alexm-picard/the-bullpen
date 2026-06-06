package net.thebullpen.baseball.ingest;

import ai.onnxruntime.OrtException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import net.thebullpen.baseball.inference.AsyncPredictionLogger;
import net.thebullpen.baseball.inference.FeaturePipelinePitchPre;
import net.thebullpen.baseball.inference.PitchInferenceService;
import net.thebullpen.baseball.inference.PredictionLogEvent;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Runs the pre-pitch head on the about-to-be-thrown pitch (decision [143] predict-next) and
 * enqueues a {@code prediction_log} row keyed to {@code (game_id, at_bat_index, pitch_number)} so
 * it reconciles to that pitch when it lands (step 5 LEFT JOIN). Worker-profile, in-process
 * inference (decision [27]); gated on the pre-head being loaded so the worker degrades gracefully
 * when no model artifact is present.
 *
 * <p>Feature conventions match the training pipeline exactly to avoid train/serve skew:
 *
 * <ul>
 *   <li>{@code score_diff = 0} - the production pre-head trained on a constant-0 placeholder
 *       ({@code select_labeled_pitches.sql}: {@code toInt16(0) AS score_diff}); a real score would
 *       be skew on a feature the model never varied over.
 *   <li>{@code dow} = ISO day-of-week 1=Mon..7=Sun, matching ClickHouse {@code toDayOfWeek}.
 *   <li>{@code base_state} = 1/2/4 runner bitmask (on_1b/2b/3b).
 *   <li>Tier 3 form = null (decision [143]); LightGBM treats it as NaN. A documented skew, watched
 *       as a separate live-calibration metric, closed later by {@code pitcher_form_current}.
 * </ul>
 */
@Component
@Profile("worker")
@ConditionalOnBean(PitchInferenceService.class)
public class LivePitchPredictor {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final PitchInferenceService inference;
  private final AsyncPredictionLogger logger;
  private final String schemaHash;

  public LivePitchPredictor(PitchInferenceService inference, AsyncPredictionLogger logger) {
    this.inference = inference;
    this.logger = logger;
    this.schemaHash = inference.pipelineSpec().schemaHash();
  }

  /**
   * Predict the next pitch and enqueue a keyed {@code prediction_log} row. Returns the calibrated
   * 5-class distribution (the poller may surface it for live display).
   */
  public Map<String, Double> predictAndLog(LiveNextPitch ctx) throws OrtException {
    Instant requestAt = Instant.now();
    long startNanos = System.nanoTime();
    Map<String, Double> probs = inference.predictPre(toRequest(ctx));
    float latencyMs = (System.nanoTime() - startNanos) / 1_000_000.0f;
    logger.enqueue(buildEvent(ctx, probs, requestAt, schemaHash, latencyMs));
    return probs;
  }

  /** Assemble the pre-head request from the live context (conventions per the class doc). */
  static FeaturePipelinePitchPre.Request toRequest(LiveNextPitch ctx) {
    return new FeaturePipelinePitchPre.Request(
        ctx.balls(),
        ctx.strikes(),
        ctx.outs(),
        ctx.inning(),
        ctx.baseState(),
        0, // score_diff: training placeholder is a constant 0 (no real score, no skew)
        ctx.gameDate().getDayOfWeek().getValue(), // dow: ISO 1=Mon..7=Sun == toDayOfWeek
        ctx.pitchHand(),
        resolveBatSide(ctx.batSide(), ctx.pitchHand()),
        ctx.parkId(),
        ctx.pitcherId(),
        ctx.batterId(),
        // Tier 3 form: null for v1 (decision [143]); forwarded as NaN.
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  /** Switch hitters bat opposite the pitcher's hand; the model expects a resolved {@code L|R}. */
  static String resolveBatSide(String batSide, String pitchHand) {
    if (!"S".equals(batSide)) {
      return batSide;
    }
    return "L".equals(pitchHand) ? "R" : "L";
  }

  /** Build the keyed event (CHAMPION; the live champion pre-head serves the user). */
  static PredictionLogEvent buildEvent(
      LiveNextPitch ctx,
      Map<String, Double> probs,
      Instant requestAt,
      String schemaHash,
      float latencyMs) {
    String winner = argmax(probs);
    return new PredictionLogEvent(
        UUID.randomUUID(),
        requestAt,
        PitchInferenceService.MODEL_NAME,
        PitchInferenceService.MODEL_VERSION,
        null, // model_version_id: router not wired into the live path; reconcile by name+version
        PredictionLogEvent.Role.CHAMPION,
        schemaHash,
        serialize(toRequest(ctx)),
        serializePrediction(probs, winner),
        latencyMs,
        MDC.get("correlation_id"),
        ctx.gameId(),
        ctx.atBatIndex(),
        ctx.pitchNumber());
  }

  static String argmax(Map<String, Double> probs) {
    String best = null;
    double bestVal = Double.NEGATIVE_INFINITY;
    for (Map.Entry<String, Double> e : probs.entrySet()) {
      if (e.getValue() > bestVal) {
        best = e.getKey();
        bestVal = e.getValue();
      }
    }
    return best == null ? "unknown" : best;
  }

  private static String serialize(Object value) {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      return "{}";
    }
  }

  private static String serializePrediction(Map<String, Double> probs, String winner) {
    try {
      return MAPPER.writeValueAsString(Map.of("probabilities", probs, "winner", winner));
    } catch (JsonProcessingException e) {
      return "{}";
    }
  }
}
