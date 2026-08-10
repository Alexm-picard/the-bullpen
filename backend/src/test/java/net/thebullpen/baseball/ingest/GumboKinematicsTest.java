package net.thebullpen.baseball.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import net.thebullpen.baseball.ingest.GumboKinematics.Derived;
import net.thebullpen.baseball.ingest.GumboKinematics.Fit;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link GumboKinematics} - the 9-param -> Savant-equivalent Tier-4 derivation. */
class GumboKinematicsTest {

  // A physically plausible RHP fastball (GUMBO native ft / s), hand-computed expected outputs
  // below.
  private static final Fit FASTBALL =
      new Fit(
          -1.5, // x0
          50.0, // y0
          5.8, // z0
          5.0, // vX0
          -130.0, // vY0 (toward plate, negative y)
          -5.0, // vZ0
          -10.0, // aX
          27.0, // aY (drag)
          -15.0, // aZ (gravity + induced lift)
          6.5); // extension

  @Test
  void derives_pfx_and_release_matching_the_hand_computed_trajectory() {
    Derived d = GumboKinematics.derive(FASTBALL);
    // t (forward to y=0) = (130 - sqrt(130^2 - 2*27*50)) / 27 = 0.4015 s
    //   pfx_x = 0.5 * -10 * t^2                 = -0.806 ft
    //   pfx_z = 0.5 * (-15 + 32.174) * t^2      =  1.384 ft
    // tb (backward to y=54) = (130 - sqrt(130^2 + 216)) / 27 = -0.0307 s
    //   release_x = -1.5 + 5*tb + 0.5*-10*tb^2  = -1.658 ft
    //   release_z = 5.8 + -5*tb + 0.5*-15*tb^2  =  5.946 ft
    assertThat(d.pfxXFt()).isCloseTo(-0.806, within(0.005));
    assertThat(d.pfxZFt()).isCloseTo(1.384, within(0.005));
    assertThat(d.releasePosXFt()).isCloseTo(-1.658, within(0.005));
    assertThat(d.releasePosZFt()).isCloseTo(5.946, within(0.005));
  }

  @Test
  void uses_the_minus_root_forward_and_backward_not_the_spurious_plus_root() {
    // Forward crossing (y=0) is the near root ~0.4 s, NOT the far ~9.2 s the + root would grab.
    double tForward = GumboKinematics.timeToPlane(FASTBALL, 0.0);
    assertThat(tForward).as("forward plate-crossing time").isCloseTo(0.4015, within(0.001));
    assertThat(tForward).isPositive().isLessThan(1.0);

    // Backward to the release plane (y=54) is a SMALL NEGATIVE time (~-0.03 s), not a large one.
    double tBackward = GumboKinematics.timeToPlane(FASTBALL, 54.0);
    assertThat(tBackward)
        .as("backward release-extrapolation time")
        .isCloseTo(-0.0307, within(0.001));
    assertThat(tBackward).isNegative().isGreaterThan(-0.2);

    // The trap: the + root at y=0 is a garbage ~9.2 s solution. Prove derive() did NOT use it -
    // a +root release_z would be thousands of feet off, so a sane release height is the guard.
    double plusRoot =
        (-FASTBALL.vY0()
                + Math.sqrt(FASTBALL.vY0() * FASTBALL.vY0() - 2 * FASTBALL.aY() * FASTBALL.y0()))
            / FASTBALL.aY();
    assertThat(plusRoot).as("the spurious + root").isGreaterThan(8.0);
    assertThat(GumboKinematics.derive(FASTBALL).releasePosZFt())
        .as("release height stays physical (~6 ft), proving the minus root")
        .isBetween(4.0, 7.0);
  }
}
