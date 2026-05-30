/**
 * <GameStateStrip> — the navy lower-third bar that shows the live game state
 * at a glance.
 *
 * Pattern: the `<ModelFleetRibbon>` chrome (navy bar, equally-weighted cells
 * separated by 1px navyDeep dividers), but the cells are presentational
 * data cells rather than navigation chips. No hover, no link semantics.
 *
 * Each cell shows a Saira-uppercase tracked label on top and an IBM Plex Mono
 * value on the bottom — same visual rhythm as the ModelFleetRibbon chip head
 * + detail line.
 *
 * The optional `emphasis` on a cell lets one value (typically AGREEMENT) wear
 * a small scarlet pill so the live model state reads at a glance.
 */

import type { GameStateCell } from "../../data/games-fixtures";
import { radii, colors, typography } from "../../design/tokens";

export type GameStateStripProps = {
  cells: GameStateCell[];
};

function cellEmphasisStyle(
  emphasis: GameStateCell["emphasis"],
): React.CSSProperties {
  // Base: no decoration — just the mono value text on the navy bar.
  if (!emphasis || emphasis === "default") return {};
  // Scarlet fill: solid scarlet pill, cream text on top.
  if (emphasis === "scarlet-fill") {
    return {
      backgroundColor: colors.scarlet,
      color: colors.textOnNavy,
      padding: "2px 8px",
      border: `1px solid ${colors.scarlet}`,
      borderRadius: radii.sm,
    };
  }
  // Scarlet outline only: hollow pill, scarlet text + border.
  if (emphasis === "scarlet-outline") {
    return {
      color: colors.scarlet,
      padding: "2px 8px",
      border: `1px solid ${colors.scarlet}`,
      borderRadius: radii.sm,
    };
  }
  // Silver outline: hollow pill, silver text + border.
  return {
    color: colors.silver,
    padding: "2px 8px",
    border: `1px solid ${colors.silver}`,
    borderRadius: radii.sm,
  };
}

export function GameStateStrip({ cells }: GameStateStripProps) {
  return (
    <div
      className="live-game__state-strip"
      role="region"
      aria-label="Live game state"
      style={{
        backgroundColor: colors.navy,
        display: "grid",
        gridTemplateColumns: `repeat(${cells.length}, 1fr)`,
        columnGap: 1,
        borderRadius: radii.sm,
      }}
    >
      {cells.map((cell, i) => (
        <div
          key={cell.label + i}
          style={{
            display: "flex",
            flexDirection: "column",
            justifyContent: "center",
            gap: 4,
            padding: "10px 14px",
            backgroundColor: colors.navy,
            borderRight:
              i < cells.length - 1 ? `1px solid ${colors.navyDeep}` : "none",
            minHeight: 48,
          }}
        >
          <span
            style={{
              fontFamily: typography.fonts.display,
              fontSize: 11,
              fontWeight: typography.weights.bold,
              textTransform: "uppercase",
              letterSpacing: "0.08em",
              color: colors.silver,
            }}
          >
            {cell.label}
          </span>
          <span
            style={{
              fontFamily: typography.fonts.mono,
              fontSize: 14,
              fontWeight: typography.weights.bold,
              color: colors.textOnNavy,
              letterSpacing: "0.02em",
              fontFeatureSettings: '"tnum" 1',
              alignSelf: "flex-start",
              ...cellEmphasisStyle(cell.emphasis),
            }}
          >
            {cell.value}
          </span>
        </div>
      ))}
    </div>
  );
}
