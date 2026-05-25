package net.thebullpen.baseball.drift;

import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Default {@link TruthJoinedPredictionFetcher} — empty list. Real impl lands in 3c follow-up. */
@Component
public class StubTruthJoinedPredictionFetcher implements TruthJoinedPredictionFetcher {

  private static final Logger log = LoggerFactory.getLogger(StubTruthJoinedPredictionFetcher.class);

  @Override
  public List<TruthJoinedRow> fetch(
      String modelName, long modelVersionId, Instant windowStart, Instant windowEnd) {
    log.debug(
        "TruthJoinedPredictionFetcher stub: {}/{} window=[{}, {}] → []",
        modelName,
        modelVersionId,
        windowStart,
        windowEnd);
    return List.of();
  }
}
