package net.thebullpen.baseball.drift;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

/**
 * Unit coverage (no ClickHouse) of the load-bearing shape-skip logic: which {@code prediction_log}
 * payloads carry a per-class {@code probabilities} object and which are skipped. The DataSource is
 * a never-connected placeholder — {@code probabilitiesOf} touches only the injected ObjectMapper.
 */
class RealPredictionDistributionFetcherTest {

  private final RealPredictionDistributionFetcher fetcher =
      new RealPredictionDistributionFetcher(new SimpleDriverDataSource(), new ObjectMapper());

  @Test
  void probabilitiesOf_returns_the_object_for_a_pitch_payload() {
    var node =
        fetcher.probabilitiesOf(
            "{\"probabilities\":{\"ball\":0.3,\"in_play\":0.7},\"winner\":\"in_play\"}");
    assertThat(node).isNotNull();
    assertThat(node.get("ball").asDouble()).isEqualTo(0.3);
    assertThat(node.get("in_play").asDouble()).isEqualTo(0.7);
  }

  @Test
  void probabilitiesOf_returns_null_for_non_pitch_and_malformed_payloads() {
    // batted-ball single-park payload: no probabilities object.
    assertThat(fetcher.probabilitiesOf("{\"prob_hr\":0.12}")).isNull();
    // probabilities present but not an object.
    assertThat(fetcher.probabilitiesOf("{\"probabilities\":0.5}")).isNull();
    assertThat(fetcher.probabilitiesOf("not json")).isNull();
    assertThat(fetcher.probabilitiesOf("")).isNull();
    assertThat(fetcher.probabilitiesOf(null)).isNull();
  }
}
