package net.thebullpen.baseball.drift;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Extracts the per-class probability distribution of the model's PREDICTIONS from the last 24h of
 * {@code prediction_log}. Same architectural seam as {@link FeatureDistributionFetcher} — tests
 * inject a synthetic impl; real ClickHouse-querying impl lands when {@code prediction_log} has
 * traffic.
 *
 * <p>The fetcher pivots the {@code prediction} JSON column (e.g. {@code {"ball":0.3,
 * "called_strike":0.2,...}}) into per-class numeric samples. {@link #fetchPerClassProbabilities}
 * returns a map of class-name → list of probabilities seen for that class across the window. {@link
 * PsiPredictionJob} runs PSI between each list and the training-time reference for the same class.
 */
public interface PredictionDistributionFetcher {

  /**
   * For each output class, return the list of predicted-probability values observed in the window.
   * Empty map ⇒ no traffic / stub fetcher; the job skips writing rows.
   */
  Map<String, List<Double>> fetchPerClassProbabilities(
      String modelName, long modelVersionId, Instant windowStart, Instant windowEnd);
}
