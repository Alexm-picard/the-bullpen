package net.thebullpen.baseball.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import net.thebullpen.baseball.api.dto.BattedBallRequest;
import net.thebullpen.baseball.api.dto.PredictionResponse;
import net.thebullpen.baseball.inference.AsyncPredictionLogger;
import net.thebullpen.baseball.inference.InferenceRouter;
import net.thebullpen.baseball.inference.ModelLoader;
import net.thebullpen.baseball.inference.PredictionLogEvent;
import net.thebullpen.baseball.inference.PredictionOrchestrator;
import net.thebullpen.baseball.inference.ToyBattedBallInference;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /v1/predict/batted-ball - Phase 1.5 + 1.7, slimmed onto {@link PredictionOrchestrator} (M5):
 * the controller keeps only the web layer (annotations, body/header binding, the MDC correlation
 * read, response mapping); the shared skeleton (router dispatch, metrics, the champion +
 * fire-and-forget shadow {@link PredictionLogEvent} rows, error accounting) lives in the
 * orchestrator, exactly as the pitch side's #185 extraction did.
 *
 * <p>The family's {@code -1L} policy (see the orchestrator's class javadoc): no routing row means
 * the legacy TOY bean served - identity is the hardcoded {@code v0} + the ctor-cached toy pipeline
 * schema hash + a <b>null</b> registry FK (reconciliation depends on legacy rows being NULL).
 *
 * <p>Returns the prediction synchronously and enqueues the log rows for the async batch logger.
 * Dropping on overload is the contract - see {@link AsyncPredictionLogger}.
 */
@RestController
@RequestMapping("/v1/predict")
@Profile("api")
public class PredictBattedBallController {

  private final ObjectMapper objectMapper;
  private final ToyBattedBallInference inference;
  private final ModelLoader modelLoader;
  private final PredictionOrchestrator orchestrator;
  private final String legacyFeatureSchemaHash;

  public PredictBattedBallController(
      ToyBattedBallInference inference,
      ModelLoader modelLoader,
      PredictionOrchestrator orchestrator,
      ObjectMapper objectMapper) {
    this.inference = inference;
    this.modelLoader = modelLoader;
    this.orchestrator = orchestrator;
    this.objectMapper = objectMapper;
    this.legacyFeatureSchemaHash = inference.pipelineSpec().schemaHash();
  }

  /**
   * Predict + dispatch via {@link InferenceRouter} (leaf 3b.3), through the orchestrator. When no
   * routing row exists for {@code _toy_batted_ball}, the legacy supplier is invoked - preserves the
   * pre-router behavior exactly so unregistered-model environments stay green.
   *
   * <p>{@code X-Bullpen-Game-Id} request header drives the Murmur3 bucket assignment. When absent
   * (Park Explorer / dev curl), a random long is used - assignment is then per-request, not
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
    long gameId =
        gameIdHeader != null
            ? gameIdHeader
            : java.util.concurrent.ThreadLocalRandom.current().nextLong();
    String correlationId = MDC.get("correlation_id");

    PredictionOrchestrator.Served<Float> served =
        orchestrator.predict(new SingleParkFamily(req), gameId, correlationId);

    return new PredictionResponse(
        served.response(),
        ToyBattedBallInference.MODEL_NAME,
        served.identity().versionLabel(),
        served.elapsedNanos() / 1_000L,
        correlationId);
  }

  /** The single-park family: toy-bean legacy leg, registry-routed leg via {@link ModelLoader}. */
  private final class SingleParkFamily implements PredictionOrchestrator.Family<Float> {
    private final BattedBallRequest req;

    private SingleParkFamily(BattedBallRequest req) {
      this.req = req;
    }

    @Override
    public String modelName() {
      return ToyBattedBallInference.MODEL_NAME;
    }

    @Override
    public Object featurePayload() {
      return req; // the raw request DTO - the drift observed side reads its field names
    }

    @Override
    public Float predictByVersionId(long versionId) throws Exception {
      return modelLoader
          .loadBattedBall(versionId)
          .predict(
              req.launchSpeedMph(),
              req.launchAngleDeg(),
              req.releaseSpeedMph(),
              req.parkId(),
              req.stand());
    }

    @Override
    public Float legacyFallback() throws Exception {
      return inference.predict(
          req.launchSpeedMph(),
          req.launchAngleDeg(),
          req.releaseSpeedMph(),
          req.parkId(),
          req.stand());
    }

    @Override
    public PredictionOrchestrator.Identity legacyIdentity() {
      // Toy bean served: hardcoded v0 + ctor-cached toy schema hash + NULL FK (3b.5:
      // reconciliation joins prediction_log.model_version_id against the registry and depends
      // on legacy rows being null - the -1L sentinel is never persisted).
      return new PredictionOrchestrator.Identity(
          ToyBattedBallInference.MODEL_VERSION, legacyFeatureSchemaHash, null);
    }

    @Override
    public PredictionOrchestrator.Identity identityFor(long versionId) {
      var model = modelLoader.loadBattedBall(versionId);
      return new PredictionOrchestrator.Identity(model.version(), model.schemaHash(), versionId);
    }

    @Override
    public String serializePrediction(Float prob) throws JsonProcessingException {
      return objectMapper.writeValueAsString(java.util.Map.of("prob_hr", prob));
    }
  }
}
