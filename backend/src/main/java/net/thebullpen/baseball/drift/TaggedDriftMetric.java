package net.thebullpen.baseball.drift;

import java.time.Instant;

/**
 * One {@code drift_metrics} row WITH its V027 {@code tag} column - the ops-surface read shape
 * (E-4). Field names deliberately mirror {@link DriftMetric} one-for-one so the {@code
 * /v1/ops/drift} JSON stays backward-compatible and only GAINS {@code tag}; keeping this a separate
 * flat record (rather than adding {@code tag} to {@link DriftMetric}) leaves the batch jobs'
 * write-side record untouched - the tag is applied at the {@link DriftMetricsRepository} choke
 * point, never carried by the rows the jobs construct.
 *
 * <p>Why the ops surface needs the tag: decision [175]'s induced-drill rows persist in {@code
 * drift_metrics} as evidence (tag {@code induced-drill-*}). The public dashboard must be able to
 * label them - a synthetic PSI spike rendering as organic drift on a hiring-visible page is exactly
 * the dishonest display the tagging exists to prevent. Empty string = organic.
 */
public record TaggedDriftMetric(
    Instant computedAt,
    String modelName,
    long modelVersionId,
    MetricType metricType,
    String featureOrSegment,
    double metricValue,
    long sampleSize,
    Instant windowStart,
    Instant windowEnd,
    String tag) {

  public TaggedDriftMetric {
    if (tag == null) {
      tag = "";
    }
  }
}
