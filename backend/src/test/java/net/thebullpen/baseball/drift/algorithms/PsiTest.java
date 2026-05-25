package net.thebullpen.baseball.drift.algorithms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Pure unit tests for {@link Psi}. */
class PsiTest {

  @Test
  void identical_continuous_distributions_yield_near_zero_psi() {
    Random r = new Random(42);
    double[] sample = new double[5000];
    for (int i = 0; i < sample.length; i++) {
      sample[i] = r.nextGaussian();
    }
    double psi = Psi.computeContinuous(sample, sample.clone(), 10);
    // PSI on the same data should be effectively 0 (epsilon smoothing introduces tiny noise).
    assertThat(psi).isCloseTo(0.0, within(1e-6));
  }

  @Test
  void shifted_continuous_distribution_yields_positive_psi() {
    Random r = new Random(42);
    int n = 10_000;
    double[] reference = new double[n];
    double[] actual = new double[n];
    for (int i = 0; i < n; i++) {
      reference[i] = r.nextGaussian();
      actual[i] = r.nextGaussian() + 1.0; // mean-shift of 1 std
    }
    double psi = Psi.computeContinuous(reference, actual, 10);
    // The mean-shift-of-1-std reference range per industry rule-of-thumb is roughly 0.1–0.5;
    // bin alignment + random sampling pushes it well above the "no drift" floor of 0.1.
    assertThat(psi).isGreaterThan(0.1);
  }

  @Test
  void large_shift_pushes_psi_into_significant_range() {
    Random r = new Random(42);
    int n = 10_000;
    double[] reference = new double[n];
    double[] actual = new double[n];
    for (int i = 0; i < n; i++) {
      reference[i] = r.nextGaussian();
      actual[i] = r.nextGaussian() + 3.0; // 3-sigma shift
    }
    double psi = Psi.computeContinuous(reference, actual, 10);
    assertThat(psi)
        .as("3σ mean shift should produce PSI well above the 0.25 'significant' threshold")
        .isGreaterThan(0.25);
  }

  @Test
  void identical_categorical_distributions_yield_zero_chi_squared() {
    Map<String, Integer> dist = Map.of("NYY", 1000, "BOS", 800, "LAD", 600);
    assertThat(Psi.computeCategorical(dist, Map.copyOf(dist))).isEqualTo(0.0);
  }

  @Test
  void categorical_with_new_value_in_actual_is_handled_gracefully() {
    // Reference has 3 parks; actual has a 4th. chi² stays finite.
    Map<String, Integer> reference = Map.of("NYY", 1000, "BOS", 800, "LAD", 600);
    Map<String, Integer> actual = Map.of("NYY", 1000, "BOS", 800, "LAD", 600, "NEW", 50);
    double chi2 = Psi.computeCategorical(reference, actual);
    assertThat(chi2).isFinite();
    assertThat(chi2).isGreaterThan(0.0);
  }

  @Test
  void categorical_with_disjoint_distributions_yields_high_chi_squared() {
    Map<String, Integer> reference = Map.of("A", 1000, "B", 1000);
    Map<String, Integer> actual = Map.of("C", 1000, "D", 1000);
    double chi2 = Psi.computeCategorical(reference, actual);
    // Each of 4 keys contributes (1000)^2 / 1000 = 1000 → total = 4000.
    assertThat(chi2).isEqualTo(4000.0);
  }

  @Test
  void quantile_edges_are_monotonically_increasing() {
    Random r = new Random(7);
    double[] sample = new double[1000];
    for (int i = 0; i < sample.length; i++) {
      sample[i] = r.nextDouble();
    }
    double[] edges = Psi.quantileEdges(sample, 10);
    assertThat(edges).hasSize(11);
    assertThat(edges[0]).isEqualTo(Double.NEGATIVE_INFINITY);
    assertThat(edges[10]).isEqualTo(Double.POSITIVE_INFINITY);
    for (int i = 1; i < edges.length - 1; i++) {
      assertThat(edges[i]).isGreaterThanOrEqualTo(edges[i - 1]);
    }
  }

  @Test
  void empty_reference_throws() {
    assertThatThrownBy(() -> Psi.computeContinuous(new double[0], new double[] {1.0}, 10))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void empty_actual_throws() {
    assertThatThrownBy(() -> Psi.computeContinuous(new double[] {1.0}, new double[0], 10))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void too_few_bins_throws() {
    assertThatThrownBy(() -> Psi.computeContinuous(new double[] {1.0, 2.0}, new double[] {1.0}, 1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void empty_categorical_distributions_return_zero() {
    assertThat(Psi.computeCategorical(Map.of(), Map.of())).isEqualTo(0.0);
  }

  private static org.assertj.core.data.Offset<Double> within(double v) {
    return org.assertj.core.data.Offset.offset(v);
  }
}
