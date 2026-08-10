package net.thebullpen.baseball.domain;

import java.time.Instant;

/**
 * The most recent COMPLETED ball in play of a game, as the game page's batted-ball card needs it.
 *
 * <p>Carries BOTH the display fields and the model-input fields on purpose. The card shows a batted
 * ball and scores that same ball across all 30 parks, so a design where the display read one source
 * and the prediction read another could show ball A while comparing ball B - and would do it at
 * exactly the moment the two sources disagreed, which is the moment nobody is watching for. One
 * record, one ball.
 *
 * <p>This is also why it exists at all rather than the page scanning its pitch list: that list is
 * the newest 50 pitches, a WINDOW rather than the game, so a batted ball is found only while it
 * happens to be inside it. This query is authoritative regardless.
 *
 * <p>Absence is the whole record being null, per {@link BattedBall} and {@link CurrentMatchup} - a
 * game with no completed ball in play yet has none, which is a normal state for the first innings
 * and the honest answer for a scheduled game.
 *
 * @param batterId who hit it.
 * @param atBatIndex at-bat this ball ended. With {@code pitchNumber} it identifies the pitch, which
 *     is what lets a consumer tell a re-render of the same ball from a new one.
 * @param pitchNumber pitch within that at-bat.
 * @param ts when the row was ingested - the card captions the ball with it, since "the most recent
 *     batted ball" is a claim about time and a viewer arriving mid-inning cannot otherwise tell
 *     whether it was seconds or half an hour ago.
 * @param event the plate-appearance result ({@code "Home Run"}, {@code "Groundout"}). Never blank:
 *     it is the completeness marker that proves the play finished (V032).
 * @param bbType the feed's trajectory verbatim ({@code ground_ball}, {@code fly_ball}, ...). May be
 *     blank; the display falls back to a launch-angle band.
 * @param launchSpeedMph exit velocity, always &gt; 0 (0 is the storage absence sentinel).
 * @param launchAngleDeg vertical launch angle; may legitimately be 0 or negative.
 * @param hitDistanceFt Statcast's PROJECTED landing distance - correct and tiny for a ball that was
 *     never in the air (1 ft observed). Presenting it sensibly is the display's job.
 * @param sprayAngleDeg spray angle, or NULL when it cannot be honestly derived (tracked at or
 *     behind the plate, or outside the foul lines). Null means the per-park comparison must be
 *     withheld, NOT that 0 should be substituted - the model treats spray as an observation.
 * @param stand batter side, already resolved to L or R for switch hitters.
 * @param baseState base-occupancy bitmask, or null on a pre-V028 row whose occupancy is unknown.
 * @param parkId the park, which is what the per-park comparison is anchored to.
 * @param outs outs at the time of the pitch.
 */
public record RecentBattedBall(
    long batterId,
    int atBatIndex,
    int pitchNumber,
    Instant ts,
    String event,
    String bbType,
    double launchSpeedMph,
    double launchAngleDeg,
    double hitDistanceFt,
    Double sprayAngleDeg,
    String stand,
    Integer baseState,
    String parkId,
    int outs) {}
