package net.thebullpen.baseball.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Response body for the forward simulator endpoints — Phase 2a.9.
 *
 * <p>{@code method} is {@code "analytical"} or {@code "monte_carlo"}. {@code expectedPitches},
 * {@code pWalk}, {@code pStrikeout}, {@code pInPlay} are evaluated at the request's starting count.
 * {@code mcTrials} is populated only for the Monte-Carlo endpoint.
 */
public record SimulateResponse(
    String method,
    int startBalls,
    int startStrikes,
    double expectedPitches,
    double pWalk,
    double pStrikeout,
    double pInPlay,
    @Schema(
            nullable = true,
            description = "Monte-Carlo trial count; null for the analytical method.")
        Integer mcTrials,
    String modelName,
    String modelVersion,
    @Schema(
            description =
                "How the model was served. Always \"unrouted-diagnostic\": the simulator pins one"
                    + " artifact and calls it directly (NOT through the A/B router), because the"
                    + " per-state Markov solve requires every probe to come from the same model"
                    + " version - routing could stitch a mid-request promotion/flip across states.")
        String servingMode,
    @Schema(
            nullable = true,
            description =
                "The served model's registry stage (e.g. \"shadow\"), or null when the pinned"
                    + " artifact has no matching registry row. Informational: the simulator serves"
                    + " the pinned artifact directly regardless of stage.")
        String registryStage,
    long latencyMicros,
    String correlationId) {}
