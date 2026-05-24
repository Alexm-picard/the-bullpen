package net.thebullpen.baseball.api.dto;

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
    Integer mcTrials,
    String modelName,
    String modelVersion,
    long latencyMicros,
    String correlationId) {}
