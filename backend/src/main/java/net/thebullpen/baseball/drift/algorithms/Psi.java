package net.thebullpen.baseball.drift.algorithms;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
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
 *   <li>{@link #computeCategorical} - symmetric chi-squared distance for categorical features,
 *       normalized to proportions so it is scale-invariant (training-scale reference vs a small
 *       actual window). Bounded in [0, 2]; new categories on either side stay finite.
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
   * Symmetric chi-squared distance over categorical distributions, normalized to proportions so the
   * result is scale-invariant. Each side is divided by its OWN total before the sum, so a reference
   * at training scale (~10^5-10^6 rows) compared against a 24h actual window (~10^2 rows) scores on
   * the shape of the shift, not on the row counts.
   *
   * <p>Before this normalization the sum collapsed to ~the reference row count when the two sides
   * were at wildly different scales (each term {@code (act - ref)^2 / (act + ref)} approaches
   * {@code ref} once {@code ref} dominates {@code act}). That was a real production defect,
   * surfaced on the first live drift cycle (2026-07-08): every categorical feature read ~710k, the
   * training row count. The May restore drill never caught it because its reference and actual
   * sides were at similar scales.
   *
   * <p>New categories present only in {@code actual} (or only in {@code reference}) stay finite:
   * the missing side contributes proportion 0 and the denominator {@code pRef + pAct} is still
   * positive. The distance is bounded in {@code [0, 2]}: 0 = identical shape, 2 = disjoint
   * supports.
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
    double refTotal = 0.0;
    for (int v : reference.values()) {
      refTotal += v;
    }
    double actTotal = 0.0;
    for (int v : actual.values()) {
      actTotal += v;
    }
    // No mass on one side means we cannot assess a shift (an empty actual window is
    // absence-of-data,
    // not drift). Callers already skip empty windows; this guards 0/0 -> NaN defensively.
    if (refTotal == 0.0 || actTotal == 0.0) {
      return 0.0;
    }
    double sum = 0.0;
    for (String key : keys) {
      double pRef = reference.getOrDefault(key, 0) / refTotal;
      double pAct = actual.getOrDefault(key, 0) / actTotal;
      double denom = pRef + pAct;
      if (denom == 0.0) {
        continue;
      }
      double diff = pAct - pRef;
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
    // Distinct interior breakpoints only. A concentrated reference produces tied quantiles, and
    // duplicate edges create zero-width bins whose empty reference proportion (floored to EPSILON)
    // then gets compared against real actual mass -> garbage/inflated PSI (DEF-H1). The breakpoints
    // are non-decreasing (quantiles of the sorted sample), so ties are adjacent; collapsing them
    // yields fewer, valid bins, and binCounts/psiFromCounts adapt to edges.length.
    List<Double> interior = new ArrayList<>();
    for (int i = 1; i < nBins; i++) {
      double q = (double) i / nBins;
      double edge = sorted[(int) Math.floor(q * (sorted.length - 1))];
      if (interior.isEmpty() || edge != interior.get(interior.size() - 1)) {
        interior.add(edge);
      }
    }
    double[] edges = new double[interior.size() + 2];
    edges[0] = Double.NEGATIVE_INFINITY;
    for (int i = 0; i < interior.size(); i++) {
      edges[i + 1] = interior.get(i);
    }
    edges[edges.length - 1] = Double.POSITIVE_INFINITY;
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
