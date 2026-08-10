package net.thebullpen.baseball.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import net.thebullpen.baseball.api.dto.BattedBallRequest;
import net.thebullpen.baseball.api.dto.PredictionResponse;
import net.thebullpen.baseball.inference.AsyncPredictionLogger;
import net.thebullpen.baseball.inference.FeaturePipelineBattedBall;
import net.thebullpen.baseball.inference.InferenceRouter;
import net.thebullpen.baseball.inference.LoadedAllParksModel;
import net.thebullpen.baseball.inference.ModelLoader;
import net.thebullpen.baseball.inference.PredictionOrchestrator;
import net.thebullpen.baseball.registry.RegistryService;
import net.thebullpen.baseball.registry.dto.ModelVersion;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * POST /v1/predict/batted-ball - the SINGLE-park view of the registered per-park outcome champion
 * {@code battedball_outcome}, on {@link PredictionOrchestrator} (M5).
 *
 * <p>"Retire the toy": this endpoint used to serve {@code ToyBattedBallInference} as a hardcoded
 * legacy fallback (audit "toy-as-live" surface). It now serves the SAME champion {@code /all-parks}
 * serves - one inference over all 30 parks, from which the requested {@code parkId} is extracted -
 * and returns a {@code 503} when no champion is registered, exactly like {@code /all-parks}. There
 * is no toy fallback: with no champion it fails loud everywhere rather than silently serving a toy.
 *
 * <p>Returns the prediction synchronously and enqueues the log rows for the async batch logger.
 * Dropping on overload is the contract - see {@link AsyncPredictionLogger}.
 */
@RestController
@RequestMapping("/v1/predict")
@Profile("api")
public class PredictBattedBallController {

  /** Same registry family {@code /all-parks} serves - single-park is one park of its output. */
  static final String MODEL_NAME = "battedball_outcome";

  private static final String HR_OUTCOME = "hr";

  private final ObjectMapper objectMapper;
  private final ModelLoader modelLoader;
  private final PredictionOrchestrator orchestrator;
  private final RegistryService registry;

  public PredictBattedBallController(
      ModelLoader modelLoader,
      PredictionOrchestrator orchestrator,
      RegistryService registry,
      ObjectMapper objectMapper) {
    this.modelLoader = modelLoader;
    this.orchestrator = orchestrator;
    this.registry = registry;
    this.objectMapper = objectMapper;
  }

  /**
   * Predict + dispatch via {@link InferenceRouter} (leaf 3b.3), through the orchestrator. Serves
   * the {@code battedball_outcome} champion (or 503 when none); the requested {@code parkId} is
   * extracted from the champion's per-park output. The {@code -1L} no-routing-config path
   * re-resolves the LIVE champion (never a toy), mirroring {@code /all-parks}.
   *
   * <p>{@code X-Bullpen-Game-Id} request header drives the Murmur3 bucket assignment. When absent
   * (Park Explorer / dev curl), a random long is used - assignment is then per-request, not
   * per-game, which is fine for the demo-traffic case.
   */
  @Operation(
      summary = "Predict P(home run) for a batted ball at one park",
      description =
          "Runs the registered per-park outcome champion for the given batted-ball inputs and"
              + " returns the home-run probability at the requested park, logging the prediction"
              + " asynchronously. Routing fans out to champion + any shadow via the A/B router."
              + " Returns 503 when no champion is registered.")
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
    FeaturePipelineBattedBall.Request pipeReq = toPipelineRequest(req);

    PredictionOrchestrator.Served<Map<String, float[]>> served =
        orchestrator.predict(new SingleParkFamily(req, pipeReq), gameId, correlationId);

    // Extract the requested park HERE, after the orchestrator returns - NOT inside the family. A
    // bad parkId is a client error (400); thrown from inside predictByVersionId it runs in the
    // champion future and the orchestrator's wrap() would surface it as a 500. The FK is never null
    // on this family (the -1L policy re-resolves the champion), so this loadAllParks is a cache
    // hit.
    LoadedAllParksModel servingModel = modelLoader.loadAllParks(served.identity().versionFk());
    float probHr = hrForPark(served.response(), servingModel.outcomeOrder(), req.parkId());

