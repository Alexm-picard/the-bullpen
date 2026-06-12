/**
 * <AboutRoadmap> — one short prose paragraph on phase status + season window.
 *
 * Same prose treatment as <AboutOpeningPitch>: IBM Plex Sans 16px,
 * line-height ~1.55, max-width ~62ch via the .about-prose class. Single
 * paragraph; the page composes it under the ROADMAP HONESTY section label.
 */

import { colors, typography } from "../../design/broadcast";

export type AboutRoadmapProps = {
  paragraph: string;
};

export function AboutRoadmap({ paragraph }: AboutRoadmapProps) {
  return (
    <p
      className="about-prose"
      style={{
        margin: 0,
        fontFamily: typography.fonts.body,
        fontSize: typography.scale[2], // 16
        lineHeight: 1.55,
        color: colors.text,
      }}
    >
      {paragraph}
    </p>
  );
}
