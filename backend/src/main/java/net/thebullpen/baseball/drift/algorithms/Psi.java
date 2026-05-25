package net.thebullpen.baseball.drift.algorithms;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Population Stability Index (PSI) + chi-squared distance — the drift math leaf 3c.2 needs. Pure
 * static; no I/O. Two flavors:
 *
 * <ul>
 *   <li>{@link #computeContinuous} — quantile-binned PSI for numeric features. Bin edges come from
 *       the REFERENCE distribution (equal-frequency on reference per leaf "Known edge cases" —
 *       handles skewed features that would collapse under equal-width binning). Both distributions
 *       are normalized to proportions; zero proportions get a smoothing epsilon (default {@code
 *       1e-4}) to avoid {@code log(0) = -inf}.
 *   <li>{@link #computeCategorical} — chi-squared distance for categorical features. Symmetric in
 *       reference/actual. New categories in `actual` (absent from reference) are smoothed.
 * </ul>
 *
 * <p>PSI interpretation (industry rule-of-thumb): &lt; 0.1 = no drift; 0.1–0.25 = moderate; &gt;
 * 0.25 = significant. The threshold check lives in 3c.7's alerting layer, not here.
 */
public final class Psi {

  /** Smoothing epsilon applied to zero proportions to avoid log(0). */
  public static final double EPSILON = 1e-4;

  /** Default number of quantile bins for continuous PSI. */
  public static final int DEFAULT_BINS = 10;

  private Psi() {}

  /**
   * Continuous PSI with quantile binning from the reference distribution.
   *
   * @param reference baseline distribution sample (training-time).
   * @param actual current-window distribution sample (last 24h).
   * @param nBins number of quantile bins (typically 10).
   * @return PSI value (≥ 0; identical distributions → ≈ 0).
   */
  public static double computeContinuous(double[] reference, double[] actual, int nBins) {
    if (reference == null || reference.length == 0) {
      throw new IllegalArgumentException("reference distribution must be non-empty");
    }
    if (actual == null || actual.length == 0) {
      throw new IllegalArgumentException("actual distribution must be non-empty");
    }
    if (nBins < 2) {
      throw new IllegalArgumentException("nBins must be >= 2; got " + nBins);
    }
    double[] edges = quantileEdges(reference, nBins);
    int[] refCounts = binCounts(reference, edges);
    int[] actCounts = binCounts(actual, edges);
    return psiFromCounts(refCounts, actCounts, reference.length, actual.length);
  }

  /**
   * Symmetric chi-squared distance over categorical counts. New categories present only in {@code
   * actual} are smoothed (reference count → 0 contribution; chi² formula still finite because we
   * divide by {@code ref + actual}).
   */
  public static double computeCategorical(
      Map<String, Integer> reference, Map<String, Integer> actual) {
    if (reference == null || actual == null) {
      throw new IllegalArgumentException("reference + actual must be non-null");
    }
    Set<String> keys = new HashSet<>();
    keys.addAll(reference.keySet());
    keys.addAll(actual.keySet());
    if (keys.isEmpty()) {
      return 0.0;
    }
    double sum = 0.0;
    for (String key : keys) {
      double ref = reference.getOrDefault(key, 0);
      double act = actual.getOrDefault(key, 0);
      double denom = ref + act;
      if (denom == 0.0) {
        continue;
      }
      double diff = act - ref;
      sum += (diff * diff) / denom;
    }
    return sum;
  }

  // --- helpers ----------------------------------------------------------

  /**
   * Compute (nBins+1) equal-frequency edges from the reference sample. The first edge is
   * −∞-effective; the last is +∞-effective. Edges are inclusive on the left, exclusive on the right
   * (the last bin is closed on both sides).
   */
  static double[] quantileEdges(double[] reference, int nBins) {
    double[] sorted = reference.clone();
    Arrays.sort(sorted);
    double[] edges = new double[nBins + 1];
    edges[0] = Double.NEGATIVE_INFINITY;
    for (int i = 1; i < nBins; i++) {
      double q = (double) i / nBins;
      int idx = (int) Math.floor(q * (sorted.length - 1));
      edges[i] = sorted[idx];
    }
    edges[nBins] = Double.POSITIVE_INFINITY;
    return edges;
  }

  static int[] binCounts(double[] values, double[] edges) {
    int nBins = edges.length - 1;
    int[] counts = new int[nBins];
    for (double v : values) {
      int b = findBin(v, edges);
      counts[b]++;
    }
    return counts;
  }

  /** Find the bin index for {@code v} via linear scan (nBins is small, ~10). */
  static int findBin(double v, double[] edges) {
    int nBins = edges.length - 1;
    for (int b = 0; b < nBins; b++) {
      if (v < edges[b + 1] || b == nBins - 1) {
        return b;
      }
    }
    return nBins - 1;
  }

  static double psiFromCounts(int[] refCounts, int[] actCounts, int refN, int actN) {
    double psi = 0.0;
    for (int i = 0; i < refCounts.length; i++) {
      double pRef = Math.max((double) refCounts[i] / refN, EPSILON);
      double pAct = Math.max((double) actCounts[i] / actN, EPSILON);
      psi += (pAct - pRef) * Math.log(pAct / pRef);
    }
    return psi;
  }
}
