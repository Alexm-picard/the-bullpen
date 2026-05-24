package net.thebullpen.baseball.simulation;

/**
 * The 5 pre-pitch outcomes the model predicts (decision [34]). Kept in the simulation package
 * because the Markov chain transitions on these labels; the inference layer can map to whatever
 * string scheme it likes.
 */
public enum PitchOutcome {
  BALL,
  CALLED_STRIKE,
  SWINGING_STRIKE,
  FOUL,
  IN_PLAY;

  public static final int COUNT = values().length;
}
