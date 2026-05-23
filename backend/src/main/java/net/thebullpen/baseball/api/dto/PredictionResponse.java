package net.thebullpen.baseball.api.dto;

/** Response body for POST /v1/predict/batted-ball. */
public record PredictionResponse(
    float probHr,
    String modelName,
    String modelVersion,
    long latencyMicros,
    String correlationId) {}
