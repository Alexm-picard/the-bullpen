/**
 * <ParksHeader> — the masthead for the Park Factors appendix (/parks).
 *
 * Pattern: lifts the eyebrow → two-line nameplate → byline strip → mono
 * context line structure from <CoverSheetHeader> and <MatchupHeader> so the
 * three report shells share a visual masthead vocabulary.
 *
 * Layout (top → bottom):
 *   1. HeroEyebrow: "THE BULLPEN · PARK FACTORS · APPENDIX A".
 *   2. Two-line nameplate h1 — "PARK" / "FACTORS" each on its own line via
 *      display:block spans. Saira Condensed heavy 64px → 48px <600px (the
 *      down-shift lives in parks.css under .parks__title).
 *   3. Byline strip: edition · 30 parks · 3-yr rolling · n=437,210, with the
 *      same border-top/-bottom bgEmphasis 1-px treatment as the home masthead.
 *   4. Mono context line: data-window + model tag.
 *
 * Pure presentation. Props mirror the {@link ParksMeta} fixture shape.
 */

import { Stack, Title } from "@mantine/core";

import { colors, typography } from "../../design/broadcast";
import { HeroEyebrow } from "../shared/hero-eyebrow";

export type ParksHeaderProps = {
  /** Human-friendly edition string, e.g. "2026.05.30". */
  edition: string;
  /** Sample size for the byline strip. */
  sampleN: number;
  /** Mono context line — "DATA WINDOW 2023 — 2025". */
  dataWindow: string;
  /** Mono context line — "MODEL park_factor_v2". */
  modelTag: string;
};

function formatN(n: number): string {
  // 437210 → "437,210"
  return n.toLocaleString("en-US");
}

export function ParksHeader({
  edition,
  sampleN,
  dataWindow,
  modelTag,
}: ParksHeaderProps) {
  return (
    <Stack gap={10}>
      <HeroEyebrow>
        The Bullpen &middot; Park Factors &middot; Appendix A
      </HeroEyebrow>
      <Title
        order={1}
        className="parks__title"
        style={{
          fontFamily: typography.fonts.display,
          fontSize: typography.scale[7], // 64
          fontWeight: typography.weights.heavy,
          color: colors.ink,
          textTransform: "uppercase",
          letterSpacing: "0.005em",
          lineHeight: typography.lineHeights.display,
          margin: 0,
        }}
      >
        <span style={{ display: "block" }}>Park</span>
        <span style={{ display: "block" }}>Factors</span>
      </Title>
      <div
        style={{
          display: "flex",
          alignItems: "center",
          gap: 12,
          flexWrap: "wrap",
          fontFamily: typography.fonts.body,
          fontSize: typography.scale[2], // 16
          color: colors.text,
          paddingTop: 6,
          paddingBottom: 6,
          borderTop: `1px solid ${colors.rule}`,
          borderBottom: `1px solid ${colors.rule}`,
        }}
      >
        <span style={{ fontWeight: typography.weights.semibold }}>
          Edition {edition}
        </span>
        <span style={{ color: colors.textMuted }}>·</span>
        <span style={{ fontWeight: typography.weights.semibold }}>
          30 parks
        </span>
        <span style={{ color: colors.textMuted }}>·</span>
        <span
          style={{
            fontFamily: typography.fonts.mono,
            fontSize: typography.scale[1], // 14
            color: colors.textMuted,
          }}
        >
          3-yr rolling
        </span>
        <span style={{ color: colors.textMuted }}>·</span>
        <span
          style={{
            fontFamily: typography.fonts.mono,
            fontSize: typography.scale[1], // 14
            color: colors.textMuted,
          }}
        >
          n={formatN(sampleN)}
        </span>
      </div>
      <div
        style={{
          fontFamily: typography.fonts.mono,
          fontSize: typography.scale[0], // 12
          color: colors.textMuted,
          letterSpacing: "0.04em",
          textTransform: "uppercase",
        }}
      >
        {dataWindow} · {modelTag}
      </div>
    </Stack>
  );
}
