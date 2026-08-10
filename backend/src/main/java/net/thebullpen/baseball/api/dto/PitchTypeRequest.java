package net.thebullpen.baseball.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/**
 * Wire request for the pitch-TYPE prior (decision [183]).
 *
 * <p>THESE FIELD NAMES ARE A DATA CONTRACT, NOT A STYLE CHOICE. This record is what {@code
 * serializeFeatures} writes into {@code prediction_log.features}, and PSI joins the observed
 * distribution to its reference BY EXACT KEY. A field renamed here contributes nothing to the drift
 * surface and raises nothing - the surface is simply empty, which looks identical to a healthy one.
 *
 * <p>The eleven Tier-S names below must match the {@code pitch_type_pre} entry in {@code
 * registry_client/distributions.py} exactly, and {@code
 * training/tests/pitch_type/test_champion_drift_keys.py} asserts it. Note in particular {@code
 * stand} and {@code pThrows}: BOTH pitch-outcome heads spell these {@code batterStand} and {@code
 * pitcherThrows}, so copying the neighbouring DTO is the natural move and is wrong.
 *
 * <p>The identity fields are NOT drift-watched and are excluded deliberately in that same config.
 * They exist because the career-expanding ARS features are derived server-side from the pitcher's
 * history rather than supplied by the caller, and locating "strictly before this pitch" needs the
 * full key: a doubleheader puts the same pitcher in two games on one date, so game_id alone is not
 * enough.
 */
@Schema(
    description =
        "Pre-pitch context for a pitch-TYPE PRIOR. This returns a calibrated probability"
            + " distribution over seven pitch classes; it is not a prediction of which pitch will"
            + " be thrown. Arsenal and sequence features are derived server-side from the"
            + " pitcher's career history and are not supplied here.")
public record PitchTypeRequest(
    // --- identity: needed to derive career history, never drift-watched --------------------
    @NotNull @Schema(description = "MLBAM pitcher id, used to locate his career arsenal.")
        Long pitcherId,
    @NotNull @Schema(description = "Game pk. With gameDate it disambiguates a doubleheader.")
        Long gameId,
    @NotNull @Schema(description = "Game date (ET). Bounds the career-expanding scan.")
        LocalDate gameDate,
    @Min(0) @Schema(description = "At-bat index within the game.") int atBatIndex,
    @Min(1) @Schema(description = "Pitch number within the at-bat.") int pitchNumber,

    // --- Tier S: the eleven drift-watched keys. Names are load-bearing, see class javadoc ---
    @Min(0) @Max(3) int balls,
    @Min(0) @Max(2) int strikes,
    @Min(0) @Max(2) int outs,
    @Min(1) int inning,
    @Min(0) @Max(7) @Schema(description = "Base-occupancy state, 0-7.") int baseState,
    @NotNull @Schema(description = "Batter handedness, L or R.") String stand,
    @NotNull @Schema(description = "Pitcher handedness, L or R.") String pThrows,
    @NotNull @Schema(description = "Park id, e.g. PARK01.") String parkId,
    @Schema(description = "Times through the order; null at cold start.") Double timesThroughOrder,
    @Schema(description = "At-bat number in the game; null when unknown.") Double atBatNumberInGame,
    @Schema(description = "Times this batter has been faced today; null when unknown.")
        Double timesFacedToday) {}
