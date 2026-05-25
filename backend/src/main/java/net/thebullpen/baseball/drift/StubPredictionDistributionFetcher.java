package net.thebullpen.baseball.drift;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Default {@link PredictionDistributionFetcher} — returns empty map. Real impl lands when {@code
 * prediction_log} has real traffic to pivot.
 */
@Component
public class StubPredictionDistributionFetcher implements PredictionDistributionFetcher {

  private static final Logger log =
      LoggerFactory.getLogger(StubPredictionDistributionFetcher.class);

  @Override
  public Map<String, List<Double>> fetchPerClassProbabilities(
      String modelName, long modelVersionId, Instant windowStart, Instant windowEnd) {
    log.debug(
        "PredictionDistributionFetcher stub: {}/{} window=[{}, {}] → {{}}",
        modelName,
        modelVersionId,
        windowStart,
        windowEnd);
    return Map.of();
  }
}
