package net.thebullpen.baseball.api;

import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.List;
import net.thebullpen.baseball.api.dto.PitchPredictionResponse;
import net.thebullpen.baseball.api.dto.PitchRequest;
import net.thebullpen.baseball.inference.Head;
import net.thebullpen.baseball.inference.PitchPredictionService;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * {@code POST /v1/predict/pitch[?head=pre|post]} - Phase 2a.8 + 2b.3, rewired through the
 * ML-systems wrapper (W1, fixes BUG-2).
 *
 * <p>A thin HTTP adapter: it parses the {@code head} param, enforces the {@code head=post} Tier-4
 * precondition (a 400), and maps the served result onto the response DTO. All routing /
 * dual-logging / serving orchestration lives in {@link PitchPredictionService}.
 *
 * <p>Routing goes through the router under two SEPARATE registered model names (rule 9): {@code
 * pitch_outcome_pre} for {@code head=pre} (the default) and {@code pitch_outcome_post} for {@code
 * head=post}. Each head is its own registry entry, its own A/B routing row, its own champion +
 * shadow. The champion serves the user; any SHADOW challenger runs in parallel and is logged to
 * {@code prediction_logs} with the routed {@code servingVersionId} as the {@code model_version_id}
 * FK - so per-version drift attribution joins precisely against the registry.
 *
 * <p>Dispatch matrix per head (mirrors {@code PredictAllParksController}), implemented in the
 * service:
 *
 * <ul>
 *   <li>An A/B routing config present -> champion serves, any challenger runs in shadow and is
 *       logged.
 *   <li>No routing config -> serve the registry's LIVE champion directly; {@code 503} when none is
 *       live (decision: serve-live-champion-else-503), NOT 404.
 * </ul>
 *
 * <p>The legacy artifact-direct {@code PitchInferenceService} bean is kept as a flagged dev
 * fallback (locked decision): set {@code bullpen.inference.pitch.dev-direct-serving=true} to serve
 * straight off the on-disk artifact when no registry champion exists, for local no-registry dev.
 * The default ({@code false}) is the router path, so prod + CI go through the registry.
 *
 * <p>{@code X-Bullpen-Game-Id} is not part of the 2a.8 pitch contract, so bucket assignment is
 * per-request (random) in the service - fine for dev-curl / single-prediction traffic. The live
 * poller ({@code LivePitchPredictor}) drives consistent per-game bucketing on its own path.
 *
 * <p>Rule 7 note: the feature-schema hash registration gate is unchanged - the schema hash stamped
 * on a log row comes from the loaded model's own snapshot contract, never recomputed here.
 */
@RestController
@RequestMapping("/v1/predict")
@Profile("api")
public class PredictPitchController {

  private final PitchPredictionService service;

  public PredictPitchController(PitchPredictionService service) {
    this.service = service;
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
    }

    String correlationId = MDC.get("correlation_id");
    PitchPredictionService.Served served = service.predict(head, req, correlationId);
    return new PitchPredictionResponse(
        served.probabilities(),
        served.winner(),
        served.modelName(),
        served.servingVersion(),
        served.elapsedMicros(),
        correlationId);
  }

  /**
   * Returns the list of Tier 4 field names that are null on the request (post-head precondition).
   * Kept on the controller because it drives a client-side 400 - an HTTP concern, not inference.
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
}
