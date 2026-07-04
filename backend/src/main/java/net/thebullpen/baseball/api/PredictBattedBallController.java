package net.thebullpen.baseball.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Timer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
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
import net.thebullpen.baseball.inference.InferenceRouter;
import net.thebullpen.baseball.inference.ModelLoader;
import net.thebullpen.baseball.inference.PredictionLogEvent;
import net.thebullpen.baseball.inference.RoutedPrediction;
import net.thebullpen.baseball.inference.ToyBattedBallInference;
import net.thebullpen.baseball.inference.routing.Role;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
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

  private final ObjectMapper objectMapper;
  private final ToyBattedBallInference inference;
  private final InferenceMetrics metrics;
  private final AsyncPredictionLogger logger;
  private final InferenceRouter router;
  private final ModelLoader modelLoader;
  private final String legacyFeatureSchemaHash;

  public PredictBattedBallController(
      ToyBattedBallInference inference,
      InferenceMetrics metrics,
      AsyncPredictionLogger logger,
      InferenceRouter router,
      ModelLoader modelLoader,
      ObjectMapper objectMapper) {
    this.inference = inference;
    this.metrics = metrics;
    this.logger = logger;
    this.router = router;
    this.modelLoader = modelLoader;
    this.objectMapper = objectMapper;
    this.legacyFeatureSchemaHash = inference.pipelineSpec().schemaHash();
  }

  /**
   * Predict + dispatch via {@link InferenceRouter} (leaf 3b.3). When no routing row exists for
   * {@code _toy_batted_ball}, the legacy supplier is invoked — preserves the pre-router behavior
   * exactly so unregistered-model environments stay green. When a routing row exists, the router
   * fans out to champion + shadow versions in parallel; both are logged.
   *
   * <p>{@code X-Bullpen-Game-Id} request header drives the Murmur3 bucket assignment. When absent
   * (Park Explorer / dev curl), a random long is used — assignment is then per-request, not
   * per-game, which is fine for the demo-traffic case.
   */
  @Operation(
      summary = "Predict P(home run) for a batted ball at one park",
      description =
          "Runs the batted-ball model for the given launch parameters at the requested park and"
              + " logs the prediction asynchronously. Routing fans out to champion + any shadow"
              + " via the A/B router.")
  @ApiResponse(
      responseCode = "200",
      description = "Predicted home-run probability with the serving model identity.",
      content =
          @Content(
              mediaType = "application/json",
              schema = @Schema(implementation = PredictionResponse.class)))
  @PostMapping("/batted-ball")
  public PredictionResponse predict(
      @Valid @RequestBody BattedBallRequest req,
      @RequestHeader(value = "X-Bullpen-Game-Id", required = false) Long gameIdHeader)
      throws Exception {
    Timer.Sample sample = metrics.startTimer();
    Instant requestAt = Instant.now();
    long gameId =
        gameIdHeader != null
            ? gameIdHeader
            : java.util.concurrent.ThreadLocalRandom.current().nextLong();
    String correlationId = MDC.get("correlation_id");

    try {
      RoutedPrediction<Float> routed =
          router.route(
              ToyBattedBallInference.MODEL_NAME,
              gameId,
              versionId -> {
                try {
                  return modelLoader
                      .loadBattedBall(versionId)
                      .predict(
                          req.launchSpeedMph(),
                          req.launchAngleDeg(),
                          req.releaseSpeedMph(),
                          req.parkId(),
                          req.stand());
                } catch (Exception e) {
                  throw new RuntimeException(e);
                }
              },
              () -> {
                try {
                  return inference.predict(
                      req.launchSpeedMph(),
                      req.launchAngleDeg(),
                      req.releaseSpeedMph(),
                      req.parkId(),
                      req.stand());
                } catch (Exception e) {
                  throw new RuntimeException(e);
                }
              });

      long elapsedNanos = sample.stop(metrics.timer(ToyBattedBallInference.MODEL_NAME));
      metrics.incrementPrediction(
          ToyBattedBallInference.MODEL_NAME,
          routed.servingRole().name().toLowerCase(java.util.Locale.ROOT));
      float elapsedMs = elapsedNanos / 1_000_000.0f;
      float prob = routed.servingResponse();

      // Resolve serving version label: legacy fallback uses the hardcoded v0; otherwise the
      // ModelLoader-backed model knows its own version string.
      String servingVersionLabel =
          routed.servingVersionId() == -1L
              ? ToyBattedBallInference.MODEL_VERSION
              : modelLoader.loadBattedBall(routed.servingVersionId()).version();
      String servingSchemaHash =
          routed.servingVersionId() == -1L
              ? legacyFeatureSchemaHash
              : modelLoader.loadBattedBall(routed.servingVersionId()).schemaHash();

      // 3b.5: populate modelVersionId so reconciliation can join precisely against the
      // registry. Legacy fallback (-1L servingVersionId) → null FK; ModelLoader-resolved
      // versions → their real id.
      Long servingVersionFk = routed.servingVersionId() == -1L ? null : routed.servingVersionId();
      logger.enqueue(
          new PredictionLogEvent(
              UUID.randomUUID(),
              requestAt,
              ToyBattedBallInference.MODEL_NAME,
              servingVersionLabel,
              servingVersionFk,
              toLogRole(routed.servingRole()),
              servingSchemaHash,
              serializeFeatures(req),
              serializePrediction(prob),
              elapsedMs,
              correlationId));

      // Shadow-mode dispatch: log the parallel SHADOW prediction with the challenger's metadata.
      if (routed.hasShadowRow()) {
        long shadowVid = routed.shadowVersionId().orElseThrow();
        var shadowModel = modelLoader.loadBattedBall(shadowVid);
        logger.enqueue(
            new PredictionLogEvent(
                UUID.randomUUID(),
                requestAt,
                ToyBattedBallInference.MODEL_NAME,
                shadowModel.version(),
                shadowVid,
                PredictionLogEvent.Role.SHADOW,
                shadowModel.schemaHash(),
                serializeFeatures(req),
                serializePrediction(routed.shadowResponse().orElseThrow()),
                elapsedMs,
                correlationId));
      }

      return new PredictionResponse(
          prob,
          ToyBattedBallInference.MODEL_NAME,
          servingVersionLabel,
          elapsedNanos / 1_000L,
          correlationId);
    } catch (Exception e) {
      metrics.incrementError(ToyBattedBallInference.MODEL_NAME, e.getClass().getSimpleName());
      throw e;
    }
  }

  private static PredictionLogEvent.Role toLogRole(Role role) {
    return switch (role) {
      case CHAMPION -> PredictionLogEvent.Role.CHAMPION;
      case CHALLENGER -> PredictionLogEvent.Role.CHALLENGER;
      case SHADOW -> PredictionLogEvent.Role.SHADOW;
    };
  }

  private String serializeFeatures(BattedBallRequest req) throws JsonProcessingException {
    return objectMapper.writeValueAsString(req);
  }

  private String serializePrediction(float prob) throws JsonProcessingException {
    return objectMapper.writeValueAsString(java.util.Map.of("prob_hr", prob));
  }

  /** Visible for tests — stable hash of the request as a coarse de-dup key. */
  String stableFeatureHash(BattedBallRequest req) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] digest =
          md.digest(objectMapper.writeValueAsString(req).getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(digest.length * 2);
      for (byte b : digest) hex.append(String.format("%02x", b));
      return hex.toString();
    } catch (NoSuchAlgorithmException | JsonProcessingException e) {
      throw new IllegalStateException("unable to hash request", e);
    }
  }
}
