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
 * Request body for {@code POST /v1/predict/pitch} — Phase 2a.8.
 *
 * <p>Tier 1+2 fields are required (count, base state, identity). Tier 3 (rolling form) is optional
 * for v1 — callers without recent form data omit those fields and LightGBM treats them as missing
 * (Tier 3 has a documented 1-day lag per the leaf plan; Phase 3 introduces a worker job that
 * populates {@code pitcher_form_current} so the controller can look these up automatically).
 *
 * <p>Switch hitters ({@code batterStand="S"}) are rejected on purpose: the caller resolves S → L|R
 * based on matchup before calling, same convention as the toy endpoint.
 */
public record PitchRequest(
    @NotNull @Min(0) @Max(3) Integer countBalls,
    @NotNull @Min(0) @Max(2) Integer countStrikes,
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
    // Tier 3 form features — optional; null means "we don't have it yet, let the model handle NaN".
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
    @DecimalMin("0.0") @DecimalMax("1.0") Double batterInplayRateStd) {}
