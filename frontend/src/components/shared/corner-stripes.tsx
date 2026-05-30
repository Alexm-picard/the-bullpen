/**
 * <CornerStripes> — diagonal scarlet-stripe corner motif.
 *
 * 120×120 SVG meant to be absolutely positioned in the top-right of a
 * report-sheet shell. Used by both the Matchup Report (/players/:id) and
 * the Tonight's Slate cover-sheet (/home) to anchor the broadcast-graphics
 * identity. Decorative — aria-hidden, no semantic role.
 *
 * Positioning is the caller's responsibility via a CSS class — this file
 * stays portable. The Matchup Report uses the `.matchup-report__corner`
 * class from `matchup.css`; the home page uses `.home-cover__corner`
 * from `home.css`. Both apply the same `position: absolute; top: 0;
 * right: 0;` plus an `opacity: 0.18` tint so the motif never fights with
 * page content.
 */

import { colors } from "../../design/tokens";

export type CornerStripesProps = {
  /** CSS class controlling positioning + opacity. */
  className: string;
};

export function CornerStripes({ className }: CornerStripesProps) {
  return (
    <svg
      className={className}
      role="presentation"
      aria-label=""
      aria-hidden="true"
      viewBox="0 0 120 120"
    >
      <defs>
        <pattern
          id="scarlet-stripes"
          patternUnits="userSpaceOnUse"
          width="14"
          height="14"
          patternTransform="rotate(45)"
        >
          <rect width="14" height="14" fill={colors.bgBase} />
          <rect x="0" width="7" height="14" fill={colors.scarlet} />
        </pattern>
        <clipPath id="corner-clip">
          <polygon points="120,0 120,120 0,0" />
        </clipPath>
      </defs>
      <g clipPath="url(#corner-clip)">
        <rect
          x="0"
          y="0"
          width="120"
          height="120"
          fill="url(#scarlet-stripes)"
        />
      </g>
    </svg>
  );
}
