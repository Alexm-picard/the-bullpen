package net.thebullpen.baseball.inference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Per-class isotonic calibrator (Phase 2a.8) — Java mirror of {@code
 * bullpen_training.pitch.isotonic.IsotonicCalibrator}.
 *
 * <p>Reads {@code calibrator.json} written by the Python side: for each class c, a pair of
 * monotone-non-decreasing arrays {@code (x_thresholds, y_thresholds)}. Applies piecewise-linear
 * interpolation (with end-clipping) and then re-normalises rows so the calibrated distribution sums
 * to 1.
 *
 * <p>Must match the Python implementation byte-for-byte on the parity fixture (1e-6 tolerance). The
 * arithmetic uses {@code double} throughout to avoid float32-rounding drift between the two
 * languages.
 */
public final class IsotonicCalibratorJava {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final List<String> classLabels;
  private final double[][] xThresholds;
  private final double[][] yThresholds;

  IsotonicCalibratorJava(List<String> classLabels, double[][] xThresholds, double[][] yThresholds) {
    if (classLabels.size() != xThresholds.length || classLabels.size() != yThresholds.length) {
      throw new IllegalArgumentException("calibrator class/threshold arity mismatch");
    }
    this.classLabels = List.copyOf(classLabels);
    this.xThresholds = xThresholds;
    this.yThresholds = yThresholds;
  }

  public static IsotonicCalibratorJava load(Path calibratorJson) throws IOException {
    JsonNode root = MAPPER.readTree(Files.readAllBytes(calibratorJson));
    List<String> labels = new ArrayList<>();
    root.get("class_labels").forEach(n -> labels.add(n.asText()));

    JsonNode breakpoints = root.get("breakpoints");
    int k = breakpoints.size();
    double[][] xs = new double[k][];
    double[][] ys = new double[k][];
    for (int i = 0; i < k; i++) {
      JsonNode entry = breakpoints.get(i);
      xs[i] = parseDoubleArray(entry.get("x_thresholds"));
      ys[i] = parseDoubleArray(entry.get("y_thresholds"));
    }
    return new IsotonicCalibratorJava(labels, xs, ys);
  }

  private static double[] parseDoubleArray(JsonNode node) {
    double[] out = new double[node.size()];
    for (int i = 0; i < node.size(); i++) {
      out[i] = node.get(i).asDouble();
    }
    return out;
  }

  public List<String> classLabels() {
    return classLabels;
  }

  /**
   * Apply per-class isotonic interpolation then re-normalise rows. Input shape: {@code (N, K)}
   * where K = {@link #classLabels()}. Returns a fresh double[N][K] array (caller-owned).
   *
   * <p>Matches Python's behaviour: rows that sum to 0 after clipping fall back to a uniform
   * distribution.
   */
  public double[][] transform(double[][] proba) {
    if (proba.length == 0) {
      return new double[0][];
    }
    int k = classLabels.size();
    if (proba[0].length != k) {
      throw new IllegalArgumentException(
          "row width " + proba[0].length + " != calibrator class count " + k);
    }
    double[][] out = new double[proba.length][k];
    for (int i = 0; i < proba.length; i++) {
      double sum = 0.0;
      for (int c = 0; c < k; c++) {
        out[i][c] = interpolate(xThresholds[c], yThresholds[c], proba[i][c]);
        sum += out[i][c];
      }
      if (sum == 0.0) {
        double uniform = 1.0 / k;
        for (int c = 0; c < k; c++) out[i][c] = uniform;
      } else {
        for (int c = 0; c < k; c++) out[i][c] /= sum;
      }
    }
    return out;
  }

  /**
   * NumPy {@code np.interp(x, xp, fp, left=fp[0], right=fp[-1])} for a single point. xp must be
   * sorted ascending (sklearn's IsotonicRegression guarantees this).
   */
  static double interpolate(double[] xp, double[] fp, double x) {
    if (xp.length == 0) return 0.0;
    if (x <= xp[0]) return fp[0];
    if (x >= xp[xp.length - 1]) return fp[fp.length - 1];
    // Binary search for the bracket
    int lo = 0;
    int hi = xp.length - 1;
    while (lo + 1 < hi) {
      int mid = (lo + hi) >>> 1;
      if (xp[mid] <= x) {
        lo = mid;
      } else {
        hi = mid;
      }
    }
    double x0 = xp[lo];
    double x1 = xp[hi];
    double y0 = fp[lo];
    double y1 = fp[hi];
    if (x1 == x0) return y0;
    return y0 + (y1 - y0) * (x - x0) / (x1 - x0);
  }
}
