package net.thebullpen.baseball.domain;

import java.util.OptionalDouble;

/**
 * The measured physics of a COMPLETED ball in play, from the live feed's {@code
 * playEvents[].hitData}.
 *
 * <p>ABSENCE IS THE WHOLE RECORD BEING NULL - never a half-populated one. This is the {@link
 * CurrentMatchup} lesson applied a second time: a consumer reasonably trusts the fields it can see,
 * so a record that exists must be entirely real. Every field is a primitive or a non-null String
 * precisely because there is no such thing as a partially-known batted ball here; if the feed has
 * not given us a complete, completed one, the parser yields null and nothing is written.
 *
 * <p>WHY "COMPLETED" IS PART OF THE CONTRACT: {@code hitData} populates transiently while a play is
 * still in flight. Sampling two games, every one of 62 hitData-carrying events belonged to a
 * complete play (with a result event and full coordinates), while the in-flight {@code currentPlay}
 * carried no hitData at all - but a brief partial window does exist and has been observed (physics
 * present, coordinates and result absent). Rather than model that transient as a data-quality
 * problem, the parser simply declines it: no {@link #event}, no record. That makes "partial" a
 * normal in-flight state that never reaches storage, instead of a shape every downstream consumer
 * has to defend against.
 *
 * <p>CROSS-LANGUAGE CONTRACT: {@link #sprayAngleDeg(double, double)} must agree with training's
 * {@code battedball/features_shared.py:hc_to_spray_deg} in BOTH constants and SIGN. The served
 * model was trained on that function's output and the Java feature pipeline passes the value
 * through unmodified, so a divergence here is not a rounding difference - it is a different
 * feature. Nothing in Java can detect it: javac, ArchUnit and every backend test see a
 * self-consistent record, because the contradiction only exists across the language boundary.
 * {@code BattedBallSprayParityTest} is what closes that, pinned to values computed BY the Python.
 *
 * <p>Pure record ({@code domain/} purity, ArchUnit-enforced): no Jackson, no Swagger annotations.
 *
 * @param launchSpeedMph exit velocity off the bat, mph. Always &gt; 0 - a batted ball is never 0
 *     mph (observed 59.9-106.8), which is what lets the storage layer use 0 as its absence sentinel
 *     without asserting anything false.
 * @param launchAngleDeg vertical launch angle, degrees. May legitimately be 0 (a line drive off the
 *     bat) or negative (a chopper), so it is NOT self-describing about absence - only the presence
 *     of this record is.
 * @param hitDistanceFt Statcast's PROJECTED landing distance, feet. Small and correct for balls
 *     that were never in the air: observed 1-429 ft, including a 102.7 mph single at 12 ft and a
 *     groundout at 7 ft. Stored and passed through verbatim; presenting 12 ft the way a 429 ft home
 *     run is presented is a DISPLAY concern, not a reason to alter the measurement here.
 * @param hcX horizontal landing coordinate (observed 31.3-219.0). Paired with {@link #hcY} to
 *     derive spray angle - the thing a per-park model needs and a bare launch speed/angle cannot
 *     supply.
 * @param hcY vertical landing coordinate (observed 30.4-202.5). Note the feed's y axis increases
 *     toward the plate, which the spray derivation accounts for.
 * @param bbType the feed's {@code trajectory}, verbatim: {@code ground_ball}, {@code fly_ball},
 *     {@code line_drive}, {@code popup}, {@code bunt_popup}. Already the Statcast {@code bb_type}
 *     vocabulary, so there is no mapping table here to drift out of sync.
 * @param event the plate-appearance result ({@code "Home Run"}, {@code "Groundout"}, ...). Never
 *     blank: it is simultaneously the honest display value and the COMPLETENESS marker that proves
 *     this play finished.
 */
