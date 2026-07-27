package net.thebullpen.baseball.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import java.util.Map;
import net.thebullpen.baseball.api.dto.PitchTypeRequest;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The pitch-TYPE PRIOR endpoint (decision [183]).
 *
 * <p>NAMING AND WORDING ARE PART OF THE CONTRACT HERE. Top-1 accuracy is around 0.45 - pitch
 * selection is high-entropy and the ceiling is low - so this model is promoted on CALIBRATION and
 * never on accuracy. The response type carries no argmax or "predicted pitch" field, the method and
 * response are named for a PRIOR, and the description says what the numbers are. (The URL is {@code
 * /v1/predict/pitch-type} and carries none of that meaning; the enforcement is the ABSENT FIELD,
 * which a test pins, not the path.) A caller must not be able to read this as "we predict the next
 * pitch", which is [183]'s non-negotiable framing constraint applied at the surface where it is
 * easiest to violate.
 */
@RestController
@RequestMapping("/v1/predict")
@Profile("api")
@ConditionalOnProperty(name = "bullpen.clickhouse.enabled", havingValue = "true")
public class PredictPitchTypeController {

  private final PitchTypePredictionService service;

  public PredictPitchTypeController(PitchTypePredictionService service) {
    this.service = service;
  }

  /**
   * A calibrated distribution over the seven pitch classes.
   *
   * <p>Deliberately no {@code winner} / {@code predictedType} field, unlike the pitch-OUTCOME
   * response. Exposing an argmax would invite exactly the reading [183] forbids, and at ~0.45 top-1
   * it would be wrong more often than right while looking authoritative.
   *
   * @param priorPitches how many career pitches the distribution was computed over. Surfaced
   *     because a prior over 12 pitches and one over 12,000 are not comparable, and the caller
   *     cannot otherwise tell them apart.
   */
  public record PitchTypePriorResponse(
      @Schema(
              description =
                  "Calibrated probability per pitch class (FF, SI, FC, SL, CU, CH, OFF). These sum"
                      + " to 1 and are a PRIOR over what this pitcher throws in this situation -"
                      + " not a prediction of the next pitch.")
          Map<String, Double> probabilities,
      String modelName,
      String servingVersion,
      @Schema(description = "Career pitches the prior was computed over, strictly before this one.")
          long priorPitches,
      @Schema(
              description =
                  "Routing plus inference time only, DELIBERATELY excluding the server-side"
                      + " history derivation (four ClickHouse reads) so it is comparable to the"
                      + " other heads' latency. Diffing this against wall-clock time will show a"
                      + " large gap; that gap is the derivation, not overhead.")
          long elapsedMicros,
      String correlationId) {}

  @Operation(
      summary = "Calibrated pitch-type PRIOR (not a next-pitch prediction)",
      description =
          "Returns a calibrated probability distribution over seven pitch classes for the"
              + " situation described. This is a PRIOR: top-1 accuracy is about 0.45 because pitch"
              + " selection is high-entropy, and the model is evaluated and promoted on"
              + " calibration error, never on accuracy. Arsenal features are derived server-side"
              + " from the pitcher's career history strictly before this pitch.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Calibrated distribution."),
    @ApiResponse(
        responseCode = "503",
        description =
            "No promoted champion for pitch_type_pre, or the career-prior snapshot is missing or"
                + " stale. Refusing rather than serving a prior computed over the wrong history.",
        content = @Content)
  })
  @PostMapping("/pitch-type")
  public PitchTypePriorResponse prior(@Valid @RequestBody PitchTypeRequest req) {
    PitchTypePredictionService.Served served = service.predict(req);
    return new PitchTypePriorResponse(
        served.probabilities(),
        served.modelName(),
        served.servingVersion(),
        served.priorPitches(),
        served.elapsedMicros(),
        MDC.get("correlation_id"));
  }
}
