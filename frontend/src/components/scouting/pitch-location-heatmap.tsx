/**
 * <PitchLocationHeatmap> — 4-up small-multiples of pitcher pitch-location KDEs.
 *
 * Each panel renders one pitch (FF / SL / CB / CH) as a 12×12 SVG grid with
 * the heatWarm 4-stop ramp applied per cell. A strike-zone rectangle is
 * overlaid in navy. Panel header shows the pitch code + usage % + average velo
 * — those three numbers together make the heatmap useful at a glance even if
 * the color is hard to read.
 *
 * Pure SVG. role="img" + aria-label per panel (a11y rule: every SVG has both).
 * The label paraphrases the visible peak so a screen reader user gets the
 * same information.
 *
 * Color is paired with: (1) the panel header text (pitch + velo + usage) so
 * the panel is identifiable even in monochrome; (2) the aria-label which
 * describes the peak location in words.
 */

import { colors, typography } from "../../design/tokens";
import type { PitchMixRow } from "../../data/matchup-fixtures";

export type PitchLocationHeatmapProps = {
  /** Up to 4 pitches; renders one panel per row. Extras after 4 wrap. */
  pitches: PitchMixRow[];
  /** Optional caption above the small-multiples. */
  caption?: string;
};

// ── Heatmap geometry ─────────────────────────────────────────────────────────

const PANEL_SIZE = 168;
const CELL = 12;
const GRID_N = 12;
const PAD = 12; // inner padding for the grid inside the panel

// Strike zone occupies the center 60% of the 12×12 grid (cells 3–8 inclusive
// on both axes, roughly).
const SZ_COL_START = 3;
const SZ_COL_END = 9; // exclusive
const SZ_ROW_START = 3;
const SZ_ROW_END = 9;

function rampColor(value: number): string {
  // Map [0,1] → 4-stop heatWarm via piecewise-linear stops.
  // Stops at 0, 0.33, 0.66, 1.0.
  const ramp = colors.heatWarm;
  const clamped = Math.max(0, Math.min(1, value));
  if (clamped <= 0) return ramp[0];
  if (clamped >= 1) return ramp[3];
  if (clamped < 1 / 3) {
    return ramp[Math.round(clamped * 3 * 1)] ?? ramp[0];
  }
  if (clamped < 2 / 3) {
    return ramp[1 + Math.round((clamped - 1 / 3) * 3 * 1)] ?? ramp[1];
  }
  return ramp[2 + Math.round((clamped - 2 / 3) * 3 * 1)] ?? ramp[2];
}

function describePeak(grid: number[][]): string {
  let bestR = 0;
  let bestC = 0;
  let best = -1;
  for (let r = 0; r < grid.length; r++) {
    for (let c = 0; c < grid[r].length; c++) {
      if (grid[r][c] > best) {
        best = grid[r][c];
        bestR = r;
        bestC = c;
      }
    }
  }
  const vertical =
    bestR <= 3 ? "top of zone" : bestR >= 8 ? "below the zone" : "middle";
  const horizontal =
    bestC <= 3 ? "glove side" : bestC >= 8 ? "arm side" : "middle";
  return `${vertical}, ${horizontal}`;
}

function Panel({ pitch }: { pitch: PitchMixRow }) {
  const ariaLabel = `${pitch.name} location density, peak ${describePeak(pitch.locationGrid)}, ${(pitch.usage * 100).toFixed(0)}% usage, ${pitch.velo.toFixed(1)} mph`;
  const gridSizePx = CELL * GRID_N;
  return (
    <figure
      style={{
        margin: 0,
        padding: 0,
        display: "flex",
        flexDirection: "column",
        gap: 4,
        backgroundColor: colors.bgSheet,
        border: `1px solid ${colors.bgEmphasis}`,
        borderRadius: 2,
      }}
    >
      <header
        style={{
          display: "flex",
          justifyContent: "space-between",
          alignItems: "baseline",
          padding: "6px 10px",
          backgroundColor: colors.bgSubtle,
          borderBottom: `1px solid ${colors.bgEmphasis}`,
        }}
      >
        <span
          style={{
            fontFamily: typography.fonts.display,
            fontSize: 13,
            fontWeight: typography.weights.bold,
            textTransform: "uppercase",
            letterSpacing: "0.04em",
            color: colors.textStrong,
          }}
        >
          {pitch.code} · {pitch.name}
        </span>
        <span
          style={{
            fontFamily: typography.fonts.mono,
            fontSize: 11,
            color: colors.textMuted,
            fontFeatureSettings: '"tnum" 1',
          }}
        >
          {(pitch.usage * 100).toFixed(0)}% · {pitch.velo.toFixed(1)} mph
        </span>
      </header>
      <svg
        role="img"
        aria-label={ariaLabel}
        width={PANEL_SIZE}
        height={PANEL_SIZE}
        viewBox={`0 0 ${PANEL_SIZE} ${PANEL_SIZE}`}
        style={{ display: "block", margin: "0 auto" }}
      >
        <title>{ariaLabel}</title>
        {/* Background */}
        <rect
          x={0}
          y={0}
          width={PANEL_SIZE}
          height={PANEL_SIZE}
          fill={colors.bgSheet}
        />
        {/* Heat cells */}
        {pitch.locationGrid.flatMap((row, r) =>
          row.map((v, c) => (
            <rect
              key={`${r}-${c}`}
              x={PAD + c * CELL}
              y={PAD + r * CELL}
              width={CELL}
              height={CELL}
              fill={rampColor(v)}
              fillOpacity={0.92}
            />
          )),
        )}
        {/* Strike zone */}
        <rect
          x={PAD + SZ_COL_START * CELL}
          y={PAD + SZ_ROW_START * CELL}
          width={(SZ_COL_END - SZ_COL_START) * CELL}
          height={(SZ_ROW_END - SZ_ROW_START) * CELL}
          fill="none"
          stroke={colors.navy}
          strokeWidth={1.5}
        />
        {/* Outer frame */}
        <rect
          x={PAD}
          y={PAD}
          width={gridSizePx}
          height={gridSizePx}
          fill="none"
          stroke={colors.bgEmphasis}
        />
      </svg>
      <figcaption
        style={{
          fontFamily: typography.fonts.mono,
          fontSize: 10,
          color: colors.textMuted,
          textAlign: "center",
          padding: "0 8px 8px",
          letterSpacing: "0.02em",
        }}
      >
        Whiff {(pitch.whiff * 100).toFixed(0)}% · xwOBA {pitch.xwoba.toFixed(3)}
      </figcaption>
    </figure>
  );
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
              width: 18,
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

export function PitchLocationHeatmap({
  pitches,
  caption,
}: PitchLocationHeatmapProps) {
  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 8 }}>
      {caption ? (
        <div
          style={{
            fontFamily: typography.fonts.body,
            fontSize: 12,
            fontWeight: typography.weights.semibold,
            color: colors.textMuted,
            letterSpacing: "0.04em",
            textTransform: "uppercase",
          }}
        >
          {caption}
        </div>
      ) : null}
      <div
        style={{
          display: "grid",
          gridTemplateColumns: "repeat(2, minmax(0, 1fr))",
          gap: 12,
        }}
      >
        {pitches.map((p) => (
          <Panel key={p.code} pitch={p} />
        ))}
      </div>
      <Legend />
    </div>
  );
}
