package net.thebullpen.baseball.registry.experiment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import net.thebullpen.baseball.registry.experiment.dto.PrimaryMetric;
import org.junit.jupiter.api.Test;

/** Pure unit tests for {@link MetricsComputer} — Brier / LogLoss / ECE arithmetic. */
class MetricsComputerTest {

  @Test
  void brier_perfect_predictions_returns_zero() {
    PairedPrediction p =
        new PairedPrediction(1L, new double[] {1.0, 0.0}, new double[] {1.0, 0.0}, 0);
    assertThat(MetricsComputer.brier(List.of(p), false)).isEqualTo(0.0);
    assertThat(MetricsComputer.brier(List.of(p), true)).isEqualTo(0.0);
  }

  @Test
  void brier_uniform_predictions_returns_known_value() {
    // 2-class uniform: probs=[0.5, 0.5], truth=0 → squared errors: (0.5-1)^2 + (0.5-0)^2 = 0.5
    // averaged over 2 classes = 0.25.
    PairedPrediction p =
        new PairedPrediction(1L, new double[] {0.5, 0.5}, new double[] {0.5, 0.5}, 0);
    assertThat(MetricsComputer.brier(List.of(p), false)).isEqualTo(0.25);
  }

  @Test
  void log_loss_perfect_prediction_returns_zero() {
    PairedPrediction p =
        new PairedPrediction(1L, new double[] {1.0, 0.0}, new double[] {0.99, 0.01}, 0);
    assertThat(MetricsComputer.logLoss(List.of(p), false)).isCloseTo(0.0, within(1e-10));
  }

  @Test
  void log_loss_zero_probability_is_clamped_to_finite() {
    // p_truth = 0 would be -infinity log loss; the 1e-15 clamp keeps it finite.
    PairedPrediction p =
        new PairedPrediction(1L, new double[] {0.0, 1.0}, new double[] {0.0, 1.0}, 0);
    double v = MetricsComputer.logLoss(List.of(p), false);
    assertThat(v).isFinite();
    assertThat(v).isCloseTo(-Math.log(1e-15), within(1e-6));
  }

  @Test
  void log_loss_better_predictor_returns_lower_value() {
    // Champion is 80% confident in truth; challenger 95%. Challenger's log loss must be lower.
    PairedPrediction p =
        new PairedPrediction(1L, new double[] {0.8, 0.2}, new double[] {0.95, 0.05}, 0);
    double champ = MetricsComputer.logLoss(List.of(p), false);
    double chall = MetricsComputer.logLoss(List.of(p), true);
    assertThat(chall).isLessThan(champ);
  }

  @Test
  void ece_perfect_calibration_returns_zero() {
    // Predictions at confidence 1.0 that are all correct → ECE = 0 (avg_conf = accuracy = 1).
    PairedPrediction p1 =
        new PairedPrediction(1L, new double[] {1.0, 0.0}, new double[] {1.0, 0.0}, 0);
    PairedPrediction p2 =
        new PairedPrediction(2L, new double[] {0.0, 1.0}, new double[] {0.0, 1.0}, 1);
    assertThat(MetricsComputer.ece(List.of(p1, p2), false)).isEqualTo(0.0);
  }

  @Test
  void ece_systematic_overconfidence_produces_nonzero() {
    // 4 predictions, all at 0.9 confidence in class 0, but only 50% correct → bin 9 has
    // avg_conf=0.9, accuracy=0.5 → ECE = |0.9 - 0.5| = 0.4.
    PairedPrediction p1 =
        new PairedPrediction(1L, new double[] {0.9, 0.1}, new double[] {0.9, 0.1}, 0); // correct
    PairedPrediction p2 =
        new PairedPrediction(2L, new double[] {0.9, 0.1}, new double[] {0.9, 0.1}, 0); // correct
    PairedPrediction p3 =
        new PairedPrediction(3L, new double[] {0.9, 0.1}, new double[] {0.9, 0.1}, 1); // wrong
    PairedPrediction p4 =
        new PairedPrediction(4L, new double[] {0.9, 0.1}, new double[] {0.9, 0.1}, 1); // wrong
    double ece = MetricsComputer.ece(List.of(p1, p2, p3, p4), false);
    assertThat(ece).isCloseTo(0.4, within(1e-9));
  }

  @Test
  void compute_routes_to_correct_metric_function() {
    PairedPrediction p =
        new PairedPrediction(1L, new double[] {0.5, 0.5}, new double[] {0.8, 0.2}, 0);
    assertThat(MetricsComputer.compute(PrimaryMetric.BRIER, List.of(p), false)).isEqualTo(0.25);
    assertThat(MetricsComputer.compute(PrimaryMetric.LOG_LOSS, List.of(p), false))
        .isCloseTo(-Math.log(0.5), within(1e-10));
  }

  @Test
  void compute_on_empty_list_throws() {
    assertThatThrownBy(() -> MetricsComputer.compute(PrimaryMetric.BRIER, List.of(), false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("empty");
  }

  @Test
  void argmax_returns_index_of_largest() {
    assertThat(MetricsComputer.argmax(new double[] {0.1, 0.6, 0.3})).isEqualTo(1);
    assertThat(MetricsComputer.argmax(new double[] {0.6, 0.1, 0.3})).isEqualTo(0);
    assertThat(MetricsComputer.argmax(new double[] {0.1, 0.3, 0.6})).isEqualTo(2);
  }

  private static org.assertj.core.data.Offset<Double> within(double v) {
    return org.assertj.core.data.Offset.offset(v);
  }
}
