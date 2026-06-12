/**
 * <LowerThird> - the broadcast identity's section header (decision [160]):
 * a navy bar with a slanted trailing edge and a gold leading tick, replacing
 * the flat <SectionLabel> bars in migrated screens.
 *
 * Renders a real heading element (default h2) so document outline survives the
 * redesign; one h1 per page stays the rule.
 */

import { colors, cuts, typography } from "../../design/broadcast";
import { teamColor } from "../../design/teamColors";

export type LowerThirdProps = {
  children: React.ReactNode;
  /** Right-aligned mono metadata inside the bar, e.g. "LAST 24H". */
  meta?: string;
  /** Edge-tick accent: "gold" (default) or a team abbreviation. */
  accent?: string;
  /** Heading level for the title text. */
  as?: "h1" | "h2" | "h3";
  id?: string;
};

export function LowerThird({
  children,
  meta,
  accent = "gold",
  as: Heading = "h2",
  id,
}: LowerThirdProps) {
  const tick = accent === "gold" ? colors.gold : teamColor(accent);
  return (
    <div
      style={{
        display: "inline-flex",
        alignItems: "stretch",
        minWidth: 260,
        maxWidth: "100%",
        backgroundColor: colors.chrome,
        clipPath: cuts.lowerThirdEdge,
      }}
    >
      <span
        aria-hidden="true"
        style={{ width: 6, backgroundColor: tick, flex: "0 0 auto" }}
      />
      <Heading
        id={id}
        style={{
          margin: 0,
          padding: "7px 26px 7px 12px",
          fontFamily: typography.fonts.display,
          fontStyle: "italic",
          fontWeight: typography.weights.bold,
          fontSize: 17,
          lineHeight: typography.lineHeights.display,
          letterSpacing: "0.06em",
          textTransform: "uppercase",
          color: colors.textOnChrome,
        }}
      >
        {children}
      </Heading>
      {meta && (
        <span
          style={{
            display: "inline-flex",
            alignItems: "center",
            marginLeft: "auto",
            padding: "0 30px 0 14px",
            fontFamily: typography.fonts.mono,
            fontSize: 11,
            fontFeatureSettings: '"tnum" 1',
            letterSpacing: "0.04em",
            color: colors.textOnChromeMuted,
          }}
        >
          {meta}
        </span>
      )}
    </div>
  );
}
