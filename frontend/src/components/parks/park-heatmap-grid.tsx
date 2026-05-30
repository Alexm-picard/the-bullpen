/**
 * <ParkHeatmapGrid> — 12×12 batted-ball landing-density heatmap.
 *
 * Pure SVG. Each cell of the input grid (normalized [0,1]) is rendered as
 * a heatWarm-ramp colored rect. A navy outfield-arc stroke is overlaid so
 * the heatmap reads as "balls landing inside the field of play" rather
 * than "abstract density grid." A legend strip below shows the ramp stops
 * with "low / high density" anchors.
 *
 * role="img" + aria-label per the SVG rule; caption rendered above the SVG
 * as a small uppercase mono line.
 */

import { colors, typography } from "../../design/tokens";

export type ParkHeatmapGridProps = {
  /** 12×12 normalized [0,1] grid. */
  grid: number[][];
  /** Short caption line above the heatmap. */
  caption: string;
};

const SVG_SIZE = 360;
const GRID_N = 12;
const CELL = SVG_SIZE / GRID_N; // 30

function rampColor(value: number): string {
  const ramp = colors.heatWarm;
  const clamped = Math.max(0, Math.min(1, value));
  if (clamped <= 0) return ramp[0];
  if (clamped >= 1) return ramp[3];
  if (clamped < 1 / 3) return ramp[0];
  if (clamped < 2 / 3) return ramp[1];
  if (clamped < 0.9) return ramp[2];
  return ramp[3];
}

function describePeak(grid: number[][]): string {
  let bestR = 0;
  let bestC = 0;
  let best = -1;
  for (let r = 0; r < grid.length; r++) {
    const row = grid[r];
    if (row === undefined) continue;
    for (let c = 0; c < row.length; c++) {
      const value = row[c];
      if (value !== undefined && value > best) {
        best = value;
        bestR = r;
        bestC = c;
      }
    }
  }
  const horizontal =
    bestC <= 3 ? "left field" : bestC >= 8 ? "right field" : "center";
  const vertical = bestR <= 4 ? "deep" : bestR >= 8 ? "shallow" : "mid";
  return `${vertical} ${horizontal}`;
}

function Legend() {
  return (
    <div
      style={{
        display: "flex",
        alignItems: "center",
        gap: 8,
        fontFamily: typography.fonts.mono,
        fontSize: 10,
        color: colors.textMuted,
      }}
    >
      <span style={{ letterSpacing: "0.04em", textTransform: "uppercase" }}>
        Low
      </span>
      <div style={{ display: "flex", gap: 1 }}>
        {colors.heatWarm.map((c, i) => (
          <div
            key={i}
            style={{
              width: 24,
              height: 8,
              backgroundColor: c,
              border: `1px solid ${colors.bgEmphasis}`,
            }}
          />
        ))}
      </div>
      <span style={{ letterSpacing: "0.04em", textTransform: "uppercase" }}>
        High density
      </span>
    </div>
  );
}

export function ParkHeatmapGrid({ grid, caption }: ParkHeatmapGridProps) {
  const peak = describePeak(grid);
  const ariaLabel = `Batted-ball landing density heatmap, 12 by 12 grid, peak density ${peak}.`;

  // Outfield arc geometry — same proportions as <ParkFieldSvg> at 360-wide.
  const homeX = SVG_SIZE / 2;
  const homeY = SVG_SIZE - 12;
  const outfieldR = SVG_SIZE * 0.78;

  return (
    <figure
      style={{ margin: 0, display: "flex", flexDirection: "column", gap: 8 }}
    >
      <figcaption
        style={{
          fontFamily: typography.fonts.mono,
          fontSize: 11,
          color: colors.textMuted,
          letterSpacing: "0.04em",
          textTransform: "uppercase",
        }}
      >
        {caption}
      </figcaption>
      <svg
        role="img"
        aria-label={ariaLabel}
        width={SVG_SIZE}
        height={SVG_SIZE}
        viewBox={`0 0 ${SVG_SIZE} ${SVG_SIZE}`}
        style={{
          display: "block",
          border: `1px solid ${colors.bgEmphasis}`,
          backgroundColor: colors.bgSheet,
        }}
      >
        <title>{ariaLabel}</title>
        {/* Heat cells */}
        {grid.flatMap((row, r) =>
          row.map((v, c) => (
            <rect
              key={`${r}-${c}`}
              x={c * CELL}
              y={r * CELL}
              width={CELL}
              height={CELL}
              fill={rampColor(v)}
              fillOpacity={0.88}
            />
          )),
        )}
        {/* Outfield arc overlay */}
        <path
          d={`M ${homeX - outfieldR} ${homeY}
              A ${outfieldR} ${outfieldR} 0 0 1 ${homeX + outfieldR} ${homeY}`}
          fill="none"
          stroke={colors.navy}
          strokeWidth={1.5}
          strokeOpacity={0.7}
        />
      </svg>
      <Legend />
    </figure>
  );
}
