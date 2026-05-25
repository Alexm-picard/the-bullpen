package net.thebullpen.baseball.drift;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import net.thebullpen.baseball.drift.TruthJoinedPredictionFetcher.TruthJoinedRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Default segmented fetcher — empty map. Real impl lands when prediction_log has traffic. */
@Component
public class StubSegmentedTruthJoinedPredictionFetcher
    implements SegmentedTruthJoinedPredictionFetcher {

  private static final Logger log =
      LoggerFactory.getLogger(StubSegmentedTruthJoinedPredictionFetcher.class);

  @Override
  public Map<String, List<TruthJoinedRow>> fetchBySegment(
      String modelName,
      long modelVersionId,
      String segmentDimension,
      Instant windowStart,
      Instant windowEnd) {
    log.debug(
        "SegmentedTruthJoinedPredictionFetcher stub: {}/{} dim={} window=[{}, {}] → {{}}",
        modelName,
        modelVersionId,
        segmentDimension,
        windowStart,
        windowEnd);
    return Map.of();
  }
}
