package net.thebullpen.baseball.simulation;

/**
 * Per-pitch state machine for a plate appearance (Phase 2a.9).
 *
 * <p>12 transient states indexed by {@code (balls, strikes)} for balls ∈ {0,1,2,3} and strikes ∈
 * {0,1,2}. Plus 3 absorbing states: walk (BB), strikeout (K), ball-in-play (BIP).
 *
 * <p>Transition rules (per decision [53]):
 *
 * <ul>
 *   <li>BALL → (balls+1, strikes), or BB if balls = 3
 *   <li>CALLED_STRIKE / SWINGING_STRIKE → (balls, strikes+1), or K if strikes = 2
 *   <li>FOUL → (balls, strikes+1) if strikes &lt; 2, else self-loop at (balls, 2)
 *   <li>IN_PLAY → BIP
 * </ul>
 *
 * <p>This class is pure logic — no math libraries, no probabilities. The {@link AnalyticalSolver}
 * and {@link MonteCarloSimulator} both consume it.
 */
public final class PlateAppearanceMarkov {

  /** 12 transient + 3 absorbing = 15 states. */
  public static final int N_TRANSIENT = 12;

  public static final int N_ABSORBING = 3;
  public static final int N_STATES = N_TRANSIENT + N_ABSORBING;

  /** Absorbing-state indices (relative to the absorbing block, 0..2). */
  public static final int ABSORB_BB = 0;

  public static final int ABSORB_K = 1;
  public static final int ABSORB_BIP = 2;

  /**
   * Pack (balls, strikes) → transient index [0..11]. Layout: balls major, strikes minor, so
   * (0,0)=0, (0,1)=1, (0,2)=2, (1,0)=3, …, (3,2)=11.
   */
  public static int transientIndex(int balls, int strikes) {
    validate(balls, strikes);
    return balls * 3 + strikes;
  }

  /** Inverse of {@link #transientIndex}. */
  public static int[] unpackTransient(int idx) {
    if (idx < 0 || idx >= N_TRANSIENT) {
      throw new IllegalArgumentException("transient index out of range: " + idx);
    }
    return new int[] {idx / 3, idx % 3};
  }

  private static void validate(int balls, int strikes) {
    if (balls < 0 || balls > 3) {
      throw new IllegalArgumentException("balls out of [0,3]: " + balls);
    }
    if (strikes < 0 || strikes > 2) {
      throw new IllegalArgumentException("strikes out of [0,2]: " + strikes);
    }
  }

  /**
   * Result of dispatching one pitch outcome from a state.
   *
   * @param absorbing true if the pitch landed in BB/K/BIP
   * @param targetIndex transient index if !absorbing, else absorbing-block index (0..2)
   */
  public record Transition(boolean absorbing, int targetIndex) {}

  /** Apply one pitch outcome from {@code (balls, strikes)}. */
  public static Transition step(int balls, int strikes, PitchOutcome outcome) {
    validate(balls, strikes);
    return switch (outcome) {
      case BALL ->
          balls == 3
              ? new Transition(true, ABSORB_BB)
              : new Transition(false, transientIndex(balls + 1, strikes));
      case CALLED_STRIKE, SWINGING_STRIKE ->
          strikes == 2
              ? new Transition(true, ABSORB_K)
              : new Transition(false, transientIndex(balls, strikes + 1));
      case FOUL ->
          // Foul with 2 strikes stays at 2 strikes (the canonical foul-into-the-stands rule).
          strikes < 2
              ? new Transition(false, transientIndex(balls, strikes + 1))
              : new Transition(false, transientIndex(balls, 2));
      case IN_PLAY -> new Transition(true, ABSORB_BIP);
    };
  }

  private PlateAppearanceMarkov() {}
}
