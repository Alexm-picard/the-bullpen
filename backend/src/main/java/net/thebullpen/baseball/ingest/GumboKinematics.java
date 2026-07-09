package net.thebullpen.baseball.ingest;

/**
 * Derives the four Savant-equivalent Tier-4 pitch features ({@code pfx_x}, {@code pfx_z}, {@code
 * release_pos_x}, {@code release_pos_z}) from the raw 9-parameter trajectory fit the MLB GUMBO live
 * feed reports, so the live {@code pitch_outcome_post} prediction sees the SAME quantities the
 * model trained on.
 *
 * <p>Why not use GUMBO's reported {@code pitchData.coordinates.pfxX/pfxZ/x0/z0} directly? They are
 * definitionally DIFFERENT quantities from the pitches-table columns: GUMBO's pfx is measured over
 * the y=40 span (~60% of full flight), and {@code x0/z0} are the y0~=50 fit reference, not the
 * release point. Feeding them would be a silent train/serve skew. The pass-through fields ({@code
 * startSpeed}, {@code breaks.spinRate/spinDirection}, {@code coordinates.pX/pZ}, pitch type) ARE
 * identical in units + frame and are handled by the parser.
 *
 * <p>The formulas below (all in GUMBO's native feet / seconds) were validated on the box against
 * one full 2026 game (n=250 pitches matched pitch-for-pitch to the pitches table): release_pos
 * matched to storage precision (sd 0.003 ft), pfx to within the table's own 1-decimal rounding +
 * the live-vs-postgame tracking refit (sd 0.065-0.09 ft), with the y=0 plate convention clearly
 * beating y=17/12.
 *
 * <p>Pure + static; no I/O, no JSON (the parser does the extraction + null-check).
 */
public final class GumboKinematics {

  /** Gravitational acceleration, ft/s^2 (removed from a_z to isolate induced vertical movement). */
  private static final double G = 32.174;

  /** Plate crossing plane, y = 0 ft (the validated forward-integration reference). */
  private static final double Y_PLATE = 0.0;

  /** Front of the rubber, ft from the plate; release is this minus the pitcher's extension. */
  private static final double MOUND_TO_PLATE_FT = 60.5;

  private GumboKinematics() {}

  /**
   * The raw 9-parameter constant-acceleration trajectory fit ({@code pitchData.coordinates.*}) plus
   * the pitcher's release {@code extension} ({@code pitchData.extension}), as reported by GUMBO.
   * All in feet / seconds.
   */
  public record Fit(
      double x0,
      double y0,
      double z0,
      double vX0,
      double vY0,
      double vZ0,
      double aX,
      double aY,
      double aZ,
      double extension) {}

  /** The four derived Savant-equivalent features, in feet (matching the pitches-table columns). */
  public record Derived(double pfxXFt, double pfxZFt, double releasePosXFt, double releasePosZFt) {}

  /**
   * Derive the four Tier-4 features from the fit. Forward-integrate to the plate for pfx;
   * extrapolate BACKWARD to the release plane for release position - both using the SAME {@code
   * (-vY0 - sqrt)/aY} root (see {@link #timeToPlane}).
   */
  public static Derived derive(Fit f) {
    double t = timeToPlane(f, Y_PLATE);
    double pfxX = 0.5 * f.aX() * t * t;
    double pfxZ = 0.5 * (f.aZ() + G) * t * t; // add G back: a_z already has gravity in it

    double yRelease = MOUND_TO_PLATE_FT - f.extension();
    double tb = timeToPlane(f, yRelease); // ~= -0.03 s (backward from the y0~=50 fit origin)
    double releaseX = f.x0() + f.vX0() * tb + 0.5 * f.aX() * tb * tb;
    double releaseZ = f.z0() + f.vZ0() * tb + 0.5 * f.aZ() * tb * tb;

    return new Derived(pfxX, pfxZ, releaseX, releaseZ);
  }

  /**
   * Time (s) from the fit origin (y0 ~= 50) to plane {@code y = yTarget}, solving {@code y0 + vY0*t
   * + 0.5*aY*t^2 = yTarget}.
   *
   * <p>The {@code (-vY0 - sqrt(...))/aY} (MINUS) root is correct for BOTH the forward plate
   * crossing (t &gt; 0) and the backward release extrapolation (t &lt; 0, ~-0.03 s). The {@code +}
   * root is a TRAP: it grabs a spurious ~+8.8 s "solution" that puts release_z thousands of feet
   * off. Do not flip it (there is a unit test guarding exactly this).
   */
  static double timeToPlane(Fit f, double yTarget) { // package-private: the root-trap test pins it
    double disc = f.vY0() * f.vY0() - 2.0 * f.aY() * (f.y0() - yTarget);
    return (-f.vY0() - Math.sqrt(disc)) / f.aY();
  }
}
