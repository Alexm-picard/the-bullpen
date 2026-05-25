package net.thebullpen.baseball.drift.algorithms;

import java.util.List;

/**
 * Calibration math (3c.4) — Brier score, ECE, log loss against observed truth. Pure static helpers;
 * no I/O. Operates on aligned lists of {@code (probability vector, truth class index)} — different
 * shape from {@code MetricsComputer} (3b.4) which works on champion-vs-challenger PAIRS. 3c's drift
 * jobs care about ONE distribution against truth; 3b's experiments care about TWO distributions
 * against truth.
 *
 * <p>All three metrics are lower-is-better; baselines:
 *
 * <ul>
 *   <li>{@code brier} — perfect-on-Dirac = 0; uniform K-class = (K-1)/K averaged over classes.
 *   <li>{@code ece} — perfect calibration = 0; max ≈ 1.
 *   <li>{@code logLoss} — perfect prediction = 0; uniform = log(K).
 * </ul>
 */
public final class Calibration {

  private static final int ECE_BINS = 10;
  private static final double LOG_FLOOR = 1e-15;

  private Calibration() {}

  /**
   * Multi-class Brier: mean squared error of the probability vector vs the one-hot truth, averaged
   * over rows AND classes. Same formula as {@code MetricsComputer.brier} but for a single
   * distribution.
   */
  public static double brier(List<double[]> probs, int[] truthClass) {
    requireSameSize(probs, truthClass);
    double sumSq = 0.0;
    long count = 0;
    for (int row = 0; row < probs.size(); row++) {
      double[] p = probs.get(row);
      int t = truthClass[row];
      checkTruthInRange(t, p.length, row);
      for (int k = 0; k < p.length; k++) {
        double y = k == t ? 1.0 : 0.0;
        double diff = p[k] - y;
        sumSq += diff * diff;
      }
      count += p.length;
    }
    return sumSq / count;
  }

  /**
   * Multinomial log-loss: {@code -mean(log(p_truth))} with a {@value #LOG_FLOOR} clamp to keep
   * exact-zero predictions finite.
   */
  public static double logLoss(List<double[]> probs, int[] truthClass) {
    requireSameSize(probs, truthClass);
    double sum = 0.0;
    for (int row = 0; row < probs.size(); row++) {
      double[] p = probs.get(row);
      int t = truthClass[row];
      checkTruthInRange(t, p.length, row);
      double pTruth = Math.max(p[t], LOG_FLOOR);
      sum += -Math.log(pTruth);
    }
    return sum / probs.size();
  }

  /**
   * Expected Calibration Error (10 equal-width bins on the argmax-class confidence). Same 10-bin
   * variant as {@code MetricsComputer.ece} (Guo et al. 2017 standard).
   */
  public static double ece(List<double[]> probs, int[] truthClass) {
    requireSameSize(probs, truthClass);
    long[] binCount = new long[ECE_BINS];
    double[] binConfSum = new double[ECE_BINS];
    long[] binCorrect = new long[ECE_BINS];
    for (int row = 0; row < probs.size(); row++) {
      double[] p = probs.get(row);
      int t = truthClass[row];
      checkTruthInRange(t, p.length, row);
      int predClass = argmax(p);
      double conf = p[predClass];
      int bin = (int) Math.min(ECE_BINS - 1, Math.floor(conf * ECE_BINS));
      binCount[bin]++;
      binConfSum[bin] += conf;
      if (predClass == t) {
        binCorrect[bin]++;
      }
    }
    double weighted = 0.0;
    long total = probs.size();
    for (int b = 0; b < ECE_BINS; b++) {
      if (binCount[b] == 0) {
        continue;
      }
      double avgConf = binConfSum[b] / binCount[b];
      double accuracy = (double) binCorrect[b] / binCount[b];
      weighted += ((double) binCount[b] / total) * Math.abs(avgConf - accuracy);
    }
    return weighted;
  }

  static int argmax(double[] p) {
    int best = 0;
    double bestVal = p[0];
    for (int i = 1; i < p.length; i++) {
      if (p[i] > bestVal) {
        best = i;
        bestVal = p[i];
      }
    }
    return best;
  }

  private static void requireSameSize(List<double[]> probs, int[] truthClass) {
    if (probs == null || truthClass == null) {
      throw new IllegalArgumentException("probs + truthClass must be non-null");
    }
    if (probs.size() != truthClass.length) {
      throw new IllegalArgumentException(
          "probs.size()=" + probs.size() + " != truthClass.length=" + truthClass.length);
    }
    if (probs.isEmpty()) {
      throw new IllegalArgumentException("probs must be non-empty");
    }
  }

  private static void checkTruthInRange(int t, int k, int row) {
    if (t < 0 || t >= k) {
      throw new IllegalArgumentException(
          "row " + row + ": truthClass=" + t + " out of range [0, " + k + ")");
    }
  }
}
