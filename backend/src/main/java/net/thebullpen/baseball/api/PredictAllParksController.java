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
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;
import net.thebullpen.baseball.api.dto.AllParksOutcomeRequest;
import net.thebullpen.baseball.api.dto.AllParksPredictionResponse;
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
 * {@code POST /v1/predict/batted-ball/all-parks} - the real per-park outcome predictor (decision
 * [146]), slimmed onto {@link PredictionOrchestrator} (M5): the controller keeps the web layer
 * (annotations, binding, MDC read, DTO mapping, the outcome-order response extraction); the shared
 * skeleton lives in the orchestrator.
 *
 * <p>Routing goes through {@link InferenceRouter} under model name {@code battedball_outcome}. The
 * family's {@code -1L} policy (see the orchestrator's class javadoc): no routing row means the
 * fallback served the registry's LIVE champion directly - identity is the RE-RESOLVED champion's
 * real id/version/hash ({@code 503} when none is live; serve-live-champion-else-503, the toy is not
 * in this path). The FK is never null on this family.
 *
 * <p>{@code X-Bullpen-Game-Id} drives bucket assignment (random per-request when absent - fine for
 * Park-Explorer / dev-curl traffic).
 */
@RestController
@RequestMapping("/v1/predict")
@Profile("api")
public class PredictAllParksController {

  static final String MODEL_NAME = "battedball_outcome";
  private static final String HR_OUTCOME = "hr";

  private final ObjectMapper objectMapper;
  private final ModelLoader modelLoader;
  private final RegistryService registry;
  private final PredictionOrchestrator orchestrator;

  public PredictAllParksController(
      ModelLoader modelLoader,
      RegistryService registry,
      PredictionOrchestrator orchestrator,
      ObjectMapper objectMapper) {
    this.modelLoader = modelLoader;
    this.registry = registry;
    this.orchestrator = orchestrator;
    this.objectMapper = objectMapper;
  }

  @Operation(
      summary = "Predict per-park outcome distribution for a batted ball",
      description =
          "Runs the real per-park outcome model once and returns P(home run) for the launch"
              + " parameters at each of the 30 MLB parks, plus per-park carry feet when the"
              + " champion has a carry head. Serves the registry LIVE champion; 503 when none is"
              + " live.")
  @ApiResponse(
      responseCode = "200",
      description =
          "Per-park home-run probabilities (and optional carry feet) with model identity.",
      content =
          @Content(
              mediaType = "application/json",
              schema = @Schema(implementation = AllParksPredictionResponse.class)))
  @PostMapping("/batted-ball/all-parks")
  public AllParksPredictionResponse predictAllParks(
      @Valid @RequestBody AllParksOutcomeRequest req,
      @RequestHeader(value = "X-Bullpen-Game-Id", required = false) Long gameIdHeader)
      throws Exception {
    long gameId = gameIdHeader != null ? gameIdHeader : ThreadLocalRandom.current().nextLong();
    String correlationId = MDC.get("correlation_id");
    FeaturePipelineBattedBall.Request pipeReq = toPipelineRequest(req);

    PredictionOrchestrator.Served<LoadedAllParksModel.AllParksPrediction> served =
        orchestrator.predict(new AllParksFamily(req, pipeReq), gameId, correlationId);

    // Response mapping needs the serving model's outcome order; the FK is never null on this
    // family (the -1L policy re-resolves the champion), so this is a Caffeine cache hit.
    LoadedAllParksModel servingModel = modelLoader.loadAllParks(served.identity().versionFk());
    LoadedAllParksModel.AllParksPrediction serving = served.response();
    Map<String, Double> probHrByPark =
        extractHr(serving.distribution(), servingModel.outcomeOrder());

    return new AllParksPredictionResponse(
        probHrByPark,
        serving.carryFtByPark(), // null for a probabilities-only champion -> omitted from JSON
        MODEL_NAME,
        served.identity().versionLabel(),
        served.elapsedNanos() / 1_000L,
        correlationId == null ? "" : correlationId);
  }

  /**
   * The all-parks family: registry-routed leg and a legacy leg that serves the LIVE champion (503
   * when none) - the {@code -1L} identity policy RE-RESOLVES the champion, never a null FK.
   */
  private final class AllParksFamily
      implements PredictionOrchestrator.Family<LoadedAllParksModel.AllParksPrediction> {
    private final AllParksOutcomeRequest req;
    private final FeaturePipelineBattedBall.Request pipeReq;

    private AllParksFamily(AllParksOutcomeRequest req, FeaturePipelineBattedBall.Request pipeReq) {
      this.req = req;
      this.pipeReq = pipeReq;
    }

    @Override
    public String modelName() {
      return MODEL_NAME;
    }

    @Override
    public Object featurePayload() {
      // The RAW request DTO, never the transformed pipeReq - the drift observed side
      // JSONExtracts by request-field name (launchSpeedMph etc.).
      return req;
    }

    @Override
    public LoadedAllParksModel.AllParksPrediction predictByVersionId(long versionId)
        throws Exception {
      // One inference yields the per-park distribution plus the per-park carry feet when the
      // champion has a carry head; carryFtByPark is null for a probabilities-only champion.
      return modelLoader.loadAllParks(versionId).predictWithCarry(pipeReq);
    }

    @Override
    public LoadedAllParksModel.AllParksPrediction legacyFallback() throws Exception {
      return modelLoader.loadAllParks(requireChampionId()).predictWithCarry(pipeReq);
    }

    @Override
    public PredictionOrchestrator.Identity legacyIdentity() {
      // The fallback served the registry champion, so re-resolve it for its identity (cached, so
      // this is a cheap registry lookup + a cache hit). Throws the 503 RSE when none is live -
      // unreachable in practice here because legacyFallback() already required it this request.
      return identityFor(requireChampionId());
    }

    @Override
    public PredictionOrchestrator.Identity identityFor(long versionId) {
      LoadedAllParksModel model = modelLoader.loadAllParks(versionId);
      return new PredictionOrchestrator.Identity(model.version(), model.schemaHash(), versionId);
    }

    @Override
    public String serializePrediction(LoadedAllParksModel.AllParksPrediction prediction)
        throws JsonProcessingException {
      // The full park -> 5-outcome distribution (the logged payload; the response is HR-only).
      return objectMapper.writeValueAsString(prediction.distribution());
    }
  }

  /**
   * The registry's LIVE champion id for {@link #MODEL_NAME}, or a 503 when none is live. Used by
   * the no-routing-config fallback (decision: serve-live-champion-else-503).
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

  private static FeaturePipelineBattedBall.Request toPipelineRequest(AllParksOutcomeRequest req) {
    return new FeaturePipelineBattedBall.Request(
        req.launchSpeedMph(),
        req.launchAngleDeg(),
        req.sprayAngleDeg(),
        req.hitDistanceFt(),
        req.stand(),
        req.baseState(),
        req.outs());
  }

  private static Map<String, Double> extractHr(
      Map<String, float[]> dist, List<String> outcomeOrder) {
    int hrIdx = outcomeOrder.indexOf(HR_OUTCOME);
    if (hrIdx < 0) {
      throw new IllegalStateException(
          "serving model outcome order has no '" + HR_OUTCOME + "': " + outcomeOrder);
    }
    TreeMap<String, Double> probHrByPark = new TreeMap<>();
    for (Map.Entry<String, float[]> e : dist.entrySet()) {
      probHrByPark.put(e.getKey(), (double) e.getValue()[hrIdx]);
    }
    return probHrByPark;
  }
}
