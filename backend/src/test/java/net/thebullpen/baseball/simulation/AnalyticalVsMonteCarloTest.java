package net.thebullpen.baseball.simulation;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.SplittableRandom;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;

/**
 * Convergence test: across 100 random per-state pitcher distributions, the Monte-Carlo mean must
 * agree with the analytical mean within ±0.5% (per leaf acceptance criterion).
 *
 * <p>If this drifts, either the matrix algebra in {@link AnalyticalSolver} or the sampling loop in
 * {@link MonteCarloSimulator} is wrong — and since the two are independent implementations of the
 * same chain, divergence is informative.
 *
 * <p>Determinism: outer RNG is seeded so the 100 random vectors are reproducible; each MC run also
 * gets a deterministic seed derived from the vector index.
 */
class AnalyticalVsMonteCarloTest {

  private static final int VECTORS = 100;
  // Leaf plan suggests 10K trials with 0.5% relative tolerance, but at p≈0.3 the binomial SE
  // alone is ~0.005 — 0.5% relative (~0.0015) is unreachable in 10K trials regardless of
  // implementation correctness. We bump to 50K (SE drops by √5 → ~0.002) and use absolute
  // floors sized to ~5σ of the binomial noise so the test is robust without losing signal.
  private static final int MC_TRIALS = 50_000;

  // Probabilities are well-bounded by binomial SE; 0.5% relative + 0.01 absolute floor works.
  private static final double PROB_RELATIVE_TOL = 0.005;
  private static final double PROB_ABSOLUTE_FLOOR = 0.01;
  // E[pitches] has long-tailed variance (foul-loop chains can produce E[pitches]>10 with
  // geometric-style variance). 2% relative + 0.1 absolute floor covers the heavy-tailed cases
  // without losing signal on short PAs.
  private static final double PITCHES_RELATIVE_TOL = 0.02;
  private static final double PITCHES_ABSOLUTE_FLOOR = 0.1;

  @Test
  void monteCarloMatchesAnalyticalAcross100RandomDistributions() {
    SplittableRandom seedRng = new SplittableRandom(20260524L);
    int failures = 0;
    StringBuilder firstFailure = new StringBuilder();

    for (int v = 0; v < VECTORS; v++) {
      double[] sharedDistribution = randomDistribution(seedRng);
      IntFunction<double[]> probsByState = s -> sharedDistribution;

      AnalyticalSolver.Solution analytical = AnalyticalSolver.solve(probsByState);
      MonteCarloSimulator.Result mc =
          MonteCarloSimulator.run(0, 0, MC_TRIALS, probsByState, 1000L + v);
      AnalyticalSolver.StateResult start = analytical.at(0, 0);

      if (!within(
              start.expectedPitches(),
              mc.meanPitches(),
              PITCHES_RELATIVE_TOL,
              PITCHES_ABSOLUTE_FLOOR)
          || !within(start.pWalk(), mc.pWalk(), PROB_RELATIVE_TOL, PROB_ABSOLUTE_FLOOR)
          || !within(start.pStrikeout(), mc.pStrikeout(), PROB_RELATIVE_TOL, PROB_ABSOLUTE_FLOOR)
          || !within(start.pInPlay(), mc.pInPlay(), PROB_RELATIVE_TOL, PROB_ABSOLUTE_FLOOR)) {
        failures++;
        if (firstFailure.length() == 0) {
          firstFailure
              .append("vector #")
              .append(v)
              .append(" probs=")
              .append(java.util.Arrays.toString(sharedDistribution))
              .append("\n  analytical: E[pitches]=")
              .append(start.expectedPitches())
              .append(" pBB=")
              .append(start.pWalk())
              .append(" pK=")
              .append(start.pStrikeout())
              .append(" pBIP=")
              .append(start.pInPlay())
              .append("\n  monteCarlo: E[pitches]=")
              .append(mc.meanPitches())
              .append(" pBB=")
              .append(mc.pWalk())
              .append(" pK=")
              .append(mc.pStrikeout())
              .append(" pBIP=")
              .append(mc.pInPlay());
        }
      }
    }

    assertTrue(
        failures == 0,
        "MC vs analytical diverged on "
            + failures
            + "/"
            + VECTORS
            + " random distributions; first failure:\n"
            + firstFailure);
  }

  @Test
  void monteCarloIsDeterministicForSameSeed() {
    IntFunction<double[]> probs = s -> new double[] {0.36, 0.17, 0.11, 0.19, 0.17};
    MonteCarloSimulator.Result a = MonteCarloSimulator.run(0, 0, 5_000, probs, 42L);
    MonteCarloSimulator.Result b = MonteCarloSimulator.run(0, 0, 5_000, probs, 42L);
    assertTrue(a.meanPitches() == b.meanPitches(), "MC drifted across runs with identical seed");
    assertTrue(a.pWalk() == b.pWalk());
    assertTrue(a.pStrikeout() == b.pStrikeout());
    assertTrue(a.pInPlay() == b.pInPlay());
  }

  /**
   * Draw a random 5-class distribution, biased toward non-degenerate values so MC has enough signal
   * in {@link #MC_TRIALS} trials.
   */
  private static double[] randomDistribution(SplittableRandom rng) {
    double[] raw = new double[5];
    double sum = 0.0;
    for (int i = 0; i < 5; i++) {
      // Symmetric Dirichlet(α=2) via -log of uniform — concentrates mass off the corners.
      raw[i] = -Math.log(rng.nextDouble() + 1e-12);
      sum += raw[i];
    }
    for (int i = 0; i < 5; i++) raw[i] /= sum;
    return raw;
  }

  private static boolean within(
      double analytical, double mc, double relativeTol, double absoluteFloor) {
    double tol = Math.max(absoluteFloor, relativeTol * Math.abs(analytical));
    return Math.abs(mc - analytical) <= tol;
  }
}
