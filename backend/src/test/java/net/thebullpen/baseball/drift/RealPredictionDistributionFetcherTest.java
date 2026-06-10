package net.thebullpen.baseball.drift;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit coverage (no ClickHouse) of the load-bearing shape-skip logic: which {@code prediction_log}
 * payloads carry a per-class {@code probabilities} object and which are skipped.
 */
class RealPredictionDistributionFetcherTest {

  @Test
  void probabilitiesOf_returns_the_object_for_a_pitch_payload() {
    var node =
        RealPredictionDistributionFetcher.probabilitiesOf(
            "{\"probabilities\":{\"ball\":0.3,\"in_play\":0.7},\"winner\":\"in_play\"}");
    assertThat(node).isNotNull();
    assertThat(node.get("ball").asDouble()).isEqualTo(0.3);
    assertThat(node.get("in_play").asDouble()).isEqualTo(0.7);
  }

  @Test
  void probabilitiesOf_returns_null_for_non_pitch_and_malformed_payloads() {
    // batted-ball single-park payload: no probabilities object.
    assertThat(RealPredictionDistributionFetcher.probabilitiesOf("{\"prob_hr\":0.12}")).isNull();
    // probabilities present but not an object.
    assertThat(RealPredictionDistributionFetcher.probabilitiesOf("{\"probabilities\":0.5}"))
        .isNull();
    assertThat(RealPredictionDistributionFetcher.probabilitiesOf("not json")).isNull();
    assertThat(RealPredictionDistributionFetcher.probabilitiesOf("")).isNull();
    assertThat(RealPredictionDistributionFetcher.probabilitiesOf(null)).isNull();
  }
}
