package net.thebullpen.baseball.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Timer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.thebullpen.baseball.api.dto.AllParksOutcomeRequest;
import net.thebullpen.baseball.api.dto.AllParksPredictionResponse;
import net.thebullpen.baseball.inference.AsyncPredictionLogger;
import net.thebullpen.baseball.inference.FeaturePipelineBattedBall;
import net.thebullpen.baseball.inference.InferenceMetrics;
import net.thebullpen.baseball.inference.InferenceRouter;
import net.thebullpen.baseball.inference.LoadedAllParksModel;
import net.thebullpen.baseball.inference.ModelLoader;
import net.thebullpen.baseball.inference.PredictionLogEvent;
import net.thebullpen.baseball.inference.RoutedPrediction;
import net.thebullpen.baseball.inference.routing.Role;
import net.thebullpen.baseball.registry.RegistryService;
import net.thebullpen.baseball.registry.dto.ModelVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * [146]; replaces the placeholder 30x toy loop). One ONNX call yields a calibrated 5-outcome
 * distribution per park. The v1 response is HR-only ({@code probHrByPark}); the full per-park
 * distribution is logged to {@code prediction_logs} for the shadow comparison.
 *
 * <p>Routing goes through {@link InferenceRouter} under model name {@code battedball_outcome}:
 *
 * <ul>
 *   <li>An A/B routing config present (the normal state once a champion is promoted) -> champion
 *       serves, any challenger runs in shadow and is logged.
 *   <li>No routing config -> serve the registry's LIVE champion directly; {@code 503} when none is
 *       live (decision: serve-live-champion-else-503). The toy is no longer in this path.
 * </ul>
 *
 * <p>{@code X-Bullpen-Game-Id} drives bucket assignment (random per-request when absent - fine for
 * Park-Explorer / dev-curl traffic).
 */
@RestController
@RequestMapping("/v1/predict")
@Profile("api")
public class PredictAllParksController {

  private static final Logger log = LoggerFactory.getLogger(PredictAllParksController.class);

  static final String MODEL_NAME = "battedball_outcome";
  private static final String HR_OUTCOME = "hr";

  private final ObjectMapper objectMapper;
  private final ModelLoader modelLoader;
  private final InferenceRouter router;
  private final RegistryService registry;
  private final AsyncPredictionLogger logger;
  private final InferenceMetrics metrics;

  public PredictAllParksController(
      ModelLoader modelLoader,
      InferenceRouter router,
      RegistryService registry,
      AsyncPredictionLogger logger,
      InferenceMetrics metrics,
      ObjectMapper objectMapper) {
    this.modelLoader = modelLoader;
    this.router = router;
    this.registry = registry;
    this.logger = logger;
    this.metrics = metrics;
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
    Timer.Sample sample = metrics.startTimer();
    Instant requestAt = Instant.now();
    long gameId = gameIdHeader != null ? gameIdHeader : ThreadLocalRandom.current().nextLong();
    String correlationId = MDC.get("correlation_id");
    FeaturePipelineBattedBall.Request pipeReq = toPipelineRequest(req);

    try {
      RoutedPrediction<LoadedAllParksModel.AllParksPrediction> routed =
          router.route(
              MODEL_NAME,
              gameId,
              versionId -> predict(modelLoader.loadAllParks(versionId), pipeReq),
              () -> predict(modelLoader.loadAllParks(requireChampionId()), pipeReq));

      long elapsedNanos = sample.stop(metrics.timer(MODEL_NAME));
      metrics.incrementPrediction(MODEL_NAME, routed.servingRole().name().toLowerCase(Locale.ROOT));
      float elapsedMs = elapsedNanos / 1_000_000.0f;

      // Legacy fallback (servingVersionId == -1) served the registry champion, so re-resolve it for
      // its identity + outcome order (cached, so this is a cheap registry lookup + a cache hit).
      long servingVersionId =
          routed.servingVersionId() == -1L ? requireChampionId() : routed.servingVersionId();
      LoadedAllParksModel servingModel = modelLoader.loadAllParks(servingVersionId);

      LoadedAllParksModel.AllParksPrediction serving = routed.servingResponse();
      Map<String, float[]> dist = serving.distribution();
      Map<String, Double> probHrByPark = extractHr(dist, servingModel.outcomeOrder());

      logger.enqueue(
          new PredictionLogEvent(
              UUID.randomUUID(),
              requestAt,
              MODEL_NAME,
              servingModel.version(),
              servingVersionId,
              toLogRole(routed.servingRole()),
              servingModel.schemaHash(),
              serializeFeatures(req),
              serializeDistribution(dist),
              elapsedMs,
              correlationId));

      // Shadow row logged FIRE-AND-FORGET off the request path (F1.4).
      routed
          .shadowFuture()
          .ifPresent(
              shadowFut -> {
                long shadowVid = routed.shadowVersionId().orElseThrow();
                shadowFut.whenComplete(
                    (shadowResp, ex) -> {
                      if (ex != null) {
                        return;
                      }
                      try {
                        LoadedAllParksModel shadowModel = modelLoader.loadAllParks(shadowVid);
                        logger.enqueue(
                            new PredictionLogEvent(
                                UUID.randomUUID(),
                                requestAt,
                                MODEL_NAME,
                                shadowModel.version(),
                                shadowVid,
                                PredictionLogEvent.Role.SHADOW,
                                shadowModel.schemaHash(),
                                serializeFeatures(req),
                                serializeDistribution(shadowResp.distribution()),
                                elapsedMs,
                                correlationId));
                      } catch (JsonProcessingException je) {
                        log.warn(
                            "shadow row serialization failed for {}: {}",
                            MODEL_NAME,
                            je.toString());
                      }
                    });
              });

      return new AllParksPredictionResponse(
          probHrByPark,
          serving.carryFtByPark(), // null for a probabilities-only champion -> omitted from JSON
          MODEL_NAME,
          servingModel.version(),
          elapsedNanos / 1_000L,
          correlationId == null ? "" : correlationId);
    } catch (ResponseStatusException e) {
      throw e; // 503 (no champion) / client errors pass through untouched
    } catch (Exception e) {
      metrics.incrementError(MODEL_NAME, e.getClass().getSimpleName());
      throw e;
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

  private static LoadedAllParksModel.AllParksPrediction predict(
      LoadedAllParksModel model, FeaturePipelineBattedBall.Request req) {
    try {
      // One inference yields the per-park distribution plus the per-park carry feet when the
      // champion has a carry head; carryFtByPark is null for a probabilities-only champion.
      return model.predictWithCarry(req);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
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

  private static PredictionLogEvent.Role toLogRole(Role role) {
    return switch (role) {
      case CHAMPION -> PredictionLogEvent.Role.CHAMPION;
      case CHALLENGER -> PredictionLogEvent.Role.CHALLENGER;
      case SHADOW -> PredictionLogEvent.Role.SHADOW;
    };
  }

  private String serializeFeatures(AllParksOutcomeRequest req) throws JsonProcessingException {
    return objectMapper.writeValueAsString(req);
  }

  private String serializeDistribution(Map<String, float[]> dist) throws JsonProcessingException {
    return objectMapper.writeValueAsString(dist);
  }
}
