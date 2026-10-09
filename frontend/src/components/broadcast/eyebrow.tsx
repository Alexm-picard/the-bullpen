/**
 * <Eyebrow> - the broadcast identity's small uppercase kicker above a hero h1
 * (decision [160]; replaces the paper-era <HeroEyebrow>). Mono, tracked wide
 * (typography.tracking.eyebrow), goldInk by default. Presentational only.
 *
 * `as="p"` renders a block kicker with the 4px gap the page mastheads use
 * above their h1; `tone="muted"` drops the gold for a secondary kicker.
 */

import { colors, typography } from "../../design/broadcast";

export type EyebrowProps = {
  children: React.ReactNode;
  /** Gold ink (default) or muted text. */
  tone?: "gold" | "muted";
  /** Element to render; a `p` is a block kicker above a heading. */
  as?: "span" | "p" | "h3";
};

export function Eyebrow({
  children,
  tone = "gold",
  as = "span",
}: EyebrowProps) {
  const Tag = as;
  return (
    <Tag
      style={{
        display: as === "span" ? "inline-block" : "block",
        margin: as === "span" ? undefined : "0 0 4px",
        fontFamily: typography.fonts.mono,
        fontSize: 12,
        fontWeight: typography.weights.semibold,
        letterSpacing: typography.tracking.eyebrow,
        textTransform: "uppercase",
        color: tone === "muted" ? colors.textMuted : colors.goldInk,
      }}
    >
      {children}
    </Tag>
  );
}