    return new PredictionResponse(
        probHr,
        MODEL_NAME,
        served.identity().versionLabel(),
        served.elapsedNanos() / 1_000L,
        correlationId);
  }

  private static FeaturePipelineBattedBall.Request toPipelineRequest(BattedBallRequest req) {
    return new FeaturePipelineBattedBall.Request(
        req.launchSpeedMph(),
        req.launchAngleDeg(),
        req.sprayAngleDeg(),
        req.hitDistanceFt(),
        req.stand(),
        req.baseState(),
        req.outs());
  }

  /**
   * The single-park family: registry-routed legs only. Each leg runs the all-parks champion once
   * and extracts {@code req.parkId()}'s HR probability - single-park and {@code /all-parks} serve
   * the identical champion with no divergence.
   */
  private final class SingleParkFamily
      implements PredictionOrchestrator.Family<Map<String, float[]>> {
    private final BattedBallRequest req;
    private final FeaturePipelineBattedBall.Request pipeReq;

    private SingleParkFamily(BattedBallRequest req, FeaturePipelineBattedBall.Request pipeReq) {
      this.req = req;
      this.pipeReq = pipeReq;
    }

    @Override
    public String modelName() {
      return MODEL_NAME;
    }

    @Override
    public Object featurePayload() {
      // The RAW request DTO, never the transformed pipeReq - the drift observed side JSONExtracts
      // by request-field name (launchSpeedMph etc.).
      return req;
    }

    @Override
    public Map<String, float[]> predictByVersionId(long versionId) throws Exception {
      return modelLoader.loadAllParks(versionId).predict(pipeReq);
    }

    @Override
    public Map<String, float[]> legacyFallback() throws Exception {
      // No routing row: serve the LIVE champion (503 when none). NEVER a toy - the -1L policy
      // re-resolves the champion exactly as /all-parks does.
      return modelLoader.loadAllParks(requireChampionId()).predict(pipeReq);
    }

    @Override
    public PredictionOrchestrator.Identity legacyIdentity() {
      // The fallback served the registry champion, so re-resolve it for its identity.
      return identityFor(requireChampionId());
    }

    @Override
    public PredictionOrchestrator.Identity identityFor(long versionId) {
      LoadedAllParksModel model = modelLoader.loadAllParks(versionId);
      return new PredictionOrchestrator.Identity(model.version(), model.schemaHash(), versionId);
    }

    @Override
    public String serializePrediction(Map<String, float[]> distribution)
        throws JsonProcessingException {
      // The full park -> 5-outcome distribution is the logged payload; the response is HR-only.
      return objectMapper.writeValueAsString(distribution);
    }
  }

  /** Pull the requested park's HR probability out of the champion's per-park distribution. */
  private static float hrForPark(
      Map<String, float[]> dist, List<String> outcomeOrder, String parkId) {
    float[] parkVec = dist.get(parkId);
    if (parkVec == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "unknown parkId '" + parkId + "'; the model serves parks " + dist.keySet());
    }
    int hrIdx = outcomeOrder.indexOf(HR_OUTCOME);
    if (hrIdx < 0) {
      throw new IllegalStateException(
          "serving model outcome order has no '" + HR_OUTCOME + "': " + outcomeOrder);
    }
    return parkVec[hrIdx];
  }

  /**
   * The registry's LIVE champion id for {@link #MODEL_NAME}, or a 503 when none is live - used by
   * the no-routing-config fallback (serve-live-champion-else-503, mirroring {@code /all-parks}).
   */
  private long requireChampionId() {
    return registry
        .findChampion(MODEL_NAME)
        .map(ModelVersion::id)
        .orElseThrow(
            () ->
                new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    MODEL_NAME
                        + " has no LIVE champion and no A/B routing config; register + promote a"
                        + " model first"));
  }
}
