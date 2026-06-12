/**
 * <AboutFactsRibbon> — the navy broadcast strip below the masthead on /about.
 *
 * Pattern: lifts the chrome of <ModelFleetRibbon> from /home (same navy bg,
 * same column-gap-1px-divider trick, same bordered-chip composition) but is
 * intentionally NOT clickable — display-only. Four artifact-count cells.
 *
 * Per locked pick F1: `133 DECISIONS · 7 ADRs · 3 MODELS · 4 CV FOLDS`.
 *
 * Layout per cell:
 *   - Saira Condensed eyebrow (top): small uppercase qualifier (e.g. "Locked")
 *   - IBM Plex Mono figure (middle): the big number, large heavy
 *   - Saira Condensed unit (bottom): uppercase plural unit
 *
 * Cells separated by 1px navyDeep vertical rules (same trick as the fleet
 * ribbon — column-gap 1px with same-color cell bg, so the navy bar reads as
 * one bar with internal divisions).
 *
 * Below 600px the 4-cell row stacks to a 2×2 grid via about.css media query.
 */

import type { FactCell } from "../../data/about-fixtures";
import { radii, colors, typography } from "../../design/broadcast";

export type AboutFactsRibbonProps = {
  cells: FactCell[];
};

export function AboutFactsRibbon({ cells }: AboutFactsRibbonProps) {
  return (
    <div
      className="about-facts-ribbon"
      role="group"
      aria-label="Project artifact counts"
      style={{
        backgroundColor: colors.chrome,
        display: "grid",
        gridTemplateColumns: `repeat(${cells.length}, 1fr)`,
        columnGap: 1,
        borderRadius: radii.sm,
      }}
    >
      {cells.map((cell, i) => (
        <div
          key={`${cell.unit}-${i}`}
          style={{
            display: "flex",
            flexDirection: "column",
            alignItems: "center",
            justifyContent: "center",
            gap: 4,
            padding: "16px 14px",
            backgroundColor: colors.chrome,
            borderRight:
              i < cells.length - 1 ? `1px solid ${colors.chromeDeep}` : "none",
            minHeight: 88,
          }}
        >
          <span
            style={{
              fontFamily: typography.fonts.display,
              fontSize: 11,
              fontWeight: typography.weights.semibold,
              textTransform: "uppercase",
              letterSpacing: "0.08em",
              color: colors.textOnChromeMuted,
            }}
          >
            {cell.eyebrow}
          </span>
          <span
            style={{
              fontFamily: typography.fonts.mono,
              fontSize: typography.scale[5], // 32
              fontWeight: typography.weights.bold,
              color: colors.textOnChrome,
              lineHeight: 1.05,
              letterSpacing: "0.01em",
            }}
          >
            {cell.figure}
          </span>
          <span
            style={{
              fontFamily: typography.fonts.display,
              fontSize: 12,
              fontWeight: typography.weights.bold,
              textTransform: "uppercase",
              letterSpacing: "0.08em",
              color: colors.textOnChrome,
            }}
          >
            {cell.unit}
          </span>
        </div>
      ))}
    </div>
  );
}
