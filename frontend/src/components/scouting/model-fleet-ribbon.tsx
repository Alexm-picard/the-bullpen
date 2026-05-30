/**
 * <ModelFleetRibbon> — the navy broadcast strip below the masthead that shows
 * the model fleet at a glance.
 *
 * Pattern: lower-third navy bar (same vocabulary as KeyNotes header and
 * PlayerProfileCard header), but full-width and split into N equally-weighted
 * link chips. Each chip is a React Router <Link> so clicks route into /ops.
 *
 * Layout: navy bg, fixed 48px height row of chips. Chip internal layout —
 * Saira display label on top, IBM Plex Mono detail line below, state badge
 * pinned right. Hover: subtle bgEmphasis overlay (chrome-on-chrome). Focus:
 * 2px scarlet outline, offset 2px. Keyboard accessible via the underlying
 * <Link> element.
 *
 * Why a new primitive: this is the first lower-third with interactive chips;
 * future Game page may reuse. KeyNotes' header is decorative; this one carries
 * navigation.
 */

import { Link } from "react-router-dom";

import type { ModelChip, ModelChipState } from "../../data/home-fixtures";
import { radii, colors, typography } from "../../design/tokens";

export type ModelFleetRibbonProps = {
  chips: ModelChip[];
};

function stateColor(state: ModelChipState): string {
  // LIVE = scarlet (team accent for active state), SHADOW = silver (broadcast
  // chrome second-tier), OK = good3 (the cellColor good ramp's strong green).
  if (state === "LIVE") return colors.scarlet;
  if (state === "SHADOW") return colors.silver;
  return colors.condFormat.good3;
}

export function ModelFleetRibbon({ chips }: ModelFleetRibbonProps) {
  return (
    <nav
      className="home-fleet-ribbon"
      aria-label="Model fleet"
      style={{
        backgroundColor: colors.navy,
        display: "grid",
        gridTemplateColumns: `repeat(${chips.length}, 1fr)`,
        // 1-px column gap using a divider rather than CSS gap so the navy bar
        // reads as a single bar with internal divisions (no visible cream gap).
        columnGap: 1,
        borderRadius: radii.sm,
      }}
    >
      {chips.map((chip, i) => (
        <Link
          key={chip.id}
          to={chip.href}
          className="home-fleet-ribbon__chip"
          style={{
            display: "flex",
            alignItems: "center",
            justifyContent: "space-between",
            padding: "10px 14px",
            textDecoration: "none",
            color: colors.textOnNavy,
            backgroundColor: colors.navy,
            // Right divider on all but last chip — same pattern as printed
            // lower-third bars on broadcast scoreboards.
            borderRight:
              i < chips.length - 1 ? `1px solid ${colors.navyDeep}` : "none",
            minHeight: 48,
          }}
        >
          <div style={{ display: "flex", flexDirection: "column", gap: 2 }}>
            <span
              style={{
                fontFamily: typography.fonts.display,
                fontSize: 13,
                fontWeight: typography.weights.bold,
                textTransform: "uppercase",
                letterSpacing: "0.06em",
                color: colors.textOnNavy,
              }}
            >
              {chip.label}
            </span>
            <span
              style={{
                fontFamily: typography.fonts.mono,
                fontSize: 11,
                color: colors.silver,
                letterSpacing: "0.02em",
              }}
            >
              {chip.detail}
            </span>
          </div>
          <span
            data-state={chip.state}
            style={{
              fontFamily: typography.fonts.mono,
              fontSize: 11,
              fontWeight: typography.weights.bold,
              letterSpacing: "0.06em",
              textTransform: "uppercase",
              color: stateColor(chip.state),
              padding: "2px 6px",
              border: `1px solid ${stateColor(chip.state)}`,
              borderRadius: radii.sm,
              whiteSpace: "nowrap",
            }}
          >
            {chip.state}
          </span>
        </Link>
      ))}
    </nav>
  );
}
