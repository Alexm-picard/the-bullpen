package net.thebullpen.baseball.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

/**
 * Response body for {@code POST /v1/predict/pitch} — Phase 2a.8.
 *
 * <p>{@code probabilities} is the calibrated 5-class distribution (sums to 1.0 within 1e-5). {@code
 * winner} is the argmax class label for convenience — the frontend can derive it but shipping it
 * explicitly keeps the contract self-describing.
 */
public record PitchPredictionResponse(
    @Schema(description = "Calibrated per-class probability distribution (sums to 1.0).")
        Map<String, Double> probabilities,
    @Schema(example = "in_play", description = "Argmax class label of the distribution.")
        String winner,
    @Schema(example = "pitch_outcome_pre", description = "Serving model name.") String modelName,
    @Schema(example = "v2", description = "Serving model version.") String modelVersion,
    @Schema(example = "1830", description = "Inference wall-clock time, microseconds.")
        long latencyMicros,
    @Schema(description = "Request correlation id echoed back for log tracing.")
        String correlationId) {}
