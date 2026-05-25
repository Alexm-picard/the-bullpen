/**
 * StadiumSvg (leaf 4c.1) — renders one stadium field outline as an inline SVG,
 * with an optional `children` overlay layer for the 4c.2 heatmap / 4c.3 launch
 * trajectory polyline.
 *
 * The actual line drawing lives in `/public/parks/<park_id>.svg` as a
 * `<symbol id="field">`. We reference it via an `<svg><use href=…#field />`
 * because:
 *   - `<use href>` keeps the geometry as one shared resource cached by the
 *     browser — every example card pointing at the same park hits the same SVG.
 *   - Overlays composed in the consumer DOM share the same coordinate space
 *     (viewBox 0 0 500 500, home plate at (250, 480), 1 unit = 1 foot).
 *
 * `color` flows through CSS — the SVG's `stroke="currentColor"` means the
 * line drawing inherits whatever `color` the wrapping container resolves.
 * Default is `colors.textDefault`.
 */
import { Box } from "@mantine/core";
import type { CSSProperties, ReactNode } from "react";

import { colors } from "../../design/tokens";

export type StadiumSvgProps = {
  parkId: string;
  /** Pixel-density size of the rendered SVG (square). Defaults to 320. */
  size?: number;
  /** Optional ARIA label. Defaults to "{parkId} field". */
  ariaLabel?: string;
  /** Overlay layer drawn in the same coordinate space. */
  children?: ReactNode;
  /** Stroke color for the field outline. Defaults to tokens.colors.textDefault. */
  color?: string;
  style?: CSSProperties;
};

export function StadiumSvg({
  parkId,
  size = 320,
  ariaLabel,
  children,
  color,
  style,
}: StadiumSvgProps) {
  return (
    <Box
      style={{
        width: size,
        height: size,
        color: color ?? colors.textDefault,
        ...(style ?? {}),
      }}
    >
      <svg
        width={size}
        height={size}
        viewBox="0 0 500 500"
        role="img"
        aria-label={ariaLabel ?? `${parkId} field`}
      >
        <use href={`/parks/${parkId}.svg#field`} />
        {children ? <g className="bullpen-overlay">{children}</g> : null}
      </svg>
    </Box>
  );
}
