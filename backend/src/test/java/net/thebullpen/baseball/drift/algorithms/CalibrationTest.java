package net.thebullpen.baseball.drift.algorithms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class CalibrationTest {

  @Test
  void brier_dirac_on_truth_is_zero() {
    List<double[]> probs = List.of(new double[] {1.0, 0.0}, new double[] {0.0, 1.0});
    int[] truth = {0, 1};
    assertThat(Calibration.brier(probs, truth)).isEqualTo(0.0);
  }

  @Test
  void brier_uniform_on_uniform_truth_approaches_K_minus_1_over_K() {
    // Uniform K=5 prediction (each class = 0.2). Truth uniformly distributed.
    // Per-row Brier = (0.2-1)^2 + 4*(0.2-0)^2 = 0.64 + 0.16 = 0.8 averaged over 5 classes = 0.16.
    // (K-1)/K = 4/5 = 0.8 per ROW; the averaged-over-classes form gives 0.16.
    List<double[]> probs = new ArrayList<>();
    int[] truth = new int[1000];
    Random r = new Random(42);
    for (int i = 0; i < 1000; i++) {
      probs.add(new double[] {0.2, 0.2, 0.2, 0.2, 0.2});
      truth[i] = r.nextInt(5);
    }
    double brier = Calibration.brier(probs, truth);
    assertThat(brier).isCloseTo(0.16, within(0.001));
  }

  @Test
  void log_loss_perfect_prediction_is_zero() {
    List<double[]> probs = List.of(new double[] {1.0, 0.0, 0.0});
    int[] truth = {0};
    assertThat(Calibration.logLoss(probs, truth)).isCloseTo(0.0, within(1e-10));
  }

  @Test
  void log_loss_zero_probability_is_clamped_to_finite() {
    List<double[]> probs = List.of(new double[] {0.0, 1.0});
    int[] truth = {0};
    double v = Calibration.logLoss(probs, truth);
    assertThat(v).isFinite();
    assertThat(v).isCloseTo(-Math.log(1e-15), within(1e-6));
  }

  @Test
  void ece_perfect_calibration_is_zero() {
    // Two predictions, both at confidence 1.0, both correct → bin 9 avg_conf=1.0, accuracy=1.0.
    List<double[]> probs = List.of(new double[] {1.0, 0.0}, new double[] {0.0, 1.0});
    int[] truth = {0, 1};
    assertThat(Calibration.ece(probs, truth)).isEqualTo(0.0);
  }

  @Test
  void ece_systematic_overconfidence_is_positive() {
    // 4 predictions all at confidence 0.9 for class 0; only half correct → ECE = |0.9-0.5| = 0.4.
    List<double[]> probs = new ArrayList<>();
    int[] truth = {0, 0, 1, 1};
    for (int i = 0; i < 4; i++) {
      probs.add(new double[] {0.9, 0.1});
    }
    assertThat(Calibration.ece(probs, truth)).isCloseTo(0.4, within(1e-9));
  }

  @Test
  void mismatched_sizes_throw() {
    assertThatThrownBy(() -> Calibration.brier(List.of(new double[] {1.0, 0.0}), new int[] {0, 1}))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void empty_probs_throws() {
    assertThatThrownBy(() -> Calibration.brier(List.of(), new int[0]))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void out_of_range_truth_throws() {
    assertThatThrownBy(() -> Calibration.brier(List.of(new double[] {0.5, 0.5}), new int[] {5}))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static org.assertj.core.data.Offset<Double> within(double v) {
    return org.assertj.core.data.Offset.offset(v);
  }
}
