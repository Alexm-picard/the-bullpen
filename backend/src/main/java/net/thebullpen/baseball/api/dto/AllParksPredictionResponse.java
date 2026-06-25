package net.thebullpen.baseball.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/**
 * Response body for {@code POST /v1/predict/batted-ball/all-parks} (leaf 4c.2).
 *
 * <p>{@code probHrByPark} maps each MLB park id (3-letter abbreviation) to the model's predicted
 * P(home run) for the given launch parameters at that park. The map has exactly 30 entries (one per
 * MLB park) and is sorted by park id alphabetically.
 *
 * <p>{@code carryFtByPark} (Phase 4) maps each park id to the model's predicted carry distance in
 * FEET for the same launch parameters. It is {@code null} when the serving champion has no carry
 * head (a probabilities-only model) - clients fall back to their own estimate in that case. When
 * present it has the same 30 park keys as {@code probHrByPark}. Omitted from the JSON when null
 * (Jackson default), so the response shape is unchanged for a carry-less champion.
 *
 * <p>{@code modelName} + {@code modelVersion} carry the model identity so the UI can show "served
 * by ..." without a second round-trip. {@code latencyMicros} is total wall-clock for all 30
 * inferences combined.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AllParksPredictionResponse(
    Map<String, Double> probHrByPark,
    Map<String, Double> carryFtByPark,
    String modelName,
    String modelVersion,
    long latencyMicros,
    String correlationId) {}
