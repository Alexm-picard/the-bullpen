package net.thebullpen.baseball.data;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import net.thebullpen.baseball.domain.PlayerPredictionRow;
import org.junit.jupiter.api.Test;

/**
 * Unit test for the private {@code parseWinner} JSON path in {@link PlayerPredictionsRepository}.
 * The repo's SQL is exercised under Testcontainers in a separate IT (when {@code
 * -Dbullpen.it.docker=true}); this test pins the parser logic that runs once per row.
 *
 * <p>Reflection-into-private is fine here — the parser is an implementation detail of the row
 * mapper, not a public API, but its behavior is load-bearing for what the UI renders. A public
 * helper would be over-promotion.
 */
class PlayerPredictionsRepositoryTest {

  @Test
  void parses_pitch_5class_winner_and_prob() throws Exception {
    PlayerPredictionRow row =
        invokeParse(
            "{\"probabilities\":{\"ball\":0.4,\"called_strike\":0.3,\"swinging_strike\":0.1,"
                + "\"foul\":0.1,\"in_play\":0.1},\"winner\":\"ball\"}");
    assertThat(row.winnerClass()).isEqualTo("ball");
    assertThat(row.winnerProb()).isEqualTo(0.4);
  }

  @Test
  void parses_batted_ball_single_prob() throws Exception {
    PlayerPredictionRow row = invokeParse("{\"probHr\":0.87}");
    assertThat(row.winnerClass()).isEqualTo("probHr");
    assertThat(row.winnerProb()).isEqualTo(0.87);
  }

  @Test
  void returns_nulls_on_blank_json() throws Exception {
    PlayerPredictionRow row = invokeParse("");
    assertThat(row.winnerClass()).isNull();
    assertThat(row.winnerProb()).isNull();
  }

  @Test
  void returns_nulls_on_malformed_json() throws Exception {
    PlayerPredictionRow row = invokeParse("not-json{");
    assertThat(row.winnerClass()).isNull();
    assertThat(row.winnerProb()).isNull();
  }

  @Test
  void returns_winner_with_null_prob_when_probabilities_missing_class() throws Exception {
    PlayerPredictionRow row =
        invokeParse("{\"probabilities\":{\"ball\":0.4},\"winner\":\"in_play\"}");
    assertThat(row.winnerClass()).isEqualTo("in_play");
    assertThat(row.winnerProb()).isNull();
  }

  /** Reach into the private static helper for unit testing — see class javadoc. */
  private static PlayerPredictionRow invokeParse(String json) throws Exception {
    Method m = PlayerPredictionsRepository.class.getDeclaredMethod("parseWinner", String.class);
    m.setAccessible(true);
    Object summary = m.invoke(null, json);
    // WinnerSummary is also private — pluck (cls, prob) reflectively into a row for assertions.
    Method clsM = summary.getClass().getDeclaredMethod("cls");
    Method probM = summary.getClass().getDeclaredMethod("prob");
    String cls = (String) clsM.invoke(summary);
    Double prob = (Double) probM.invoke(summary);
    return new PlayerPredictionRow(null, null, null, null, cls, prob, null, null);
  }
}
