package net.thebullpen.baseball.api;

import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.TreeMap;
import net.thebullpen.baseball.api.dto.AllParksPredictionResponse;
import net.thebullpen.baseball.api.dto.BattedBallRequest;
import net.thebullpen.baseball.inference.ToyBattedBallInference;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /v1/predict/batted-ball/all-parks} — fan-out predictor for the Park Explorer marquee
 * (leaf 4c.2). Returns a 30-entry {@code probHrByPark} map in a single round-trip.
 *
 * <p><strong>Temporary implementation note</strong>: the leaf body assumed the 2c.5 30-park MLP
 * would natively produce 30 outputs in one ONNX call. That model isn't built yet (Phase 4 is ahead
 * of Phase 2c.5 due to vertical-slice priority). For v1 we loop over the existing toy inference 30
 * times, varying only {@code parkId}. At ~10 μs per call this totals ~300 μs — well inside the
 * leaf's "&lt; 1 s render" budget. The endpoint contract is stable; the implementation swaps to a
 * real fan-out ONNX call when 2c.5 lands without any frontend or API change.
 *
 * <p>{@code parkId} on the inbound request is ignored — the loop iterates the 30-park list. All
 * other fields (launch speed / angle / release speed / stand) are forwarded unchanged.
 */
@RestController
@RequestMapping("/v1/predict")
@Profile("api")
public class PredictAllParksController {

  /**
   * The 30 canonical MLB park ids, sorted alphabetically. Source of truth is the {@code
   * infra/park_geometry/*.json} set; this list is authored once so a missing geometry file fails
   * loudly rather than dropping a park silently. Excludes the {@code ARI} legacy 2-letter code in
   * favor of {@code AZ} (the Statcast-canonical form).
   */
  static final List<String> PARK_IDS =
      List.of(
          "ATH", "ATL", "AZ", "BAL", "BOS", "CHC", "CIN", "CLE", "COL", "CWS", "DET", "HOU", "KC",
          "LAA", "LAD", "MIA", "MIL", "MIN", "NYM", "NYY", "PHI", "PIT", "SD", "SEA", "SF", "STL",
          "TB", "TEX", "TOR", "WSH");

  private final ToyBattedBallInference inference;

  public PredictAllParksController(ToyBattedBallInference inference) {
    this.inference = inference;
  }

  @PostMapping("/batted-ball/all-parks")
  public AllParksPredictionResponse predictAllParks(@Valid @RequestBody BattedBallRequest req)
      throws Exception {
    Instant start = Instant.now();
    TreeMap<String, Double> probHrByPark = new TreeMap<>();
    for (String parkId : PARK_IDS) {
      float prob =
          inference.predict(
              req.launchSpeedMph(),
              req.launchAngleDeg(),
              req.releaseSpeedMph(),
              parkId,
              req.stand());
      probHrByPark.put(parkId, (double) prob);
    }
    long latencyMicros = java.time.Duration.between(start, Instant.now()).toNanos() / 1_000L;
    String correlationId = MDC.get("correlation_id");
    return new AllParksPredictionResponse(
        probHrByPark,
        ToyBattedBallInference.MODEL_NAME,
        ToyBattedBallInference.MODEL_VERSION,
        latencyMicros,
        correlationId == null ? "" : correlationId);
  }
}