public record BattedBall(
    double launchSpeedMph,
    double launchAngleDeg,
    double hitDistanceFt,
    double hcX,
    double hcY,
    String bbType,
    String event) {

  /**
   * Home plate in Statcast's scaled hc_x/hc_y space, y increasing TOWARD the plate.
   *
   * <p>These are the PUBLISHED Statcast coordinates, and they are the same two numbers training
   * uses in {@code battedball/features_shared.py:hc_to_spray_deg} - which is the only reason they
   * are correct. An earlier version of this class used 199.53 for the y constant, a number that
   * appears nowhere in training, in {@code contracts/}, or anywhere else in this repository.
   */
  private static final double PLATE_X = 125.42;

  private static final double PLATE_Y = 198.27;

  /** Fair territory: the foul lines sit at 45 degrees either side of dead centre. */
  private static final double FOUL_LINE_DEG = 45.0;

  /**
   * Spray angle in degrees - negative toward the left-field line, positive toward right - or EMPTY
   * when the landing coordinates cannot yield an honest one.
   *
   * <p>The per-park model requires a spray angle and the feed supplies only coordinates, so this
   * derivation is the only thing standing between the model and a fabricated input. It returns
   * {@link OptionalDouble} rather than a double for exactly that reason: the alternative to "no
   * answer" is not a harmless default but a plausible-looking lie. Sending 0 would score a ball
   * pulled down the line as though it were hit to dead centre, and CLAMPING a degenerate value to
   * the foul line would be worse still - it renders as a real measurement that nothing downstream
   * can distinguish from one.
   *
   * <p>TWO INDEPENDENT GATES, deliberately not redundant:
   *
   * <ol>
   *   <li>CAUSE - {@code PLATE_Y - hcY <= 0} means the ball was tracked at or BEHIND the plate, so
   *       the denominator has crossed zero. Bunts, choppers and pop-ups fielded near the plate land
   *       here. For almost all of them this gate is SUBSUMED by the invariant below, because a
   *       non-positive denominator drives {@code atan2} to {@code |angle| >= 90}. Its one
   *       load-bearing input is the ORIGIN: at {@code dx == 0 && dy == 0} Java defines {@code
   *       atan2(0, 0) == 0.0}, which is dead centre and INSIDE fair territory - so a total tracking
   *       failure whose coordinates defaulted to the plate would otherwise emerge as a confident
   *       0-degree spray. That single case is why this is a gate and not a comment.
   *   <li>INVARIANT - a fair ball must lie inside the foul lines. This is a PHYSICAL constraint the
   *       formula must satisfy, not a restatement of the math, so it catches degeneracies that have
   *       not been characterised yet. It already earns its place: a line drive tracked at (37.5,
   *       113.8) - far from the plate, so the cause-gate passes it - comes out at -45.7 degrees,
   *       just outside the line through coordinate rounding near the corner.
   * </ol>
   *
   * <p>Each gate is pinned by a test that reds when only that gate is removed; the pairing was
   * VERIFIED rather than asserted, which is how the origin case was found at all.
   *
   * <p>Measured cost of declining, over 187 balls in play: 7.5% overall, but only 2.2% at 150+ ft
   * and the single meaningful case a 121 ft pop-up. On every ball that could plausibly be compared
   * across parks for home-run outcome, spray is reliable - so graceful degradation costs almost
   * nothing on the cases this feature exists to serve.
   *
   * <p>This is the raw geometric angle. Pull-versus-opposite-field is the CALLER's concern: a
   * left-handed batter's pull side is the same physical direction as a right-hander's opposite
   * field, so a consumer that cares must fold in {@code stand} rather than assume.
   */
  public OptionalDouble sprayAngleDeg() {
    return sprayAngleDeg(hcX, hcY);
  }

  /**
   * The same derivation from bare coordinates, for callers that have hc_x / hc_y but no record -
   * the repository mapper, which reads columns.
   *
   * <p>Static rather than "construct a BattedBall with placeholder physics and call the instance
   * method": this record's contract is that one which exists is ENTIRELY REAL, and manufacturing a
   * fake to borrow a method would be the first violation of it, in the class that documents it. One
   * implementation either way - which is the point, since a second copy in TypeScript or SQL would
   * be a second set of degeneracy gates to keep in step.
   */
  public static OptionalDouble sprayAngleDeg(double hcX, double hcY) {
    double towardOutfield = PLATE_Y - hcY;
    if (towardOutfield <= 0) {
      return OptionalDouble.empty();
    }
    // SIGN AND CONSTANTS MUST MATCH TRAINING EXACTLY (features_shared.hc_to_spray_deg): positive
    // is toward 3B / LEFT FIELD, so it is PLATE_X - hcX, not hcX - PLATE_X. The inverted form
    // shipped briefly and fed the served model the opposite field on every live batted ball -
    // which corrupts precisely what a PER-PARK comparison is for, since park asymmetry is the
    // whole signal. FeaturePipelineBattedBall passes spray_angle_deg straight through, so
    // whatever is produced here reaches the model unmodified.
    double deg = Math.toDegrees(Math.atan2(PLATE_X - hcX, towardOutfield));
    return Math.abs(deg) > FOUL_LINE_DEG ? OptionalDouble.empty() : OptionalDouble.of(deg);
  }
}
