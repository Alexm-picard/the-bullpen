/**
 * The /parks what-if ball, in words (SPEC-parks section 5). Pure; no React.
 *
 * Spray follows the API's ABSOLUTE convention (+ toward left field / third base), which is the
 * training convention passed through unmodified. It is never described as pull or opposite field:
 * those swap with the batter's hand, so a batter-relative label is right for one side only.
 */
import { parkName } from "./park-names";

export type Ball = {
  launchSpeedMph: number;
  launchAngleDeg: number;
  sprayAngleDeg: number;
  stand: "L" | "R";
};

export function sameBall(a: Ball, b: Ball): boolean {
  return (
    a.launchSpeedMph === b.launchSpeedMph &&
    a.launchAngleDeg === b.launchAngleDeg &&
    a.sprayAngleDeg === b.sprayAngleDeg &&
    a.stand === b.stand
  );
}

/** "Straightaway", "22 deg to left field", "22 deg to right field". Never "foul". */
export function sprayWords(deg: number): string {
  const n = Math.round(Math.abs(deg));
  if (n === 0) return "Straightaway";
  return `${n} deg to ${deg > 0 ? "left" : "right"} field`;
}

/** Screen-reader form of the same direction. */
export function sprayValueText(deg: number): string {
  const n = Math.round(Math.abs(deg));
  if (n === 0) return "straightaway";
  return `${n} degrees to ${deg > 0 ? "left" : "right"} field`;
}

/** "110 mph, 28 deg up, straightaway, right-handed batter". */
export function describeBall(b: Ball): string {
  const angle =
    b.launchAngleDeg < 0
      ? `${Math.abs(b.launchAngleDeg)} deg down`
      : `${b.launchAngleDeg} deg up`;
  return `${b.launchSpeedMph} mph, ${angle}, ${sprayWords(b.sprayAngleDeg).toLowerCase()}, ${
    b.stand === "R" ? "right" : "left"
  }-handed batter`;
}

export type CarryEnd = { id: string; name: string; ft: number };

/** The shortest and longest per-park carry, ties broken by id so the labels are stable. */
export function carryExtremes(
  carryFtByPark: Record<string, number> | undefined,
): { min: CarryEnd; max: CarryEnd } | null {
  if (!carryFtByPark) return null;
  const rows = Object.entries(carryFtByPark)
    .filter(([, ft]) => Number.isFinite(ft))
    .sort(([a, fa], [b, fb]) => fa - fb || a.localeCompare(b));
  if (rows.length === 0) return null;
  const [minId, minFt] = rows[0]!;
  // The longest: highest carry, and among equals the alphabetically first id.
  const maxFt = rows[rows.length - 1]![1];
  const maxId = rows.find(([, ft]) => ft === maxFt)![0];
  return {
    min: { id: minId, name: parkName(minId), ft: Math.round(minFt) },
    max: { id: maxId, name: parkName(maxId), ft: Math.round(maxFt) },
  };
}

/** P(HR) in the agate voice: ".62", ">.99", "<.01". */
export function probText(p: number): string {
  if (p >= 0.995) return ">.99";
  if (p < 0.005) return "<.01";
  return `.${String(Math.round(p * 100)).padStart(2, "0")}`;
}
