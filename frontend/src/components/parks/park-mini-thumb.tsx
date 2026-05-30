/**
 * <ParkMiniThumb> — the 88×104 button-shaped switcher tile.
 *
 * Visual: 80×80 SVG of a 6×6 heatmap (heatWarm ramp) above a 3-letter team
 * abbreviation in Saira Condensed bold 12px uppercase tracked +0.06em.
 * Active state (the current spotlight park) wraps the tile in a 2px scarlet
 * outline ring and switches the abbr to scarlet; inactive renders no ring
 * and uses textDefault for the abbr.
 *
 * Renders as <button type="button"> with aria-label "Scroll to {parkName}
 * in overview table" so the click target is keyboard-reachable and screen
 * readers describe the action, not just the park.
 *
 * Naming intentionally avoids legacy `park-thumbnail.tsx` (Stage 1 file,
 * left in tree as orphaned dead code per the Stage 3c decision).
 */

import { colors, typography } from "../../design/tokens";

export type ParkMiniThumbProps = {
  /** Park id, e.g. "COL". Used by the parent to wire the click handler. */
  parkId: string;
  /** Full park name for the aria-label. */
  parkName: string;
  /** 3-letter abbreviation rendered below the heatmap. */
  abbr: string;
  /** 6×6 normalized [0,1] grid driving the heatmap cell colors. */
  grid: number[][];
  /** Active state — wraps the tile in a scarlet outline ring. */
  isActive: boolean;
  /** Click handler — parent owns the scroll-into-view side effect. */
  onSelect: (parkId: string) => void;
};

// ── Heatmap geometry ─────────────────────────────────────────────────────────

const SVG_SIZE = 80;
const GRID_N = 6;
const CELL = SVG_SIZE / GRID_N; // 13.33

/**
 * Map [0,1] → heatWarm 4-stop ramp. Same piecewise stops as
 * <PitchLocationHeatmap> so the two heatmaps read identically.
 */
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

export function ParkMiniThumb({
  parkId,
  parkName,
  abbr,
  grid,
  isActive,
  onSelect,
}: ParkMiniThumbProps) {
  return (
    <button
      type="button"
      onClick={() => onSelect(parkId)}
      aria-label={`Scroll to ${parkName} in overview table`}
      aria-pressed={isActive}
      className="park-mini-thumb"
      style={{
        // Reset native <button> chrome
        appearance: "none",
        background: colors.bgSheet,
        border: `1px solid ${colors.bgEmphasis}`,
        outline: isActive ? `2px solid ${colors.scarlet}` : "none",
        outlineOffset: isActive ? -1 : 0,
        cursor: "pointer",
        padding: 0,
        margin: 0,
        // Layout
        width: 88,
        height: 104,
        display: "flex",
        flexDirection: "column",
        alignItems: "center",
        gap: 4,
        flexShrink: 0,
        scrollSnapAlign: "start",
        borderRadius: 2,
      }}
    >
      <svg
        role="img"
        aria-label={`${parkName} batted-ball density preview`}
        width={SVG_SIZE}
        height={SVG_SIZE}
        viewBox={`0 0 ${SVG_SIZE} ${SVG_SIZE}`}
        style={{ display: "block", marginTop: 4 }}
      >
        <title>{`${parkName} batted-ball density preview`}</title>
        <rect
          x={0}
          y={0}
          width={SVG_SIZE}
          height={SVG_SIZE}
          fill={colors.bgSheet}
        />
        {grid.flatMap((row, r) =>
          row.map((v, c) => (
            <rect
              key={`${r}-${c}`}
              x={c * CELL}
              y={r * CELL}
              width={CELL}
              height={CELL}
              fill={rampColor(v)}
              fillOpacity={0.92}
            />
          )),
        )}
      </svg>
      <span
        style={{
          fontFamily: typography.fonts.display,
          fontSize: 12,
          fontWeight: typography.weights.bold,
          textTransform: "uppercase",
          letterSpacing: "0.06em",
          color: isActive ? colors.scarlet : colors.textDefault,
          lineHeight: 1,
        }}
      >
        {abbr}
      </span>
    </button>
  );
}
