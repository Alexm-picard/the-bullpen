/**
 * <BroadcastFleetStrip> - the model-fleet strip on the broadcast identity
 * (redesign PR-4, decision [160]). Replaces <ModelFleetRibbon> on /home: a
 * chrome strip of clickable model chips - LIVE chips carry a static gold
 * dot (the Scorebug owns the one pulsing on-air dot), SHADOW chips a steel
 * tag - linking to /ops. The chip background and its gated hover live in
 * `.broadcast-strip` (broadcast.css, imported once from index.css).
 *
 * Pure presentation; the page owns the registry wiring + the honest showcase
 * caption when the backend is unreachable.
 */

import { Link } from "react-router";

import type { ModelChip } from "../../data/home-fixtures";
import { colors, radii, typography } from "../../design/broadcast";

export type BroadcastFleetStripProps = {
  chips: ModelChip[];
};

export function BroadcastFleetStrip({ chips }: BroadcastFleetStripProps) {
  return (
    <nav
      aria-label="Model fleet"
      style={{
        display: "flex",
        alignItems: "stretch",
        gap: 1,
        overflowX: "auto",
        scrollSnapType: "x proximity",
        // Right-edge fade: a cut-off chip reads as "scroll for more".
        maskImage:
          "linear-gradient(to right, black calc(100% - 28px), transparent)",
        WebkitMaskImage:
          "linear-gradient(to right, black calc(100% - 28px), transparent)",
        backgroundColor: colors.chrome,
        border: `1px solid ${colors.chromeEdge}`,
        padding: 1,
      }}
    >
      {chips.map((chip) => (
        <Link
          key={chip.id}
          to={chip.href}
          className="broadcast-strip bp-pressable"
          style={{
            display: "inline-flex",
            alignItems: "center",
            gap: 8,
            flex: "0 0 auto",
            scrollSnapAlign: "start",
            padding: "8px 14px",
            textDecoration: "none",
          }}
        >
          {chip.state === "LIVE" && (
            <span
              data-fleet-live-dot="true"
              aria-hidden="true"
              style={{
                width: 7,
                height: 7,
                borderRadius: radii.pill,
                backgroundColor: colors.gold,
                flex: "0 0 auto",
              }}
            />
          )}
          <span
            style={{
              fontFamily: typography.fonts.mono,
              fontSize: 12,
              whiteSpace: "nowrap",
              color: colors.textOnChrome,
            }}
          >
            {chip.label}
          </span>
          <span
            style={{
              fontFamily: typography.fonts.mono,
              fontSize: 11,
              color: colors.textOnChromeMuted,
            }}
          >
            {chip.detail}
          </span>
          <span
            style={{
              fontFamily: typography.fonts.display,
              fontStyle: "italic",
              fontWeight: typography.weights.bold,
              fontSize: 11,
              letterSpacing: "0.08em",
              textTransform: "uppercase",
              color: chip.state === "LIVE" ? colors.gold : colors.steel,
            }}
          >
            {chip.state}
          </span>
        </Link>
      ))}
    </nav>
  );
}
