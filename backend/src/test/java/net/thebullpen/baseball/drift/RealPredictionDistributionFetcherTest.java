package net.thebullpen.baseball.drift;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

/**
 * Unit coverage (no ClickHouse) of the payload-shape logic: pitch-family {@code probabilities}
 * parsing and batted-ball park-keyed map extraction. The DataSource is a never-connected
 * placeholder - the methods under test touch only the injected ObjectMapper.
 */
class RealPredictionDistributionFetcherTest {

  private final RealPredictionDistributionFetcher fetcher =
      new RealPredictionDistributionFetcher(new SimpleDriverDataSource(), new ObjectMapper());

  @Test
  void pitchProbabilitiesOf_returns_the_object_for_a_pitch_payload() {
    var node =
        fetcher.pitchProbabilitiesOf(
            "{\"probabilities\":{\"ball\":0.3,\"in_play\":0.7},\"winner\":\"in_play\"}");
    assertThat(node).isNotNull();
    assertThat(node.get("ball").asDouble()).isEqualTo(0.3);
    assertThat(node.get("in_play").asDouble()).isEqualTo(0.7);
  }

  @Test
  void pitchProbabilitiesOf_returns_null_for_non_pitch_and_malformed_payloads() {
    assertThat(fetcher.pitchProbabilitiesOf("{\"prob_hr\":0.12}")).isNull();
    assertThat(fetcher.pitchProbabilitiesOf("{\"probabilities\":0.5}")).isNull();
    assertThat(fetcher.pitchProbabilitiesOf("not json")).isNull();
    assertThat(fetcher.pitchProbabilitiesOf("")).isNull();
    assertThat(fetcher.pitchProbabilitiesOf(null)).isNull();
  }

  @Test
  void battedBall_extracts_requested_parks_5_vector_from_enriched_features() {
    String prediction = "{\"NYY\":[0.5,0.2,0.15,0.1,0.05],\"BOS\":[0.4,0.3,0.1,0.1,0.1]}";
    String features = "{\"request\":{\"launchSpeedMph\":100},\"parkContext\":\"NYY\"}";
    Map<String, List<Double>> perClass = new LinkedHashMap<>();
    int result = fetcher.tryBattedBallParkExtraction(prediction, features, null, perClass);
    assertThat(result).as("extraction should succeed").isEqualTo(1);
    assertThat(perClass).containsKeys("out", "1b", "2b", "3b", "hr");
    assertThat(perClass.get("hr")).containsExactly(0.05);
    assertThat(perClass.get("out")).containsExactly(0.5);
  }

  @Test
  void battedBall_skips_explorer_calls_with_empty_parkContext() {
    String prediction = "{\"NYY\":[0.5,0.2,0.15,0.1,0.05]}";
    String features = "{\"request\":{},\"parkContext\":\"\"}";
    Map<String, List<Double>> perClass = new LinkedHashMap<>();
    int result = fetcher.tryBattedBallParkExtraction(prediction, features, null, perClass);
    assertThat(result).as("explorer call should be unresolved").isEqualTo(-1);
    assertThat(perClass).isEmpty();
  }

  @Test
  void battedBall_returns_zero_for_non_park_keyed_payload() {
    String prediction = "{\"probabilities\":{\"ball\":0.3}}";
    Map<String, List<Double>> perClass = new LinkedHashMap<>();
    int result = fetcher.tryBattedBallParkExtraction(prediction, "{}", null, perClass);
    assertThat(result).as("not a park-keyed map").isEqualTo(0);
    assertThat(perClass).isEmpty();
  }

  @Test
  void battedBall_returns_negative_one_for_legacy_rows_without_parkContext() {
    String prediction = "{\"NYY\":[0.5,0.2,0.15,0.1,0.05]}";
    String features = "{\"launchSpeedMph\":100}";
    Map<String, List<Double>> perClass = new LinkedHashMap<>();
    int result = fetcher.tryBattedBallParkExtraction(prediction, features, null, perClass);
    assertThat(result).as("legacy row with no parkContext and no game_id fallback").isEqualTo(-1);
  }
}
