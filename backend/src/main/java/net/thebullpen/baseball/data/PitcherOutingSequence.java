package net.thebullpen.baseball.data;

/**
 * Tier-SEQ state for the pitch about to be thrown: the pitcher's previous two pitch types this
 * outing (y7 ints, {@code -1} at outing start) and how many labeled pitches he has thrown so far.
 *
 * <p>Semantics mirror {@code compute_pitch_type_state.sql} exactly, and the subtleties are the
 * point: the sequence shifts over LABELED pitches only, so a pitchout / intentional ball /
 * unlabeled pitch is never a "previous pitch" and never counts toward {@code pitchesIntoOuting};
 * the ints are the y7 vocabulary (FF=0, SI=1, FC=2, SL=3, CU=4, CH=5, OFF=6) applied AFTER the
 * canonical fold, so an ST reads back as SL=3.
 */
public record PitcherOutingSequence(
    int prev1PtI, int prev2PtI, int prev1Missing, int pitchesIntoOuting) {

  /** The state before any labeled pitch has been thrown this outing. */
  public static final PitcherOutingSequence OUTING_START = new PitcherOutingSequence(-1, -1, 1, 0);
}
