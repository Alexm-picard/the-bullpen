/**
 * <ParkFieldSvg> — generic baseball-field outline (no real stadium chrome).
 *
 * Decoration + spatial anchor for the SPOTLIGHT block: a stylised home-plate
 * → foul-line → infield-arc → outfield-arc shape, drawn at 360×300 by
 * default. Intentionally _generic_ — no real outfield-wall outlines, no
 * stadium-specific landmarks. The accompanying heatmap is the data; the
 * field is the canvas.
 *
 * Home plate sits center-bottom; foul lines fan out at ±45° to the corners;
 * the infield is a light-dirt arc (bgSubtle fill); the outfield is a navy
 * stroke arc. role="img" + aria-label per the project's SVG rule.
 */

import { colors } from "../../design/broadcast";

export type ParkFieldSvgProps = {
  width?: number;
  height?: number;
  /** Optional aria-label override; defaults to a generic description. */
  ariaLabel?: string;
};

export function ParkFieldSvg({
  width = 360,
  height = 300,
  ariaLabel = "Generic baseball field outline — home plate bottom-center, foul lines at 45 degrees, infield dirt arc, outfield navy stroke arc",
}: ParkFieldSvgProps) {
  // Coordinate system: 0,0 top-left. Home plate at (180, 280).
  const homeX = width / 2;
  const homeY = height - 20;

  // Foul-line tips (corners of the field). The ±45° lines extend to the
  // top edges of the SVG canvas — they're clipped visually by the outfield
  // arc, but the geometry below the arc reads as the foul territory.
  const lfTipX = homeX - (homeY - 20); // 45° to upper-left
  const lfTipY = 20;
  const rfTipX = homeX + (homeY - 20); // 45° to upper-right
  const rfTipY = 20;

  // Infield arc radius and outfield arc radius.
  const infieldR = 70;
  const outfieldR = Math.min(width, height) * 0.78;

  return (
    <svg
      role="img"
      aria-label={ariaLabel}
      width={width}
      height={height}
      viewBox={`0 0 ${width} ${height}`}
      style={{ display: "block" }}
    >
      <title>{ariaLabel}</title>
      {/* Outfield arc — navy stroke, no fill */}
      <path
        d={`M ${homeX - outfieldR} ${homeY}
            A ${outfieldR} ${outfieldR} 0 0 1 ${homeX + outfieldR} ${homeY}`}
        fill="none"
        stroke={colors.chrome}
        strokeWidth={1.5}
      />
      {/* Foul lines */}
      <line
        x1={homeX}
        y1={homeY}
        x2={lfTipX}
        y2={lfTipY}
        stroke={colors.rule}
        strokeWidth={1}
      />
      <line
        x1={homeX}
        y1={homeY}
        x2={rfTipX}
        y2={rfTipY}
        stroke={colors.rule}
        strokeWidth={1}
      />
      {/* Infield arc — light-dirt bgSubtle fill */}
      <path
        d={`M ${homeX - infieldR} ${homeY}
            A ${infieldR} ${infieldR} 0 0 1 ${homeX + infieldR} ${homeY}
            Z`}
        fill={colors.fieldSubtle}
        stroke={colors.rule}
        strokeWidth={1}
      />
      {/* Home plate — small navy diamond */}
      <polygon
        points={`${homeX},${homeY - 5} ${homeX + 5},${homeY} ${homeX},${homeY + 5} ${homeX - 5},${homeY}`}
        fill={colors.chrome}
      />
    </svg>
  );
}
