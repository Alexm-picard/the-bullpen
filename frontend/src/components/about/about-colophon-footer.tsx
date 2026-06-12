/**
 * <AboutColophonFooter> — the navy strip at the bottom of the /about cover
 * sheet.
 *
 * Bookends the AboutFactsRibbon at the top: same navy lower-third chrome,
 * same broadcast vocabulary, so the report reads as a single bordered sheet
 * between two navy bars. Mirrors the /home CoverSheetFooter pattern, but
 * the right-slot is a plain-text repo placeholder (NOT an anchor — per the
 * locked pick R2 the repo placeholder is plain text only, not wired).
 *
 * Three slots, flex-justified:
 *   - left   = COLOPHON · SHA … · BUILD …
 *   - right  = github.com/<placeholder>/thebullpen →   (plain text)
 *
 * Below 600px, stacks vertically via about.css (.about-cover__footer).
 */

import { radii, colors, typography } from "../../design/broadcast";

export type AboutColophonFooterProps = {
  buildSha: string;
  buildDate: string;
  repoPlaceholder: string;
};

export function AboutColophonFooter({
  buildSha,
  buildDate,
  repoPlaceholder,
}: AboutColophonFooterProps) {
  const itemStyle: React.CSSProperties = {
    fontFamily: typography.fonts.mono,
    fontSize: 12,
    color: colors.textOnChrome,
    letterSpacing: "0.06em",
    textTransform: "uppercase",
  };

  return (
    <footer
      className="about-cover__footer"
      style={{
        backgroundColor: colors.chrome,
        display: "flex",
        alignItems: "center",
        justifyContent: "space-between",
        padding: "16px 16px",
        gap: 16,
        borderRadius: radii.sm,
        flexWrap: "wrap",
      }}
    >
      <span style={itemStyle}>
        COLOPHON · SHA {buildSha} · BUILD {buildDate}
      </span>
      <span style={itemStyle}>
        {repoPlaceholder} <span aria-hidden="true">→</span>
      </span>
    </footer>
  );
}
