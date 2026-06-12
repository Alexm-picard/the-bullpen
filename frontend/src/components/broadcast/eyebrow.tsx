/**
 * <Eyebrow> - the broadcast identity's small uppercase kicker above a hero h1
 * (decision [160]; replaces the paper-era <HeroEyebrow>). Mono, tracked wide,
 * goldInk. Presentational only.
 */

import { colors, typography } from "../../design/broadcast";

export type EyebrowProps = {
  children: React.ReactNode;
};

export function Eyebrow({ children }: EyebrowProps) {
  return (
    <span
      style={{
        display: "inline-block",
        fontFamily: typography.fonts.mono,
        fontSize: 12,
        fontWeight: typography.weights.semibold,
        letterSpacing: "0.12em",
        textTransform: "uppercase",
        color: colors.goldInk,
      }}
    >
      {children}
    </span>
  );
}
