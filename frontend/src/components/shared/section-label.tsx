/**
 * <SectionLabel> — the small uppercase Saira label that prefixes a section
 * inside a report-sheet shell.
 *
 * Pattern lifted from the Matchup Report (originally a local helper in
 * `players-page.tsx`). Promoted to shared because both the Matchup Report
 * and the Tonight's Slate cover-sheet use it to title their inner sections
 * ("Recent Predictions", "Calibration", "Tonight's Matchups", …).
 *
 * Visual: Saira Condensed bold 13px, uppercase, tracked +0.08em, with a
 * 1-px bgEmphasis bottom border so the section reads as a printed-packet
 * sub-head rather than a SaaS card title.
 */

import { colors, typography } from "../../design/tokens";

export type SectionLabelProps = {
  children: React.ReactNode;
};

export function SectionLabel({ children }: SectionLabelProps) {
  return (
    <div
      style={{
        fontFamily: typography.fonts.display,
        fontSize: 13,
        fontWeight: typography.weights.bold,
        textTransform: "uppercase",
        letterSpacing: "0.08em",
        color: colors.textStrong,
        marginBottom: 8,
        paddingBottom: 4,
        borderBottom: `1px solid ${colors.bgEmphasis}`,
      }}
    >
      {children}
    </div>
  );
}
