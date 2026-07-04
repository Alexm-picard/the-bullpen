package net.thebullpen.baseball.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Response body for POST /v1/predict/batted-ball. */
public record PredictionResponse(
    @Schema(example = "0.7867", description = "Predicted probability of a home run, 0-1.")
        float probHr,
    @Schema(example = "_toy_batted_ball", description = "Serving model name.") String modelName,
    @Schema(example = "v0", description = "Serving model version.") String modelVersion,
    @Schema(example = "507", description = "Inference wall-clock time, microseconds.")
        long latencyMicros,
    @Schema(description = "Request correlation id echoed back for log tracing.")
        String correlationId) {}
