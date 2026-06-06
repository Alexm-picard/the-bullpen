package net.thebullpen.baseball.data;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit test for the package-private binning + JSON-probability parser in {@link
 * CalibrationRepository}. SQL itself is exercised under Testcontainers in a separate IT (when
 * {@code -Dbullpen.it.docker=true}); this pins the bin-edge arithmetic and parser behavior.
 */
class CalibrationRepositoryTest {

  @Test
  void binIndexFor_assigns_p_to_correct_bin_with_10_bins() {
    assertThat(CalibrationRepository.binIndexFor(0.0)).isEqualTo(0);
    assertThat(CalibrationRepository.binIndexFor(0.05)).isEqualTo(0);
    assertThat(CalibrationRepository.binIndexFor(0.1)).isEqualTo(1);
    assertThat(CalibrationRepository.binIndexFor(0.499)).isEqualTo(4);
    assertThat(CalibrationRepository.binIndexFor(0.5)).isEqualTo(5);
    assertThat(CalibrationRepository.binIndexFor(0.9999)).isEqualTo(9);
  }

  @Test
  void binIndexFor_clamps_boundary_values() {
    assertThat(CalibrationRepository.binIndexFor(1.0)).isEqualTo(9);
    assertThat(CalibrationRepository.binIndexFor(2.0)).isEqualTo(9);
    assertThat(CalibrationRepository.binIndexFor(-0.5)).isEqualTo(0);
    assertThat(CalibrationRepository.binIndexFor(Double.NaN)).isEqualTo(0);
  }

  @Test
  void parseWinnerProb_extracts_5class_winner() {
    Double p =
        CalibrationRepository.parseWinnerProb(
            "{\"probabilities\":{\"ball\":0.42,\"called_strike\":0.3,\"swinging_strike\":0.1,"
                + "\"foul\":0.1,\"in_play\":0.08},\"winner\":\"ball\"}");
    assertThat(p).isEqualTo(0.42);
  }

  @Test
  void parseWinnerProb_extracts_single_prob_payload() {
    assertThat(CalibrationRepository.parseWinnerProb("{\"probHr\":0.87}")).isEqualTo(0.87);
    assertThat(CalibrationRepository.parseWinnerProb("{\"prob_hr\":0.42}")).isEqualTo(0.42);
  }

  @Test
  void parseWinnerProb_ignores_sibling_numeric_fields_in_single_prob_payload() {
    // DEF-M4: a numeric sibling (a version, a latency) ahead of the prob must NOT be returned as
    // the winner probability. The probability is looked up by name, not by JSON field order.
    assertThat(CalibrationRepository.parseWinnerProb("{\"version\":2,\"prob_hr\":0.87}"))
        .isEqualTo(0.87);
    assertThat(CalibrationRepository.parseWinnerProb("{\"latency_ms\":12.5,\"prob_hr\":0.31}"))
        .isEqualTo(0.31);
    // No known prob key present -> null (not the stray 0.99).
    assertThat(CalibrationRepository.parseWinnerProb("{\"score\":0.99}")).isNull();
  }

  @Test
  void parseWinnerProb_returns_null_on_blank_or_malformed() {
    assertThat(CalibrationRepository.parseWinnerProb(null)).isNull();
    assertThat(CalibrationRepository.parseWinnerProb("")).isNull();
    assertThat(CalibrationRepository.parseWinnerProb("not-json{")).isNull();
  }

  @Test
  void parseWinnerProb_returns_null_when_winner_class_missing_in_probabilities() {
    assertThat(
            CalibrationRepository.parseWinnerProb(
                "{\"probabilities\":{\"ball\":0.4},\"winner\":\"in_play\"}"))
        .isNull();
  }
}
