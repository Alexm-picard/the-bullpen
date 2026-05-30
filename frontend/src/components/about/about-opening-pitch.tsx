/**
 * <AboutOpeningPitch> — three short prose paragraphs that open the colophon.
 *
 * IBM Plex Sans 16px × line-height ~1.55 × max-width ~62ch column. The
 * 62ch constraint is the editorial measure for the entire long-form
 * report; CSS lives in about.css (.about-prose) so it can be shared across
 * the prose-heavy sections (OPENING PITCH, MODEL FLEET context paragraphs,
 * INTENTIONALLY NOT HERE framing, ROADMAP HONESTY).
 *
 * Pure presentation; accepts an array of strings from OPENING_PITCH_PARAS.
 */

import { colors, typography } from "../../design/tokens";

export type AboutOpeningPitchProps = {
  paragraphs: string[];
};

export function AboutOpeningPitch({ paragraphs }: AboutOpeningPitchProps) {
  return (
    <div
      className="about-prose"
      style={{
        display: "flex",
        flexDirection: "column",
        gap: 14,
        fontFamily: typography.fonts.body,
        fontSize: typography.scale[2], // 16
        lineHeight: 1.55,
        color: colors.textDefault,
      }}
    >
      {paragraphs.map((p, i) => (
        <p key={i} style={{ margin: 0 }}>
          {p}
        </p>
      ))}
    </div>
  );
}
