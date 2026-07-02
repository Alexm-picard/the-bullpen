package net.thebullpen.baseball.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class IngestMetricsTest {

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final IngestMetrics metrics = new IngestMetrics(registry);

  @Test
  void lastPollGaugeTracksTheMostRecentPoll() {
    assertThat(registry.get(IngestMetrics.LAST_POLL_METRIC).gauge().value()).isEqualTo(0.0);
    Instant at = Instant.parse("2026-07-02T18:00:00Z");
    metrics.markPollCompleted(at);
    assertThat(registry.get(IngestMetrics.LAST_POLL_METRIC).gauge().value())
        .isEqualTo(at.getEpochSecond());
  }

  @Test
  void pitchCounterAccumulatesAndIgnoresEmptyBatches() {
    metrics.incrementPitchesIngested(3);
    metrics.incrementPitchesIngested(0);
    metrics.incrementPitchesIngested(2);
    assertThat(registry.get(IngestMetrics.PITCHES_METRIC).counter().count()).isEqualTo(5.0);
  }

  @Test
  void anomalyCounterIsPerReasonAndIgnoresNonPositiveCounts() {
    metrics.incrementParseAnomaly("unknown_game_status");
    metrics.incrementParseAnomalies("unknown_pitch_description", 2);
    metrics.incrementParseAnomalies("unknown_pitch_description", 0);
    assertThat(
            registry
                .get(IngestMetrics.ANOMALY_METRIC)
                .tag("reason", "unknown_game_status")
                .counter()
                .count())
        .isEqualTo(1.0);
    assertThat(
            registry
                .get(IngestMetrics.ANOMALY_METRIC)
                .tag("reason", "unknown_pitch_description")
                .counter()
                .count())
        .isEqualTo(2.0);
  }
}
