package net.thebullpen.baseball.simulation;

import java.util.SplittableRandom;
import java.util.function.IntFunction;

/**
 * Monte-Carlo simulator for the plate-appearance Markov chain (Phase 2a.9).
 *
 * <p>Diagnostic counterpart to {@link AnalyticalSolver}. The convergence test asserts that the MC
 * mean matches the analytical mean within ±0.5% across 100 random pitcher probability vectors.
 *
 * <p>Determinism: {@link SplittableRandom} seeded per simulation; same seed → same trajectory. The
 * sampler walks the per-state distribution with a single uniform draw + cumulative threshold (no
 * alias-table since K=5 is tiny).
 */
public final class MonteCarloSimulator {

  /** Per-PA outcome of one MC trajectory. */
  private enum AbsorbingClass {
    BB,
    K,
    BIP
  }

  /** Summary stats over N simulated PAs starting from a given state. */
  public record Result(
      int balls,
      int strikes,
      int trials,
      double meanPitches,
      double pWalk,
      double pStrikeout,
      double pInPlay) {}

  /**
   * Run {@code trials} simulated plate appearances starting from {@code (balls, strikes)}.
   *
   * @param probsByState given a transient state index (0..11), the 5-class distribution. The
   *     simulator caches the result per state per call so the caller may do any expensive work
   *     once.
   * @param seed RNG seed — same seed deterministically reproduces the trajectory.
   */
  public static Result run(
      int balls, int strikes, int trials, IntFunction<double[]> probsByState, long seed) {
    if (trials <= 0) {
      throw new IllegalArgumentException("trials must be > 0; got " + trials);
    }
    SplittableRandom rng = new SplittableRandom(seed);

    // Cache per-state cumulative distributions so each MC trial reuses the work.
    double[][] cumulative = new double[PlateAppearanceMarkov.N_TRANSIENT][PitchOutcome.COUNT];
    for (int s = 0; s < PlateAppearanceMarkov.N_TRANSIENT; s++) {
      double[] p = probsByState.apply(s);
      if (p.length != PitchOutcome.COUNT) {
        throw new IllegalArgumentException(
            "probs[" + s + "] length " + p.length + " != " + PitchOutcome.COUNT);
      }
      double running = 0.0;
      for (int o = 0; o < PitchOutcome.COUNT; o++) {
        if (p[o] < 0.0) {
          throw new IllegalArgumentException("negative probability at state " + s);
        }
        running += p[o];
        cumulative[s][o] = running;
      }
      // Defensive renorm — small rounding drift fine; uniform-class fallback if all-zero.
      if (running <= 0.0) {
        for (int o = 0; o < PitchOutcome.COUNT; o++) {
          cumulative[s][o] = (o + 1.0) / PitchOutcome.COUNT;
        }
      } else if (Math.abs(running - 1.0) > 1e-9) {
        for (int o = 0; o < PitchOutcome.COUNT; o++) cumulative[s][o] /= running;
      }
    }

    long totalPitches = 0;
    int countBB = 0;
    int countK = 0;
    int countBIP = 0;

    for (int t = 0; t < trials; t++) {
      int state = PlateAppearanceMarkov.transientIndex(balls, strikes);
      int pitchesThisPa = 0;
      while (true) {
        pitchesThisPa++;
        int outcomeOrdinal = sampleOrdinal(cumulative[state], rng);
        int[] bs = PlateAppearanceMarkov.unpackTransient(state);
        PlateAppearanceMarkov.Transition transition =
            PlateAppearanceMarkov.step(bs[0], bs[1], PitchOutcome.values()[outcomeOrdinal]);
        if (transition.absorbing()) {
          AbsorbingClass terminal = absorbingFromIndex(transition.targetIndex());
          switch (terminal) {
            case BB -> countBB++;
            case K -> countK++;
            case BIP -> countBIP++;
          }
          totalPitches += pitchesThisPa;
          break;
        }
        state = transition.targetIndex();
      }
    }

    return new Result(
        balls,
        strikes,
        trials,
        (double) totalPitches / trials,
        (double) countBB / trials,
        (double) countK / trials,
        (double) countBIP / trials);
  }

  private static int sampleOrdinal(double[] cumulative, SplittableRandom rng) {
    double u = rng.nextDouble();
    for (int o = 0; o < cumulative.length; o++) {
      if (u <= cumulative[o]) return o;
    }
    return cumulative.length - 1;
  }

  private static AbsorbingClass absorbingFromIndex(int index) {
    return switch (index) {
      case PlateAppearanceMarkov.ABSORB_BB -> AbsorbingClass.BB;
      case PlateAppearanceMarkov.ABSORB_K -> AbsorbingClass.K;
      case PlateAppearanceMarkov.ABSORB_BIP -> AbsorbingClass.BIP;
      default -> throw new IllegalStateException("invalid absorbing index: " + index);
    };
  }

  private MonteCarloSimulator() {}
}
