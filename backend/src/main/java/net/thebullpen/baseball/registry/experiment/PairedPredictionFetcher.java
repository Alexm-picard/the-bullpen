package net.thebullpen.baseball.registry.experiment;

import java.time.Instant;
import java.util.List;

/**
 * Data-extraction boundary for {@link ExperimentService#evaluate}. A real implementation queries
 * ClickHouse for {@code prediction_log} rows matching the experiment's models + time window, pivots
 * CHAMPION + SHADOW pairs by {@code request_id}, and joins each pair to the observed outcome from
 * the canonical truth source ({@code pitches.description} for pitch outcomes, {@code
 * pitches.events} mapped to HR/non-HR for batted ball).
 *
 * <p>Why a separate interface: it lets the experiment lifecycle ship without the ClickHouse-join
 * SQL being in scope. Tests inject a synthetic fetcher returning canned pairs; the real impl lands
 * when 3c's drift detection needs the same plumbing and the ClickHouse query is reused. Without
 * this seam, 3b.4 would have to wait on 3c.
 *
 * <p>Implementations return paired predictions for the {@code (modelName, championVersion,
 * challengerVersion)} triple in {@code [since, until]}. Late-arriving outcomes are filtered
 * upstream by {@code until = now - 24h} so the join is on settled truth (leaf "Known edge cases").
 */
public interface PairedPredictionFetcher {

  /**
   * Fetch + pair + truth-join predictions for the experiment. May return an empty list while the
   * sample-size target isn't met yet — {@link ExperimentService#complete} treats that as "not
   * enough data; refuse to complete."
   */
  List<PairedPrediction> fetch(
      String modelName,
      String championVersion,
      String challengerVersion,
      Instant since,
      Instant until);
}
