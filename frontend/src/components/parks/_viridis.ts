/**
 * Viridis colormap helper (leaf 4c.4) — interpolates the 5-stop ramp from
 * `tokens.colors.viz.viridis` into a continuous [0, 1] → hex function.
 *
 * Five anchor stops at indices 0/0.25/0.50/0.75/1.0; piecewise-linear in sRGB.
 * Good enough for thumbnail fills; the perceptual non-uniformity introduced by
 * sRGB lerp is below what the eye can pick out at 160-pixel tiles.
 */
import { colors } from "../../design/broadcast";

const STOPS = colors.viz.viridis;

function hexToRgb(hex: string): [number, number, number] {
  const h = hex.replace("#", "");
  return [
    parseInt(h.slice(0, 2), 16),
    parseInt(h.slice(2, 4), 16),
    parseInt(h.slice(4, 6), 16),
  ];
}

function rgbToHex(r: number, g: number, b: number): string {
  const toHex = (n: number) =>
    Math.max(0, Math.min(255, Math.round(n)))
      .toString(16)
      .padStart(2, "0");
  return `#${toHex(r)}${toHex(g)}${toHex(b)}`;
}

const RGB_STOPS = STOPS.map(hexToRgb);

/** Map x ∈ [0, 1] to a Viridis hex colour. NaN / out-of-range → endpoint colours. */
export function viridis(x: number): string {
  // STOPS / RGB_STOPS are fixed non-empty arrays and i is clamped to a valid
  // segment by the x ∈ (0,1) guards above, so these index accesses are in-bounds.
  if (Number.isNaN(x) || x <= 0) return STOPS[0]!;
  if (x >= 1) return STOPS[STOPS.length - 1]!;
  const segments = RGB_STOPS.length - 1; // 4 segments for 5 stops
  const scaled = x * segments;
  const i = Math.floor(scaled);
  const t = scaled - i;
  const [r0, g0, b0] = RGB_STOPS[i]!;
  const [r1, g1, b1] = RGB_STOPS[i + 1]!;
  return rgbToHex(r0 + (r1 - r0) * t, g0 + (g1 - g0) * t, b0 + (b1 - b0) * t);
}
