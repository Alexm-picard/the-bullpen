/**
 * <HeroEyebrow> — the small uppercase label that sits above a hero h1.
 *
 * Renders as `JetBrains Mono`, tracked out, accent-colored. The "spine motif" of the
 * tech-product redesign — every major section uses one to signal what kind of content
 * follows (a prediction, a methodology step, a destination).
 *
 * Presentational only; no behavior. Used on /home for now; available to /about etc.
 * once the redesign sequence reaches them.
 */
import { Text } from "@mantine/core";

import { colors, typography } from "../../design/tokens";

export type HeroEyebrowProps = {
  children: React.ReactNode;
  /** Optional override; defaults to the brand accent. */
  color?: string;
};

export function HeroEyebrow({
  children,
  color = colors.scarlet,
}: HeroEyebrowProps) {
  return (
    <Text
      component="span"
      style={{
        fontFamily: typography.fonts.mono,
        fontSize: typography.scale[0], // 12
        fontWeight: typography.weights.semibold,
        letterSpacing: "0.12em",
        textTransform: "uppercase",
        color,
        display: "inline-block",
      }}
    >
      {children}
    </Text>
  );
}
