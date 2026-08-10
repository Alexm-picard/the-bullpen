package net.thebullpen.baseball.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.OptionalDouble;
import org.junit.jupiter.api.Test;

/**
 * Spray-angle derivation, pinned against REAL landing coordinates pulled from live and completed
 * GUMBO feeds on 2026-08-03 (game 823431 in progress, plus four completed games from 08-02; 187
 * balls in play).
 *
 * <p>Real coordinates rather than invented ones on purpose: the degeneracy this guards against was
 * found by validating output against the foul-line invariant over that sample, NOT by re-reading
 * the formula. Synthetic coordinates would have been chosen to match whatever the implementation
 * already did, and would have confirmed the arithmetic instead of testing the physics.
 */
class BattedBallTest {

  private static BattedBall at(double hcX, double hcY) {
    return new BattedBall(99.5, 35.0, 373.0, hcX, hcY, "fly_ball", "Home Run");
  }

  @Test
  void derivesSprayForBallsHitIntoTheField() {
    // Observed home runs. Signs matter: negative is toward the left-field line.
    // NEGATIVE: training's convention is positive toward 3B/left field, so a ball to right field
    // is negative. The shipped version had this at +27.8 - the sign inversion, pinned by a test
    // that agreed with the implementation instead of with training.
    assertThat(at(196.18, 65.51).sprayAngleDeg().orElseThrow()).isCloseTo(-28.057, within(0.01));
    // Every derived angle must sit inside fair territory - the invariant the sample was checked
    // against.
    for (double[] xy :
        new double[][] {{196.18, 65.51}, {202.39, 95.86}, {150.15, 153.92}, {184.99, 112.47}}) {
      // Bounded because these particular balls were hit into the field, NOT because a bound is
      // enforced - the angle gate is gone. Kept as a sanity range on real coordinates.
      assertThat(at(xy[0], xy[1]).sprayAngleDeg().orElseThrow()).isBetween(-60.0, 60.0);
    }
  }

  @Test
  void declinesWhenTheBallWasTrackedAtOrBehindThePlate() {
    // GATE 1 (cause). Real rows: a bunt pop out and a pop out, both tracked past the plate's y of
    // 198.27, where the denominator crosses zero and atan2 flips quadrant. Unguarded these produce
    // -92.4 and +102.1 - numbers that would render on the card as though measured.
    assertThat(at(111.4, 200.1).sprayAngleDeg()).isEmpty();
    assertThat(at(139.2, 202.5).sprayAngleDeg()).isEmpty();
    // Exactly at the plate depth is degenerate too, not a 90-degree spray.
    assertThat(at(150.0, 198.27).sprayAngleDeg()).isEmpty();
  }

  @Test
  void declinesTheOriginRatherThanCallingItDeadCentre() {
    // THE input that only the cause-gate catches, and the reason it is not decoration.
    //
    // For every other degenerate row the invariant-gate is sufficient: dy <= 0 makes atan2 return
    // |angle| >= 90, comfortably outside the foul lines. But at the ORIGIN itself - a ball tracked
    // at exactly home plate, dx = 0 AND dy = 0 - Java's atan2(0, 0) is defined as 0.0, which is
    // dead centre and INSIDE fair territory. Without the cause-gate a total tracking failure whose
    // coordinates defaulted to the plate would emerge as a confident 0-degree spray: a fabricated
    // measurement nothing downstream could distinguish from a real one.
    // The plate is (125.42, 198.27) - the PUBLISHED Statcast constants, the ones training uses.
    // This assertion previously used 199.53 and kept passing after the constant was corrected,
    // because that point is now BEHIND the plate and the invariant gate rejects it - so it would
    // have gone on testing the wrong gate under the name of the right one.
    assertThat(at(125.42, 198.27).sprayAngleDeg()).isEmpty();
  }

  @Test
  void acceptsARealHomeRunBeyondFortyFiveDegrees() {
    // The angle gate was REMOVED, and this is the case that killed it. Across 3,823 home runs in
    // 2026 - fair by definition, so a hard empirical bound on fair territory - p99 is 47.3 degrees
    // and the max is 52.7, with 195 (5.1%) outside the old plus/minus 45 box. The projection's box
    // is not the foul line. Declining one home run in twenty on a home-run comparison card was the
    // gate being confidently wrong about the world.
    double steep = BattedBall.sprayAngleDeg(37.5, 113.8).orElseThrow();
    assertThat(Math.abs(steep))
        .as("a real ball at 46 degrees must now be SERVED, not declined")
        .isGreaterThan(45.0);
  }

  @Test
  void aDeclinedSprayIsEmptyRatherThanZero() {
    // The whole point. OptionalDouble.empty() forces a caller to handle absence; a 0.0 return
    // would silently score a bunt behind the plate as a ball hit to dead centre, and would be
    // indistinguishable downstream from a real measurement.
    OptionalDouble declined = at(111.4, 200.1).sprayAngleDeg();
    assertThat(declined).isEmpty();
    assertThat(declined.orElse(Double.NaN)).isNaN();
  }
}
