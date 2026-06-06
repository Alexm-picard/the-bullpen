package net.thebullpen.baseball.inference;

import java.util.Arrays;

/**
 * Java port of one fitted scikit-learn {@code IsotonicRegression} (B-workstream B3).
 *
 * <p>A fitted isotonic regression with {@code out_of_bounds="clip"} is, at transform time, exactly
 * piecewise-linear interpolation over its thresholds: clamp the input to {@code [x[0], x[last]]},
 * then linearly interpolate {@code (x_thresholds, y_thresholds)} - which is what {@code sklearn}'s
 * {@code interp1d(kind="linear", fill_value=(y[0], y[last]))} does. The thresholds are serialized
 * per outcome in each model's {@code calibrator.json} ({@code x_thresholds} / {@code
 * y_thresholds}); {@code y_min}/{@code y_max} are already baked into the fitted thresholds, so they
 * need no separate handling here.
 *
 * <p>Thresholds are assumed strictly ascending in {@code x} (scikit-learn's {@code X_thresholds_}).
 */
public final class IsotonicCalibrator {

  private final double[] x;
  private final double[] y;

  public IsotonicCalibrator(double[] xThresholds, double[] yThresholds) {
    if (xThresholds.length != yThresholds.length) {
      throw new IllegalArgumentException(
          "x/y threshold length mismatch: " + xThresholds.length + " vs " + yThresholds.length);
    }
    if (xThresholds.length == 0) {
      throw new IllegalArgumentException("isotonic calibrator needs at least one threshold");
    }
    this.x = xThresholds.clone();
    this.y = yThresholds.clone();
  }

  /** Calibrate one raw probability via clamp-then-linear-interpolation over the thresholds. */
  public double transform(double raw) {
    int n = x.length;
    if (n == 1 || raw <= x[0]) {
      return y[0];
    }
    if (raw >= x[n - 1]) {
      return y[n - 1];
    }
    int idx = Arrays.binarySearch(x, raw);
    if (idx >= 0) {
      return y[idx]; // exact knot
    }
    int hi = -idx - 1; // insertion point: x[hi-1] < raw < x[hi]
    int lo = hi - 1;
    double span = x[hi] - x[lo];
    if (span <= 0.0) {
      return y[lo];
    }
    double t = (raw - x[lo]) / span;
    return y[lo] + t * (y[hi] - y[lo]);
  }
}
