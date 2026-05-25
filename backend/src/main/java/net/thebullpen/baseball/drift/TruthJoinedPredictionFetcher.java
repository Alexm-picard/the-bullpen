package net.thebullpen.baseball.drift;

import java.time.Instant;
import java.util.List;

/**
 * Pulls {@code (predicted_probs, observed_truth_class)} pairs for the calibration job (3c.4). Joins
 * {@code prediction_log} (CHAMPION + SHADOW rows for the model in the window) against the canonical
 * truth source — {@code pitches.description} mapped to a 5-class index for pitch outcomes; {@code
 * pitches.events} mapped to {out, 1B, 2B, 3B, HR} for batted-ball.
 *
 * <p>Architectural seam — same as 3b.4's {@code PairedPredictionFetcher} + 3c.2's {@code
 * FeatureDistributionFetcher}: tests inject a synthetic impl; real ClickHouse-joining impl lands
 * when {@code prediction_log} has traffic and the {@code pitches} table is wired up for the truth
 * join.
 *
 * <p>The window applies {@code [windowStart, windowEnd]} with a built-in 24-hour settle delay (the
 * leaf "Known edge cases" — {@code request_at < now - 24h} ensures outcomes are present). Callers
 * pass {@code windowEnd = now - 24h} explicitly so the delay shows in the call site.
 */
public interface TruthJoinedPredictionFetcher {

  /**
   * Each result row: a predicted probability vector + the integer index of the observed truth
   * class. Aligned by position (same logical row).
   */
  record TruthJoinedRow(double[] probs, int truthClass) {}

  List<TruthJoinedRow> fetch(
      String modelName, long modelVersionId, Instant windowStart, Instant windowEnd);
}
