package net.thebullpen.baseball.api.dto;

import java.util.Map;

/**
 * Response body for {@code POST /v1/predict/pitch} — Phase 2a.8.
 *
 * <p>{@code probabilities} is the calibrated 5-class distribution (sums to 1.0 within 1e-5). {@code
 * winner} is the argmax class label for convenience — the frontend can derive it but shipping it
 * explicitly keeps the contract self-describing.
 */
public record PitchPredictionResponse(
    Map<String, Double> probabilities,
    String winner,
    String modelName,
    String modelVersion,
    long latencyMicros,
    String correlationId) {}
