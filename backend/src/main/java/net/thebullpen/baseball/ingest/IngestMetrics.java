package net.thebullpen.baseball.ingest;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Micrometer wrappers for the live-ingest path (M1 task 5). The poller previously had ZERO
 * instrumentation, so a stuck poll or a feed-vocabulary change during a live game was invisible
 * until the drift postmortem dataset came up short. Names follow the {@link
 * net.thebullpen.baseball.inference.InferenceMetrics} idiom of exact Prometheus-final names:
 *
 * <ul>
 *   <li>{@code bullpen_ingest_last_poll_timestamp_seconds} - gauge, epoch seconds of the last
 *       successfully fetched+parsed game poll. Drives the LivePollerStuck alert ({@code time() -
 *       gauge > 300} during an active game).
 *   <li>{@code bullpen_ingest_pitches_total} - counter, pitches written to {@code pitches_live}.
 *   <li>{@code bullpen_ingest_parse_anomalies_total{reason}} - counter, the schema-drift tripwire:
 *       increments when the MLB feed hands back something the parser degrades on (unknown game
 *       status, missing gameDate, a pitch-result vocabulary word we map to "unknown"). Sustained
 *       nonzero rate during a live game means the feed schema moved under us.
 * </ul>
 */
@Component
@Profile("worker")
public class IngestMetrics {

  static final String LAST_POLL_METRIC = "bullpen_ingest_last_poll_timestamp_seconds";
  static final String PITCHES_METRIC = "bullpen_ingest_pitches_total";
  static final String ANOMALY_METRIC = "bullpen_ingest_parse_anomalies_total";
  static final String POST_TIER4_INCOMPLETE_METRIC = "bullpen_ingest_post_tier4_incomplete_total";

  private final MeterRegistry registry;
  private final AtomicLong lastPollEpochSeconds = new AtomicLong(0);
  private final Counter pitchesIngested;
  private final Counter postTier4Incomplete;
  private final ConcurrentHashMap<String, Counter> anomalies = new ConcurrentHashMap<>();

  public IngestMetrics(MeterRegistry registry) {
    this.registry = registry;
    Gauge.builder(LAST_POLL_METRIC, lastPollEpochSeconds, AtomicLong::get)
        .description("Epoch seconds of the last successfully fetched+parsed live game poll")
        .register(registry);
    this.pitchesIngested =
        Counter.builder(PITCHES_METRIC)
            .description("Pitches written to pitches_live by the live poller")
            .register(registry);
    this.postTier4Incomplete =
        Counter.builder(POST_TIER4_INCOMPLETE_METRIC)
            .description(
                "Completed pitches skipped for post prediction because their Tier-4 fit was"
                    + " incomplete")
            .register(registry);
  }

  /** A game poll fetched and parsed successfully (regardless of whether it carried new pitches). */
  public void markPollCompleted(Instant at) {
    lastPollEpochSeconds.set(at.getEpochSecond());
  }

  public void incrementPitchesIngested(int count) {
    if (count > 0) {
      pitchesIngested.increment(count);
    }
  }

  /**
   * A completed pitch was skipped for post prediction because its derived Tier-4 fit (movement /
   * spin / release position) or its resolved park was incomplete (F2.1a) - never fed NaN to the
   * post head.
   */
  public void incrementPostTier4Incomplete() {
    postTier4Incomplete.increment();
  }

  /**
   * The feed handed back something the parser degraded on. {@code reason} is a low-cardinality
   * label: unknown_game_status, missing_game_date, unknown_pitch_description.
   */
  public void incrementParseAnomaly(String reason) {
    incrementParseAnomalies(reason, 1);
  }

  public void incrementParseAnomalies(String reason, long count) {
    if (count <= 0) {
      return;
    }
    anomalies
        .computeIfAbsent(
            reason, r -> Counter.builder(ANOMALY_METRIC).tag("reason", r).register(registry))
        .increment(count);
  }
}
