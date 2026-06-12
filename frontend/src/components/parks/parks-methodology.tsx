/**
 * <ParksMethodology> — single-line monospace methodology strip.
 *
 * Sits directly below <ParksHeader>. Single IBM Plex Mono 12px line, uppercase
 * tracked, navy-deep top border + bgEmphasis bottom border, no card chrome.
 * Reads like an editor's note above the body — "here's how this was made"
 * before the OVERVIEW section starts.
 *
 * The content string is supplied verbatim from the fixture so the page never
 * has to compose methodology phrasing inline.
 */

import { colors, typography } from "../../design/broadcast";

export type ParksMethodologyProps = {
  /** Full methodology line, written upstream in the fixture file. */
  line: string;
};

export function ParksMethodology({ line }: ParksMethodologyProps) {
  return (
    <div
      style={{
        fontFamily: typography.fonts.mono,
        fontSize: typography.scale[0], // 12
        color: colors.textMuted,
        letterSpacing: "0.04em",
        padding: "8px 0",
        borderTop: `1px solid ${colors.chromeDeep}`,
        borderBottom: `1px solid ${colors.rule}`,
      }}
    >
      {line}
    </div>
  );
}
