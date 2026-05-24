package net.thebullpen.baseball.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;

/**
 * Hand-traced toy chains for the Markov state machine + analytical solver (Phase 2a.9).
 *
 * <p>If these fail, the matrix algebra or transition rules drifted — every higher-level test rests
 * on this floor.
 */
class MarkovTest {

  private static final double TOL = 1e-9;

  // -- PlateAppearanceMarkov --------------------------------------------------

  @Test
  void transientIndexRoundTrips() {
    for (int b = 0; b < 4; b++) {
      for (int s = 0; s < 3; s++) {
        int idx = PlateAppearanceMarkov.transientIndex(b, s);
        int[] back = PlateAppearanceMarkov.unpackTransient(idx);
        assertEquals(b, back[0]);
        assertEquals(s, back[1]);
      }
    }
  }

  @Test
  void ballAtThreeBallsAbsorbsToWalk() {
    PlateAppearanceMarkov.Transition t = PlateAppearanceMarkov.step(3, 1, PitchOutcome.BALL);
    assertTrue(t.absorbing());
    assertEquals(PlateAppearanceMarkov.ABSORB_BB, t.targetIndex());
  }

  @Test
  void strikeAtTwoStrikesAbsorbsToK() {
    PlateAppearanceMarkov.Transition called =
        PlateAppearanceMarkov.step(2, 2, PitchOutcome.CALLED_STRIKE);
    PlateAppearanceMarkov.Transition swinging =
        PlateAppearanceMarkov.step(2, 2, PitchOutcome.SWINGING_STRIKE);
    assertTrue(called.absorbing());
    assertTrue(swinging.absorbing());
    assertEquals(PlateAppearanceMarkov.ABSORB_K, called.targetIndex());
    assertEquals(PlateAppearanceMarkov.ABSORB_K, swinging.targetIndex());
  }

  @Test
  void inPlayAbsorbsToBipFromAnyCount() {
    for (int b = 0; b < 4; b++) {
      for (int s = 0; s < 3; s++) {
        PlateAppearanceMarkov.Transition t = PlateAppearanceMarkov.step(b, s, PitchOutcome.IN_PLAY);
        assertTrue(t.absorbing(), "expected IN_PLAY at (" + b + "," + s + ") to absorb");
        assertEquals(PlateAppearanceMarkov.ABSORB_BIP, t.targetIndex());
      }
    }
  }

  @Test
  void foulAtTwoStrikesStaysAtTwoStrikes() {
    PlateAppearanceMarkov.Transition t = PlateAppearanceMarkov.step(1, 2, PitchOutcome.FOUL);
    assertEquals(false, t.absorbing());
    int[] bs = PlateAppearanceMarkov.unpackTransient(t.targetIndex());
    assertEquals(1, bs[0]);
    assertEquals(2, bs[1]);
  }

  @Test
  void foulBelowTwoStrikesAdvancesStrike() {
    PlateAppearanceMarkov.Transition t = PlateAppearanceMarkov.step(0, 1, PitchOutcome.FOUL);
    assertEquals(false, t.absorbing());
    int[] bs = PlateAppearanceMarkov.unpackTransient(t.targetIndex());
    assertEquals(0, bs[0]);
    assertEquals(2, bs[1]);
  }

  @Test
  void invalidCountsRejected() {
    assertThrows(
        IllegalArgumentException.class, () -> PlateAppearanceMarkov.step(4, 0, PitchOutcome.BALL));
    assertThrows(
        IllegalArgumentException.class,
        () -> PlateAppearanceMarkov.step(0, 3, PitchOutcome.CALLED_STRIKE));
  }

  // -- AnalyticalSolver -------------------------------------------------------

