package net.thebullpen.baseball.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * Pure unit coverage for the truth-join's prediction-JSON parsing (step 5) - the mapper logic, with
 * no ClickHouse. The full round-trip lives in the Docker-gated {@link LivePitchesRepositoryIT}.
 */
class LivePitchesRepositoryParseTest {

  @Test
  void parsePrediction_extracts_the_class_map_and_winner() {
    LivePitchesRepository.Prediction p =
        LivePitchesRepository.parsePrediction(
            "{\"probabilities\":{\"ball\":0.5,\"called_strike\":0.3,\"in_play\":0.2},"
                + "\"winner\":\"ball\"}");

    assertEquals("ball", p.winner());
    assertEquals(3, p.classes().size());
    assertEquals(0.5, p.classes().get("ball"));
    assertEquals(0.2, p.classes().get("in_play"));
  }

  @Test
  void parsePrediction_returns_nulls_for_an_absent_prediction() {
    // LEFT JOIN miss yields "" (the "n/a" path); null is the JDBC equivalent.
    assertNull(LivePitchesRepository.parsePrediction("").classes());
    assertNull(LivePitchesRepository.parsePrediction("").winner());
    assertNull(LivePitchesRepository.parsePrediction(null).classes());
  }

  @Test
  void parsePrediction_swallows_malformed_json_rather_than_breaking_the_read() {
    LivePitchesRepository.Prediction p = LivePitchesRepository.parsePrediction("{not valid json");
    assertNull(p.classes());
    assertNull(p.winner());
  }

  @Test
  void humanizeStatus_titlecases_the_enum_name_for_display() {
    assertEquals("In Progress", LivePitchesRepository.humanizeStatus("IN_PROGRESS"));
    assertEquals("Mid Inning", LivePitchesRepository.humanizeStatus("MID_INNING"));
    assertEquals("Scheduled", LivePitchesRepository.humanizeStatus("SCHEDULED"));
    assertEquals("Unknown", LivePitchesRepository.humanizeStatus("UNKNOWN"));
    assertEquals("Unknown", LivePitchesRepository.humanizeStatus(""));
    assertEquals("Unknown", LivePitchesRepository.humanizeStatus(null));
  }
}
