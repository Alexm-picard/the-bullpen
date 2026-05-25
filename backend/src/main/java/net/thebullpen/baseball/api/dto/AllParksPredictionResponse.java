package net.thebullpen.baseball.api.dto;

import java.util.Map;

/**
 * Response body for {@code POST /v1/predict/batted-ball/all-parks} (leaf 4c.2).
 *
 * <p>{@code probHrByPark} maps each MLB park id (3-letter abbreviation) to the model's predicted
 * P(home run) for the given launch parameters at that park. The map has exactly 30 entries (one per
 * MLB park) and is sorted by park id alphabetically.
 *
 * <p>{@code modelName} + {@code modelVersion} carry the model identity so the UI can show "served
 * by ..." without a second round-trip. {@code latencyMicros} is total wall-clock for all 30
 * inferences combined.
 */
public record AllParksPredictionResponse(
    Map<String, Double> probHrByPark,
    String modelName,
    String modelVersion,
    long latencyMicros,
    String correlationId) {}
