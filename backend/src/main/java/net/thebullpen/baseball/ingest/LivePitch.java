package net.thebullpen.baseball.ingest;

/**
 * One pitch parsed from the MLB Stats API GUMBO live feed, carrying the PRE-pitch state the
 * pre-pitch head predicts on (decision [143]) plus the observed outcome needed to write {@code
 * pitches_live} and, later, truth-join predictions to outcomes for calibration.
 *
 * <p><b>Pre-pitch count.</b> The feed reports each pitch event's {@code count} as the count AFTER
 * the pitch. {@code preBalls}/{@code preStrikes} here are the count BEFORE the pitch (the previous
 * pitch's post-count, or 0-0 for the at-bat's first pitch) - that is exactly the state the
 * pre-pitch head predicts on, and (per decision [143]) the same value whether shown live as the
 * "next pitch" or logged after the pitch completes.
 *
 * <p><b>Base/score/outs are entering-at-bat values</b> (a documented v1 approximation): runs and
 * outs resolve on the terminal pitch, and mid-at-bat steals are ignored, so these are constant for
 * every pitch in the at-bat. {@code terminal} marks the pitch that ended the at-bat.
 *
 * <p>{@code description} is collapsed to the canonical {@code pitches} vocabulary (ball /
 * called_strike / swinging_strike / foul / in_play / hit_by_pitch / unknown). {@code batSide} may
 * be {@code "S"} (switch hitter); callers resolve S -> L|R against the matchup before inference.
 */
public record LivePitch(
    long gameId,
    int atBatIndex,
    int pitchNumber,
    int inning,
    boolean topInning,
    long pitcherId,
    long batterId,
    String pitchHand,
    String batSide,
    int preBalls,
    int preStrikes,
    int outs,
    boolean onFirst,
    boolean onSecond,
    boolean onThird,
    int homeScore,
    int awayScore,
    String description,
    String pitchType,
    Double releaseSpeedMph,
    Double plateXIn,
    Double plateZIn,
    boolean terminal) {

  /** Base-occupancy bitmask (1=first, 2=second, 4=third), matching {@code pitches.base_state}. */
  public int baseState() {
    return (onFirst ? 1 : 0) | (onSecond ? 2 : 0) | (onThird ? 4 : 0);
  }
}
