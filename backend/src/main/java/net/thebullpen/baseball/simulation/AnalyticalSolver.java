package net.thebullpen.baseball.simulation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.IntFunction;
import org.ejml.simple.SimpleMatrix;

/**
 * Closed-form solver for the plate-appearance Markov chain (Phase 2a.9).
 *
 * <p>Builds the transient×transient matrix Q and transient×absorbing matrix R from per-state
 * pitch-outcome probabilities, then computes:
 *
 * <ul>
 *   <li>{@code N = (I − Q)⁻¹} — the fundamental matrix. Row {@code s} sums to E[pitches] starting
 *       from state {@code s}.
 *   <li>{@code B = N · R} — absorption-probability matrix. Row {@code s} gives P(BB), P(K), P(BIP)
 *       starting from state {@code s}.
 * </ul>
 *
 * <p>The probability function takes a transient state index (see {@link
 * PlateAppearanceMarkov#transientIndex}) and returns a length-5 distribution over {@link
 * PitchOutcome} ordinals. Different states can have different distributions — pitchers throw
 * differently in 0-0 vs 3-2.
 */
public final class AnalyticalSolver {

  /**
   * Per-state result indexed by transient state.
   *
   * @param expectedPitches E[pitches until absorption] starting from state s
   * @param pWalk P(BB | start at s)
   * @param pStrikeout P(K | start at s)
   * @param pInPlay P(BIP | start at s)
   */
  public record StateResult(
      int balls,
      int strikes,
      double expectedPitches,
      double pWalk,
      double pStrikeout,
      double pInPlay) {}

  /** Full solve over all 12 transient starting states. */
  public record Solution(List<StateResult> perState) {
    public StateResult at(int balls, int strikes) {
      return perState.get(PlateAppearanceMarkov.transientIndex(balls, strikes));
    }
  }

  /**
   * Solve the chain for an arbitrary per-state pitch distribution.
   *
   * @param probsByState given a transient state index (0..11), return the 5-class distribution.
   *     Must sum to ~1.0; small drift is fine, the matrix algebra renormalises implicitly via the
   *     fundamental-matrix construction.
   */
  public static Solution solve(IntFunction<double[]> probsByState) {
    int n = PlateAppearanceMarkov.N_TRANSIENT;
    int a = PlateAppearanceMarkov.N_ABSORBING;

    SimpleMatrix q = new SimpleMatrix(n, n);
    SimpleMatrix r = new SimpleMatrix(n, a);

    for (int s = 0; s < n; s++) {
      double[] probs = probsByState.apply(s);
      if (probs.length != PitchOutcome.COUNT) {
        throw new IllegalArgumentException(
            "probs[" + s + "] length " + probs.length + " != " + PitchOutcome.COUNT);
      }
      int[] bs = PlateAppearanceMarkov.unpackTransient(s);
      for (int o = 0; o < PitchOutcome.COUNT; o++) {
        double p = probs[o];
        if (p < 0.0 || p > 1.0 + 1e-9) {
          throw new IllegalArgumentException(
              "probs[" + s + "][" + o + "] = " + p + " is outside [0,1]");
        }
        PlateAppearanceMarkov.Transition t =
            PlateAppearanceMarkov.step(bs[0], bs[1], PitchOutcome.values()[o]);
        if (t.absorbing()) {
          r.set(s, t.targetIndex(), r.get(s, t.targetIndex()) + p);
        } else {
          q.set(s, t.targetIndex(), q.get(s, t.targetIndex()) + p);
        }
      }
    }

    SimpleMatrix identity = SimpleMatrix.identity(n);
    SimpleMatrix fundamental = identity.minus(q).invert();
    SimpleMatrix absorption = fundamental.mult(r);

    List<StateResult> out = new ArrayList<>(n);
    for (int s = 0; s < n; s++) {
      int[] bs = PlateAppearanceMarkov.unpackTransient(s);
      double expectedPitches = 0.0;
      for (int j = 0; j < n; j++) expectedPitches += fundamental.get(s, j);
      out.add(
          new StateResult(
              bs[0],
              bs[1],
              expectedPitches,
              absorption.get(s, PlateAppearanceMarkov.ABSORB_BB),
              absorption.get(s, PlateAppearanceMarkov.ABSORB_K),
              absorption.get(s, PlateAppearanceMarkov.ABSORB_BIP)));
    }
    return new Solution(Collections.unmodifiableList(out));
  }

  private AnalyticalSolver() {}
}
