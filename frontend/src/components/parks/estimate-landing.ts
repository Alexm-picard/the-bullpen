/**
 * Crude deterministic landing-distance estimate from launch parameters.
 * Calibrated so the default 110 mph / 28° canonical input lands at ~400 ft.
 *
 * Lives in its own module so React Fast Refresh stays happy with the polished
 * thumbnail file (Fast Refresh refuses to mix a memoised component + a helper
 * export in one file). The simulator's real range model is the Phase 2c.5
 * work; this is a UI-only approximation.
 */
export function estimateLandingDistanceFt(
  launchSpeedMph: number,
  launchAngleDeg: number,
): number {
  const speedFactor = (launchSpeedMph / 110) ** 1.6;
  const angleFactor = Math.cos(((launchAngleDeg - 28) * Math.PI) / 180);
  const base = 400; // calibrated for 110/28
  return Math.max(60, base * speedFactor * Math.max(angleFactor, 0.25));
}
