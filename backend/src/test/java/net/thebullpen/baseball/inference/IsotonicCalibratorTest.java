package net.thebullpen.baseball.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Hand-computed checks of the {@link IsotonicCalibrator} clamp-then-linear-interp (B-workstream
 * B3).
 */
class IsotonicCalibratorTest {

  // x ascending; y the fitted monotone outputs.
  private final IsotonicCalibrator cal =
      new IsotonicCalibrator(new double[] {0.0, 0.5, 1.0}, new double[] {0.1, 0.2, 0.9});

  @Test
  void clamps_below_and_above_the_threshold_range() {
    assertEquals(0.1, cal.transform(-3.0), 1e-12, "below x[0] clamps to y[0]");
    assertEquals(0.9, cal.transform(2.0), 1e-12, "above x[last] clamps to y[last]");
  }

  @Test
  void returns_exact_knot_values() {
    assertEquals(0.1, cal.transform(0.0), 1e-12);
    assertEquals(0.2, cal.transform(0.5), 1e-12);
    assertEquals(0.9, cal.transform(1.0), 1e-12);
  }

  @Test
  void linearly_interpolates_between_knots() {
    // midpoint of [0.0, 0.5] -> 0.1 + 0.5*(0.2-0.1)
    assertEquals(0.15, cal.transform(0.25), 1e-12);
    // midpoint of [0.5, 1.0] -> 0.2 + 0.5*(0.9-0.2)
    assertEquals(0.55, cal.transform(0.75), 1e-12);
  }

  @Test
  void single_threshold_is_constant() {
    IsotonicCalibrator flat = new IsotonicCalibrator(new double[] {0.3}, new double[] {0.42});
    assertEquals(0.42, flat.transform(0.0), 1e-12);
    assertEquals(0.42, flat.transform(0.99), 1e-12);
  }
}
