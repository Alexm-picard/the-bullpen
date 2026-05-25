package net.thebullpen.baseball.drift;

import java.time.Instant;

/**
 * Pure data record mirroring one row of {@code drift_metrics} (V013). The 3c.2–3c.5 batch jobs
 * construct these and pass them in bulk to {@link DriftMetricsRepository#insertBatch}.
 *
 * <p>{@code modelVersionId} is the registry FK that joins back to {@code model_versions.id} — for
 * the "current LIVE model" alerts in 3c.7. {@code featureOrSegment} carries the secondary key
 * (feature name for PSI_FEATURE, segment id for SEGMENT_BRIER, empty string for the aggregate
 * {@link MetricType#BRIER} / {@link MetricType#PSI_PREDICTION} / {@link
 * MetricType#CALIBRATION_ERROR} variants).
 */
public record DriftMetric(
    Instant computedAt,
    String modelName,
    long modelVersionId,
    MetricType metricType,
    String featureOrSegment,
    double metricValue,
    long sampleSize,
    Instant windowStart,
    Instant windowEnd) {

  public DriftMetric {
    if (modelName == null || modelName.isBlank()) {
      throw new IllegalArgumentException("modelName must not be blank");
    }
    if (sampleSize < 0) {
      throw new IllegalArgumentException("sampleSize must be >= 0; got " + sampleSize);
    }
    if (windowEnd != null && windowStart != null && windowEnd.isBefore(windowStart)) {
      throw new IllegalArgumentException(
          "windowEnd " + windowEnd + " must be >= windowStart " + windowStart);
    }
    if (featureOrSegment == null) {
      featureOrSegment = "";
    }
  }
}
