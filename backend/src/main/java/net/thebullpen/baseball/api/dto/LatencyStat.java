package net.thebullpen.baseball.api.dto;

/**
 * Per-model serving-latency summary, served by {@code GET /v1/ops/latency} and read from the
 * ClickHouse {@code prediction_log.latency_ms} column. One row per {@code (modelName,
 * modelVersion)} that produced a logged prediction inside the lookback window.
 *
 * <p>Powers the Ops dashboard's Model-Fleet p99 column and the Latency Detail table — the first
 * real (non-fixture) latency numbers on that page. Percentiles are in milliseconds; {@code
 * sampleCount} is the number of logged predictions the quantiles were computed over (so the UI can
 * grey out a low-n row rather than over-trust a p99 from a handful of calls).
 */
public record LatencyStat(
    String modelName,
    String modelVersion,
    long sampleCount,
    double p50Ms,
    double p95Ms,
    double p99Ms,
    double p999Ms) {}
