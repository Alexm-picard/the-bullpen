package net.thebullpen.baseball.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
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
    @Schema(description = "Per-park P(home run), keyed by 3-letter park id (30 entries).")
        Map<String, Double> probHrByPark,
    @Schema(
            description =
                "Per-park predicted carry distance in feet; null (and omitted) for a"
                    + " probabilities-only champion.")
        Map<String, Double> carryFtByPark,
    @Schema(example = "battedball_outcome", description = "Serving model name.") String modelName,
    @Schema(example = "v2", description = "Serving model version.") String modelVersion,
    @Schema(example = "1240", description = "Total inference wall-clock time, microseconds.")
        long latencyMicros,
    @Schema(description = "Request correlation id echoed back for log tracing.")
        String correlationId) {}
