package net.thebullpen.baseball.drift;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import net.thebullpen.baseball.drift.TruthJoinedPredictionFetcher.TruthJoinedRow;

/**
 * Like {@link TruthJoinedPredictionFetcher} but bucketed by a segment dimension — for the 3c.5
 * weekly per-segment Brier breakdown. The real ClickHouse impl runs ONE aggregation query per
 * dimension (per leaf "Step-by-step task 2") rather than fanning out per segment value.
 *
 * <p>Same architectural seam as the other fetcher interfaces; {@link
 * StubSegmentedTruthJoinedPredictionFetcher} is the default.
 */
public interface SegmentedTruthJoinedPredictionFetcher {

  /**
   * Returns a map of segment-value → truth-joined rows for the {@code (modelName, versionId,
   * segmentDimension)} triple over the time window. Segment values are stringified (e.g. {@code
   * "NYY"} for {@code park_id}, {@code "R"} for {@code stand}, {@code "0-0"} for {@code
   * count_state}).
   */
  Map<String, List<TruthJoinedRow>> fetchBySegment(
      String modelName,
      long modelVersionId,
      String segmentDimension,
      Instant windowStart,
      Instant windowEnd);
}
