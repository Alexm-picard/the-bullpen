package net.thebullpen.baseball.domain;

/**
 * One REAL batted ball hit by a team's batter, for the season-to-date team comparison the game page
 * shows before any ball has been put in play.
 *
 * <p>Deliberately a list of actual observations rather than a "typical" batted ball. Taking a
 * team's median launch speed and median launch angle produces a point that never occurred - the
 * median of marginals is not an observation - and feeding it to the model would be the same defect
 * as {@code sprayAngleDeg: 0}, dressed as statistics. It would also read as authoritative on the
 * card. The aggregate is computed from the model's OUTPUTS over real inputs, which is what an
 * aggregate should be: the answer to "which batted ball is this?" is "all of them, and here is n".
 *
 * <p>Only balls whose spray is honestly derivable are returned - the degenerate ones decline
 * themselves via {@link BattedBall#sprayAngleDeg(double, double)}, so no fabricated input enters
 * the profile.
 *
 * @param launchSpeedMph exit velocity, mph.
 * @param launchAngleDeg vertical launch angle, degrees.
 * @param hitDistanceFt Statcast projected landing distance, feet.
 * @param sprayAngleDeg derived spray, guaranteed non-null and inside the foul lines.
 * @param stand batter side, already resolved to L or R.
 */
public record TeamContactBall(
    double launchSpeedMph,
    double launchAngleDeg,
    double hitDistanceFt,
    double sprayAngleDeg,
    String stand) {}
