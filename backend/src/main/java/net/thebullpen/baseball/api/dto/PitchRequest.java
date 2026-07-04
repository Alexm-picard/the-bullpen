package net.thebullpen.baseball.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * Request body for {@code POST /v1/predict/pitch[?head=pre|post]} — Phase 2a.8 + 2b.3.
 *
 * <p>Tier 1+2 fields are required (count, base state, identity). Tier 3 (rolling form) is optional
 * for both heads — callers without recent form data omit those fields and LightGBM treats them as
 * missing (Tier 3 has a documented 1-day lag per the leaf plan; Phase 3 introduces a worker job
 * that populates {@code pitcher_form_current} so the controller can look these up automatically).
 *
 * <p>Tier 4 (post-pitch) fields are optional in the DTO but <em>required by the controller</em>
 * when {@code ?head=post} (decision [35]: two heads, two distinct registered models). The
 * controller validates the cross-field rule explicitly and returns 400 with {@code
 * error.code=validation_failed} listing the missing fields. Validating in the controller (vs a
 * class-level bean validator) keeps the rule visible at the dispatch point.
 *
 * <p>Switch hitters ({@code batterStand="S"}) are rejected on purpose: the caller resolves S → L|R
 * based on matchup before calling, same convention as the toy endpoint.
 */
public record PitchRequest(
    @Schema(example = "1", description = "Balls in the count, 0-3.") @NotNull @Min(0) @Max(3)
        Integer countBalls,
    @Schema(example = "2", description = "Strikes in the count, 0-2.") @NotNull @Min(0) @Max(2)
        Integer countStrikes,
    @Schema(example = "1", description = "Outs in the inning, 0-2.") @NotNull @Min(0) @Max(2)
        Integer outs,
    @Schema(example = "6", description = "Inning number, 1-20.") @NotNull @Min(1) @Max(20)
        Integer inning,
    @Schema(example = "0", description = "Base-occupancy code, 0-7 (bitmask of occupied bases).")
        @NotNull
        @Min(0)
        @Max(7)
        Integer baseState,
    @Schema(
            example = "0",
            description = "Score differential from the pitching team's view, -30..30.")
        @NotNull
        @Min(-30)
        @Max(30)
        Integer scoreDiff,
    @Schema(example = "3", description = "Day of week, 0 (Sun) - 6 (Sat).") @NotNull @Min(0) @Max(6)
        Integer dow,
    @Schema(example = "R", description = "Pitcher throwing hand: L or R.")
        @NotBlank
        @Pattern(regexp = "L|R", message = "pitcherThrows must be 'L' or 'R'")
        String pitcherThrows,
    @Schema(example = "R", description = "Batter stand: L or R (resolve switch hitters upstream).")
        @NotBlank
        @Pattern(
            regexp = "L|R",
            message = "batterStand must be 'L' or 'R' (resolve switch hitters upstream)")
        String batterStand,
    @Schema(example = "NYY", description = "3-letter MLB park id.") @NotBlank String parkId,
    @Schema(example = "592789", description = "MLBAM pitcher id.") @NotNull @PositiveOrZero
        Long pitcherId,
    @Schema(example = "545361", description = "MLBAM batter id.") @NotNull @PositiveOrZero
        Long batterId,
    // Tier 3 form features — optional; null means "we don't have it yet, let the model handle NaN".
    @Schema(description = "Optional Tier 3 form: pitcher pitches thrown in the last 28 days.")
        @DecimalMin("0.0")
        @DecimalMax("10000.0")
        Double pitcherPitchesLast28d,
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
    // Tier 4 (post-pitch) — required only when head=post; controller enforces cross-field rule.
    @Schema(description = "Tier 4 (post-pitch): pitch type code. Required when head=post.")
        String pitchType,
    @DecimalMin("40.0") @DecimalMax("110.0") Double releaseSpeedMph,
    @DecimalMin("-5.0") @DecimalMax("5.0") Double plateXIn,
    @DecimalMin("-5.0") @DecimalMax("8.0") Double plateZIn,
    @DecimalMin("-5.0") @DecimalMax("5.0") Double pfxXIn,
    @DecimalMin("-5.0") @DecimalMax("5.0") Double pfxZIn,
    @DecimalMin("0.0") @DecimalMax("4000.0") Double spinRateRpm,
    @DecimalMin("0.0") @DecimalMax("360.0") Double spinAxisDeg,
    @DecimalMin("-5.0") @DecimalMax("5.0") Double releasePosXIn,
    @DecimalMin("0.0") @DecimalMax("10.0") Double releasePosZIn) {}
