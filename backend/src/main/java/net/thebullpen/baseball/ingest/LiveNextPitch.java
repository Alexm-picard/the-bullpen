package net.thebullpen.baseball.ingest;

import java.time.LocalDate;

/**
 * Pre-pitch context for the pitch ABOUT to be thrown (decision [143], predict-next), parsed from
 * the GUMBO feed's {@code liveData.plays.currentPlay} - the in-progress at-bat. The poller runs the
 * pre-pitch head on this and logs the prediction keyed to {@code (gameId, atBatIndex,
 * pitchNumber)}; when that pitch lands it reconciles to its {@code pitches_live} row (step 5 LEFT
 * JOIN).
 *
 * <p>{@code pitchNumber} is the next pitch (1 + pitches already thrown this at-bat). {@code balls /
 * strikes / outs} are the live count entering that pitch; base occupancy is the current runners.
 * {@code batSide} may be {@code "S"} (switch hitter) - callers resolve S -&gt; L|R against the
 * pitcher's hand before inference.
 */
public record LiveNextPitch(
    long gameId,
    int atBatIndex,
    int pitchNumber,
    int inning,
    boolean topInning,
    long pitcherId,
    long batterId,
    String pitchHand,
    String batSide,
    int balls,
    int strikes,
    int outs,
    boolean onFirst,
    boolean onSecond,
    boolean onThird,
    String parkId,
    LocalDate gameDate) {

  /** Base-occupancy bitmask (1=first, 2=second, 4=third), matching {@code pitches.base_state}. */
  public int baseState() {
    return (onFirst ? 1 : 0) | (onSecond ? 2 : 0) | (onThird ? 4 : 0);
  }
}
