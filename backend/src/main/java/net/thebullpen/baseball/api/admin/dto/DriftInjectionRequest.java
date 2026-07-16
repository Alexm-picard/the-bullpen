package net.thebullpen.baseball.api.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Body for {@code POST /v1/admin/drift/induce} - the E-2 live-path induced-drift injector ([175]).
 * Every field is optional with a drill-sane default so the common case is a bare {@code POST}.
 *
 * @param modelName the CHAMPION to induce drift on (default {@code battedball_outcome}, the served
 *     family; only a registered active champion with a feature_distributions baseline is accepted).
 * @param n number of synthetic prediction_log rows to write (default 5000; dense enough to dominate
 *     the near-zero-traffic break window).
 * @param shiftSigmas how many baseline standard deviations to shift the drifted feature (default
 *     1.0 - the drill's canonical 1-sigma shift, which lands PSI well past the 0.25 NOTICE
 *     threshold).
 * @param lookbackHours spread {@code request_at} uniformly over the last N hours so the
 *     PsiFeatureJob 24h window captures the batch (default 20; run the injector within this many
 *     hours before the target 2 AM cron).
 * @param shiftFeature which continuous baseline feature to shift (default {@code launchSpeedMph}).
 */
public record DriftInjectionRequest(
    @Schema(example = "battedball_outcome") String modelName,
    @Schema(example = "5000") @Min(1) @Max(200_000) Integer n,
    @Schema(example = "1.0") @DecimalMin("0.1") @DecimalMax("10.0") Double shiftSigmas,
    @Schema(example = "20") @Min(1) @Max(48) Integer lookbackHours,
    @Schema(example = "launchSpeedMph") String shiftFeature) {

  public String modelNameOr(String fallback) {
    return modelName == null || modelName.isBlank() ? fallback : modelName;
  }

  public int nOr(int fallback) {
    return n == null ? fallback : n;
  }

  public double shiftSigmasOr(double fallback) {
    return shiftSigmas == null ? fallback : shiftSigmas;
  }

  public int lookbackHoursOr(int fallback) {
    return lookbackHours == null ? fallback : lookbackHours;
  }

  public String shiftFeatureOr(String fallback) {
    return shiftFeature == null || shiftFeature.isBlank() ? fallback : shiftFeature;
  }
}
