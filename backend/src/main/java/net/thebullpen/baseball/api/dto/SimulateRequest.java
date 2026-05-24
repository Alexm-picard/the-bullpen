package net.thebullpen.baseball.api.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * Request body for the forward simulator endpoints — Phase 2a.9.
 *
 * <p>Same identity + Tier 3 fields as {@link PitchRequest}, but no per-pitch {@code (countBalls,
 * countStrikes)} — the simulator drives the count itself. {@code startBalls} / {@code startStrikes}
 * pick the starting state in the 12-state transient block.
 *
 * <p>{@code mcTrials} only applies to the Monte-Carlo endpoint; ignored by the analytical solver.
 * Bounded at 100K to keep p99 latency under a tenth of a second.
 */
public record SimulateRequest(
    @NotNull @Min(0) @Max(3) Integer startBalls,
    @NotNull @Min(0) @Max(2) Integer startStrikes,
    @NotNull @Min(0) @Max(2) Integer outs,
    @NotNull @Min(1) @Max(20) Integer inning,
    @NotNull @Min(0) @Max(7) Integer baseState,
    @NotNull @Min(-30) @Max(30) Integer scoreDiff,
    @NotNull @Min(0) @Max(6) Integer dow,
    @NotBlank @Pattern(regexp = "L|R", message = "pitcherThrows must be 'L' or 'R'")
        String pitcherThrows,
    @NotBlank
        @Pattern(
            regexp = "L|R",
            message = "batterStand must be 'L' or 'R' (resolve switch hitters upstream)")
        String batterStand,
    @NotBlank String parkId,
    @NotNull @PositiveOrZero Long pitcherId,
    @NotNull @PositiveOrZero Long batterId,
    // Tier 3 form features — optional; null → NaN to ONNX (LightGBM handles natively).
    @DecimalMin("0.0") @DecimalMax("10000.0") Double pitcherPitchesLast28d,
    @DecimalMin("0.0") @DecimalMax("300.0") Double pitcherPitchesInGame,
    @DecimalMin("0.0") @DecimalMax("400.0") Double daysSinceLastAppearance,
    @DecimalMin("0.0") @DecimalMax("1.0") Double pitcherStrikeRate28d,
    @DecimalMin("0.0") @DecimalMax("1.0") Double pitcherSwstrikeRate28d,
    @DecimalMin("0.0") @DecimalMax("1.0") Double pitcherInplayRate28d,
    @DecimalMin("0.0") @DecimalMax("1.0") Double pitcherStrikeRateStd,
    @DecimalMin("0.0") @DecimalMax("1.0") Double batterStrikeRate28d,
    @DecimalMin("0.0") @DecimalMax("1.0") Double batterInplayRate28d,
    @DecimalMin("0.0") @DecimalMax("1.0") Double batterBallRate28d,
    @DecimalMin("0.0") @DecimalMax("1.0") Double batterInplayRateStd,
    // Monte-Carlo trials (ignored by the analytical endpoint).
    @Min(1) @Max(100_000) Integer mcTrials,
    // RNG seed for MC determinism. Optional — null uses System.nanoTime().
    Long mcSeed) {}
