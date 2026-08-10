import type { CSSProperties, ReactNode } from "react";

import { colors, cuts, typography } from "../../design/broadcast";

type Variant = "gold" | "dim" | "live";

const BASE: CSSProperties = {
  display: "inline-block",
  fontFamily: typography.fonts.display,
  fontWeight: typography.weights.semibold,
  fontSize: 12,
  textTransform: "uppercase",
  letterSpacing: "0.08em",
  padding: "3px 12px 3px 10px",
  clipPath: cuts.chip,
  lineHeight: 1.4,
};

const VARIANTS: Record<Variant, CSSProperties> = {
  gold: { backgroundColor: colors.gold, color: colors.field },
  dim: { backgroundColor: colors.panelEdge, color: colors.text },
  live: { backgroundColor: colors.live, color: colors.field },
};

export function BroadcastChip({
  variant = "gold",
  children,
  style,
}: {
  variant?: Variant;
  children: ReactNode;
  style?: CSSProperties;
}) {
  return (
    <span style={{ ...BASE, ...VARIANTS[variant], ...style }}>{children}</span>
  );
}
