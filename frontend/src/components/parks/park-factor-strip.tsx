/**
 * <ParkFactorStrip> — vertical stack of 5 spotlight factor blocks.
 *
 * Each numeric block (HR / BABIP / 3B / OPS) renders:
 *   - Saira Condensed 12px uppercase eyebrow ("HR FACTOR")
 *   - Saira Condensed Heavy 48px figure in textStrong (the raw factor)
 *   - IBM Plex Mono 12px caption in textMuted ("+18% vs lg")
 *   - Background color via cellColor(value, FACTOR_METRIC) so distance from
 *     1.00 tints the block (the block bg carries the conditional signal —
 *     caption color stays muted because color is layered onto the block,
 *     not the text)
 *
 * The WIND block is categorical: it renders a Saira display 32px string
 * ("LF → RF") instead of a numeric figure and gets no cellColor tint.
 *
 * Blocks are stacked vertically with 12 px gap, 12 px×16 px padding inside
 * each. Border 1 px bgEmphasis.
 */

import { cellColor } from "../../design/cellColor";
import { radii, colors, typography } from "../../design/broadcast";
import {
  FACTOR_METRIC,
  type ParkSpotlightFactor,
  type ParkSpotlightWindBlock,
} from "../../data/parks-fixtures";

export type ParkFactorStripProps = {
  factors: (ParkSpotlightFactor | ParkSpotlightWindBlock)[];
};

function NumericBlock({ block }: { block: ParkSpotlightFactor }) {
  const bg = cellColor(block.value, FACTOR_METRIC);
  return (
    <div
      style={{
        backgroundColor: bg,
        border: `1px solid ${colors.rule}`,
        borderRadius: radii.sm,
        padding: "12px 16px",
        display: "flex",
        flexDirection: "column",
        gap: 2,
      }}
    >
      <span
        style={{
          fontFamily: typography.fonts.display,
          fontSize: 12,
          fontWeight: typography.weights.bold,
          textTransform: "uppercase",
          letterSpacing: "0.06em",
          color: colors.ink,
        }}
      >
        {block.label}
      </span>
      <span
        style={{
          fontFamily: typography.fonts.display,
          fontSize: 48,
          fontWeight: typography.weights.heavy,
          lineHeight: 1,
          color: colors.ink,
          fontFeatureSettings: '"tnum" 1',
        }}
      >
        {block.value.toFixed(2)}
      </span>
      <span
        style={{
          fontFamily: typography.fonts.mono,
          fontSize: 12,
          color: colors.textMuted,
          fontFeatureSettings: '"tnum" 1',
        }}
      >
        {block.caption}
      </span>
    </div>
  );
}

function WindBlock({ block }: { block: ParkSpotlightWindBlock }) {
  // Categorical: no cellColor tint. Background is bgSheet so the block
  // stands apart from the tinted numeric blocks above and below it.
  return (
    <div
      style={{
        backgroundColor: colors.panel,
        border: `1px solid ${colors.rule}`,
        borderRadius: radii.sm,
        padding: "12px 16px",
        display: "flex",
        flexDirection: "column",
        gap: 2,
      }}
    >
      <span
        style={{
          fontFamily: typography.fonts.display,
          fontSize: 12,
          fontWeight: typography.weights.bold,
          textTransform: "uppercase",
          letterSpacing: "0.06em",
          color: colors.ink,
        }}
      >
        {block.label}
      </span>
      <span
        style={{
          fontFamily: typography.fonts.display,
          fontSize: 32,
          fontWeight: typography.weights.heavy,
          lineHeight: 1.1,
          color: colors.ink,
          textTransform: "uppercase",
          letterSpacing: "0.02em",
        }}
      >
        {block.display}
      </span>
      <span
        style={{
          fontFamily: typography.fonts.mono,
          fontSize: 12,
          color: colors.textMuted,
        }}
      >
        {block.caption}
      </span>
    </div>
  );
}

export function ParkFactorStrip({ factors }: ParkFactorStripProps) {
  return (
    <div
      style={{
        display: "flex",
        flexDirection: "column",
        gap: 12,
      }}
    >
      {factors.map((f) =>
        f.key === "WIND" ? (
          <WindBlock key={f.key} block={f as ParkSpotlightWindBlock} />
        ) : (
          <NumericBlock key={f.key} block={f as ParkSpotlightFactor} />
        ),
      )}
    </div>
  );
}
