package net.thebullpen.baseball.drift;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Default {@link FeatureDistributionFetcher} — returns empty samples / counts. The real
 * ClickHouse-querying impl lands when {@code prediction_log} has traffic to query (same pattern as
 * {@code StubPairedPredictionFetcher} for 3b.4).
 *
 * <p>Empty results make {@link net.thebullpen.baseball.drift.jobs.PsiFeatureJob} skip writing a
 * drift row for that (model, feature) tuple, which is the right prod-without-real-impl behavior:
 * never write a drift value computed against zero data.
 */
@Component
public class StubFeatureDistributionFetcher implements FeatureDistributionFetcher {

  private static final Logger log = LoggerFactory.getLogger(StubFeatureDistributionFetcher.class);

  @Override
  public List<Double> fetchContinuous(
      String modelName,
      long modelVersionId,
      String featureName,
      Instant windowStart,
      Instant windowEnd) {
    log.debug(
        "FeatureDistributionFetcher stub (continuous): {}/{} feature={} window=[{}, {}] → []",
        modelName,
        modelVersionId,
        featureName,
        windowStart,
        windowEnd);
    return List.of();
  }

  @Override
  public Map<String, Integer> fetchCategorical(
      String modelName,
      long modelVersionId,
      String featureName,
      Instant windowStart,
      Instant windowEnd) {
    log.debug(
        "FeatureDistributionFetcher stub (categorical): {}/{} feature={} window=[{}, {}] → {{}}",
        modelName,
        modelVersionId,
        featureName,
        windowStart,
        windowEnd);
    return Map.of();
  }
}
