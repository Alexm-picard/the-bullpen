package net.thebullpen.baseball.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Request body for {@code POST /v1/predict/batted-ball/all-parks} against the real per-park outcome
 * model (decision [146]). Carries the seven inputs {@code FeaturePipelineBattedBall.Request} needs;
 * the 15 model features (stand + base-state one-hots) are derived downstream.
 *
 * <p>No {@code parkId} - the park axis is the model's OUTPUT dimension, so the response is a
 * per-park map. No {@code releaseSpeed} - the outcome model is post-contact. Switch hitters ({@code
 * stand="S"}) are rejected: resolve to {@code L|R} upstream, matching the toy {@code
 * BattedBallRequest} contract. {@code baseState} is the 0-7 base-occupancy code; {@code outs} 0-2.
 */
public record AllParksOutcomeRequest(
    @Schema(example = "104.5", description = "Exit velocity off the bat, mph.")
        @NotNull
        @DecimalMin("0.0")
        @DecimalMax("130.0")
        Double launchSpeedMph,
    @Schema(example = "28.0", description = "Vertical launch angle, degrees.")
        @NotNull
        @DecimalMin("-90.0")
        @DecimalMax("90.0")
        Double launchAngleDeg,
    @Schema(example = "12.0", description = "Horizontal spray angle, degrees (- pull, + oppo).")
        @NotNull
        @DecimalMin("-90.0")
        @DecimalMax("90.0")
        Double sprayAngleDeg,
    @Schema(example = "405.0", description = "Observed hit distance, feet.")
        @NotNull
        @DecimalMin("0.0")
        @DecimalMax("600.0")
        Double hitDistanceFt,
    @Schema(example = "R", description = "Batter stand: L or R (resolve switch hitters upstream).")
        @NotBlank
        @Pattern(
            regexp = "L|R",
            message = "stand must be 'L' or 'R' (resolve switch hitters upstream)")
        String stand,
    @Schema(example = "0", description = "Base-occupancy code, 0-7 (bitmask of occupied bases).")
        @NotNull
        @Min(0)
        @Max(7)
        Integer baseState,
    @Schema(example = "1", description = "Number of outs, 0-2.") @NotNull @Min(0) @Max(2)
        Integer outs) {}
