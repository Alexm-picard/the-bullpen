package net.thebullpen.baseball.drift;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Extracts the OBSERVED (post-deploy) distribution of one feature from the last 24h of {@code
 * prediction_log}. Same architectural seam as {@code PairedPredictionFetcher} in 3b.4: lets the
 * batch jobs ship without the ClickHouse-parsing SQL being in scope. Tests inject a synthetic
 * implementation; the real impl lands when {@code prediction_log} has real traffic to query.
 *
 * <p>The {@code features} column in {@code prediction_log} is currently a JSON String (V004); the
 * real implementation parses out the per-feature value in SQL (via {@code JSONExtractFloat} /
 * {@code JSONExtractString}) and returns either a numeric sample or a categorical count map per the
 * feature's kind.
 */
public interface FeatureDistributionFetcher {

  /** Numeric (continuous) feature: returns a sample of values from the time window. */
  List<Double> fetchContinuous(
      String modelName,
      long modelVersionId,
      String featureName,
      Instant windowStart,
      Instant windowEnd);

  /** Categorical feature: returns category → count over the time window. */
  Map<String, Integer> fetchCategorical(
      String modelName,
      long modelVersionId,
      String featureName,
      Instant windowStart,
      Instant windowEnd);
}
