/**
 * <CoverSheetFooter> — the navy strip at the bottom of the cover-sheet shell.
 *
 * Bookends the ModelFleetRibbon at the top of the page: same visual weight,
 * same lower-third navy chrome, so the report reads as a single bordered
 * sheet between two broadcast bars.
 *
 * Three slots, flex-justified: left = identity, center = build meta, right =
 * methodology link. On narrow viewports (< 600px) the strip wraps vertically
 * via `.home-cover__footer` in home.css.
 */

import { Link } from "react-router-dom";

import { radii, colors, typography } from "../../design/tokens";

export type CoverSheetFooterProps = {
  buildSha: string;
  buildDate: string;
};

export function CoverSheetFooter({
  buildSha,
  buildDate,
}: CoverSheetFooterProps) {
  const itemStyle: React.CSSProperties = {
    fontFamily: typography.fonts.mono,
    fontSize: 11,
    color: colors.silver,
    letterSpacing: "0.06em",
    textTransform: "uppercase",
  };

  return (
    <footer
      className="home-cover__footer"
      style={{
        backgroundColor: colors.navy,
        display: "flex",
        alignItems: "center",
        justifyContent: "space-between",
        padding: "10px 16px",
        gap: 16,
        borderRadius: radii.sm,
        minHeight: 40,
        flexWrap: "wrap",
      }}
    >
      <span style={itemStyle}>The Bullpen · Advance Scouting</span>
      <span style={itemStyle}>
        SHA {buildSha} · BUILD {buildDate}
      </span>
      <Link
        to="/about"
        style={{
          ...itemStyle,
          textDecoration: "none",
          color: colors.silver,
        }}
      >
        Methodology &rarr;
      </Link>
    </footer>
  );
}