  @Test
  void deterministicCalledStrikeChainHasExpectedThreePitches() {
    // Every pitch is a called strike from any state → guaranteed K in exactly
    // 3 pitches from (0,0). The fundamental-matrix row sum at (0,0) must be 3.
    IntFunction<double[]> alwaysCalledStrike = s -> new double[] {0.0, 1.0, 0.0, 0.0, 0.0};
    AnalyticalSolver.Solution sol = AnalyticalSolver.solve(alwaysCalledStrike);

    AnalyticalSolver.StateResult start = sol.at(0, 0);
    assertEquals(3.0, start.expectedPitches(), TOL);
    assertEquals(0.0, start.pWalk(), TOL);
    assertEquals(1.0, start.pStrikeout(), TOL);
    assertEquals(0.0, start.pInPlay(), TOL);
  }

  @Test
  void deterministicBallChainHasExpectedFourPitches() {
    // Every pitch is a ball → guaranteed BB in exactly 4 pitches from (0,0).
    IntFunction<double[]> alwaysBall = s -> new double[] {1.0, 0.0, 0.0, 0.0, 0.0};
    AnalyticalSolver.Solution sol = AnalyticalSolver.solve(alwaysBall);

    AnalyticalSolver.StateResult start = sol.at(0, 0);
    assertEquals(4.0, start.expectedPitches(), TOL);
    assertEquals(1.0, start.pWalk(), TOL);
    assertEquals(0.0, start.pStrikeout(), TOL);
    assertEquals(0.0, start.pInPlay(), TOL);
  }

  @Test
  void deterministicInPlayChainEndsAfterOnePitch() {
    IntFunction<double[]> alwaysInPlay = s -> new double[] {0.0, 0.0, 0.0, 0.0, 1.0};
    AnalyticalSolver.Solution sol = AnalyticalSolver.solve(alwaysInPlay);

    AnalyticalSolver.StateResult start = sol.at(0, 0);
    assertEquals(1.0, start.expectedPitches(), TOL);
    assertEquals(0.0, start.pWalk(), TOL);
    assertEquals(0.0, start.pStrikeout(), TOL);
    assertEquals(1.0, start.pInPlay(), TOL);
  }

  @Test
  void absorptionProbabilitiesSumToOneFromEveryStartState() {
    // League-average-ish per-pitch distribution; doesn't matter exactly, just need
    // a non-degenerate distribution so the chain is sub-stochastic with absorbing exit.
    IntFunction<double[]> leagueAvg = s -> new double[] {0.36, 0.17, 0.11, 0.19, 0.17};
    AnalyticalSolver.Solution sol = AnalyticalSolver.solve(leagueAvg);
    for (AnalyticalSolver.StateResult r : sol.perState()) {
      double sum = r.pWalk() + r.pStrikeout() + r.pInPlay();
      assertEquals(
          1.0,
          sum,
          1e-9,
          "absorption-probs row sum != 1 at (" + r.balls() + "," + r.strikes() + ")");
      assertTrue(r.expectedPitches() > 0.0, "expected pitches must be positive");
    }
  }

  @Test
  void foulHeavyChainConvergesDespiteSelfLoop() {
    // 60% fouls, 40% in-play — strikes never advance from (·, 2) but in-play absorbs.
    // Geometric expectation: E[pitches at any (·,2)] = 1/0.4 = 2.5.
    IntFunction<double[]> foulHeavy =
        s -> {
          int[] bs = PlateAppearanceMarkov.unpackTransient(s);
          if (bs[1] == 2) {
            return new double[] {0.0, 0.0, 0.0, 0.6, 0.4};
          }
          return new double[] {0.0, 0.0, 0.0, 1.0, 0.0};
        };
    AnalyticalSolver.Solution sol = AnalyticalSolver.solve(foulHeavy);
    // From (0,2): 1 / (1 - 0.6) = 2.5 expected pitches; absorbs to BIP with prob 1.
    AnalyticalSolver.StateResult at02 = sol.at(0, 2);
    assertEquals(2.5, at02.expectedPitches(), 1e-9);
    assertEquals(0.0, at02.pWalk(), TOL);
    assertEquals(0.0, at02.pStrikeout(), TOL);
    assertEquals(1.0, at02.pInPlay(), TOL);
  }
}
