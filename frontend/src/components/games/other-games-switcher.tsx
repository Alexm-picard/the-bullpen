/**
 * <OtherGamesSwitcher> — horizontal scroll row of small chips, one per other
 * live game tonight.
 *
 * Pattern: borrows from `<ParkSwitcherStrip>` (horizontal scroll with
 * scroll-snap) and from `<ModelFleetRibbon>` (navy-headed chip with
 * mono detail line). Each chip is a React Router <Link> so clicks navigate
 * to /games/{id} (per-game-detail leaf, out of scope for this stage but the
 * route already exists in App.tsx).
 *
 * Chip layout: navy 8px header strip with team abbreviations (e.g. "LAA @
 * HOU"), then a cream body with the game state (e.g. "TOP 8TH · 2–1") in
 * IBM Plex Mono.
 */

import { Link } from "react-router-dom";

import type { OtherGameChip } from "../../data/games-fixtures";
import { radii, colors, typography } from "../../design/tokens";

export type OtherGamesSwitcherProps = {
  chips: OtherGameChip[];
};

export function OtherGamesSwitcher({ chips }: OtherGamesSwitcherProps) {
  if (chips.length === 0) {
    return (
      <div
        style={{
          backgroundColor: colors.bgSheet,
          border: `1px solid ${colors.bgEmphasis}`,
          borderRadius: radii.sm,
          padding: 16,
          fontFamily: typography.fonts.body,
          fontSize: 13,
          color: colors.textMuted,
          textAlign: "center",
        }}
      >
        No other live games right now.
      </div>
    );
  }

  return (
    <nav
      aria-label="Other live games tonight"
      style={{
        backgroundColor: colors.bgSheet,
        border: `1px solid ${colors.bgEmphasis}`,
        borderRadius: radii.sm,
        padding: 8,
        overflowX: "auto",
        scrollSnapType: "x mandatory",
        WebkitOverflowScrolling: "touch",
      }}
    >
      <div style={{ display: "flex", gap: 8 }}>
        {chips.map((chip) => (
          <Link
            key={chip.id}
            to={chip.href}
            className="other-games__chip"
            style={{
              flex: "0 0 auto",
              minWidth: 160,
              border: `1px solid ${colors.bgEmphasis}`,
              borderRadius: radii.sm,
              textDecoration: "none",
              color: colors.textStrong,
              backgroundColor: colors.bgSheet,
              scrollSnapAlign: "start",
              display: "flex",
              flexDirection: "column",
            }}
          >
            <span
              style={{
                backgroundColor: colors.navy,
                color: colors.textOnNavy,
                fontFamily: typography.fonts.display,
                fontSize: 12,
                fontWeight: typography.weights.bold,
                textTransform: "uppercase",
                letterSpacing: "0.06em",
                padding: "6px 10px",
              }}
            >
              {chip.away} @ {chip.home}
            </span>
            <span
              style={{
                fontFamily: typography.fonts.mono,
                fontSize: 12,
                color: colors.textDefault,
                letterSpacing: "0.02em",
                padding: "8px 10px",
                fontFeatureSettings: '"tnum" 1',
              }}
            >
              {chip.state}
            </span>
          </Link>
        ))}
      </div>
    </nav>
  );
}
