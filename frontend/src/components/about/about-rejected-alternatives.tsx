/**
 * <AboutRejectedAlternatives> — one framing paragraph + 11 mono "✗" tags.
 *
 * The list is intentionally not a "missing features" complaint — each tag is
 * a considered-and-rejected alternative with a decision-log entry behind it.
 * The framing sentence makes that posture explicit before the tags read.
 *
 * Tags render as IBM Plex Mono 12px, textMuted, flex-wrap with 12px gap.
 * The `✗` glyph leads each tag in scarlet. No <a> wrappers — these are
 * labels, not links.
 */

import { radii, colors, typography } from "../../design/tokens";

export type AboutRejectedAlternativesProps = {
  paragraph: string;
  tags: string[];
};

export function AboutRejectedAlternatives({
  paragraph,
  tags,
}: AboutRejectedAlternativesProps) {
  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 14 }}>
      <p
        className="about-prose"
        style={{
          margin: 0,
          fontFamily: typography.fonts.body,
          fontSize: typography.scale[2], // 16
          lineHeight: 1.55,
          color: colors.textDefault,
        }}
      >
        {paragraph}
      </p>
      <ul
        style={{
          margin: 0,
          padding: 0,
          listStyle: "none",
          display: "flex",
          flexWrap: "wrap",
          gap: 12,
        }}
      >
        {tags.map((tag, i) => (
          <li
            key={`${tag}-${i}`}
            style={{
              fontFamily: typography.fonts.mono,
              fontSize: 12,
              color: colors.textMuted,
              letterSpacing: "0.02em",
              padding: "4px 8px",
              backgroundColor: colors.bgSubtle,
              border: `1px solid ${colors.bgEmphasis}`,
              borderRadius: radii.sm,
              display: "inline-flex",
              alignItems: "center",
              gap: 6,
            }}
          >
            <span
              aria-hidden="true"
              style={{
                color: colors.scarlet,
                fontWeight: typography.weights.bold,
              }}
            >
              ✗
            </span>
            <span>{tag}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}
