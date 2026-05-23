package net.thebullpen.baseball.api.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Request body for POST /v1/predict/batted-ball. Bean Validation bounds match the toy training
 * distribution — wider Statcast values exist but are out-of-distribution for v1's model.
 *
 * <p>Switch hitters ({@code stand="S"}) are rejected on purpose: the toy model has no
 * matchup-awareness; the caller must resolve {@code S} → {@code L|R} based on the matchup before
 * calling. Phase 2 introduces the matchup-aware pipeline and relaxes this.
 */
public record BattedBallRequest(
    @NotNull @DecimalMin("0.0") @DecimalMax("130.0") Double launchSpeedMph,
    @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") Double launchAngleDeg,
    @NotNull @DecimalMin("50.0") @DecimalMax("110.0") Double releaseSpeedMph,
    @NotBlank String parkId,
    @NotBlank
        @Pattern(
            regexp = "L|R",
            message = "stand must be 'L' or 'R' (resolve switch hitters upstream)")
        String stand) {}
