/**
 * <AboutHeader> — the masthead for /about (Stage 3e, decision [133] identity).
 *
 * Pattern: mirrors <CoverSheetHeader> on /home but the byline strip carries
 * colophon meta (built solo, edition, calendar, weekly hours) instead of a
 * matchup-count + L/R counts strip. Two-line nameplate `ABOUT` / `THE BULLPEN`
 * matches the locked pick N1 from the orchestrator flow.
 *
 * Layout (top → bottom):
 *   1. HeroEyebrow: "The Bullpen · Colophon · Back Matter" (scarlet mono).
 *   2. Two-line nameplate h1: `ABOUT` / `THE BULLPEN`, each span display:block.
 *      Saira Condensed heavy 64px → 48px below 600px (CSS media query in
 *      about.css). The two-line break is the locked pick — matches /home's
 *      "TONIGHT'S / SLATE" cadence.
 *   3. Byline strip with the bgEmphasis border-top + border-bottom treatment.
 *   4. Mono context line: ISSUED yyyy-mm-dd, uppercase tracked +0.04em.
 *
 * Pure presentation; props come from ABOUT_META in about-fixtures.ts.
 */

import { Stack, Title } from "@mantine/core";

import { colors, typography } from "../../design/broadcast";

export type AboutHeaderProps = {
  /** ISO date, e.g. "2026-05-30". Rendered in the mono context line. */
  issueDate: string;
  /** "Built solo" — leftmost byline cell. */
  builtBy: string;
  /** "Edition v0.4 (Phase 2a)". */
  edition: string;
  /** "~8–10 mo". */
  calendar: string;
  /** "~12–15 h/wk". */
  weeklyHours: string;
};

export function AboutHeader({
  issueDate,
  builtBy,
  edition,
  calendar,
  weeklyHours,
}: AboutHeaderProps) {
  return (
    <Stack gap={10}>
      <span
        style={{
          fontFamily: typography.fonts.mono,
          fontSize: 12,
          fontWeight: typography.weights.semibold,
          letterSpacing: "0.12em",
          textTransform: "uppercase",
          color: colors.goldInk,
        }}
      >
        The Bullpen · Colophon · Back Matter
      </span>
      <Title
        order={1}
        className="about-cover__title"
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
        <span style={{ display: "block" }}>About</span>
        <span style={{ display: "block" }}>The Bullpen</span>
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
          {builtBy}
        </span>
        <span style={{ color: colors.textMuted }}>·</span>
        <span style={{ fontWeight: typography.weights.semibold }}>
          {edition}
        </span>
        <span style={{ color: colors.textMuted }}>·</span>
        <span
          style={{
            fontFamily: typography.fonts.mono,
            fontSize: typography.scale[1], // 14
            color: colors.textMuted,
          }}
        >
          {calendar}
        </span>
        <span style={{ color: colors.textMuted }}>·</span>
        <span
          style={{
            fontFamily: typography.fonts.mono,
            fontSize: typography.scale[1], // 14
            color: colors.textMuted,
          }}
        >
          {weeklyHours}
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
        ISSUED {issueDate}
      </div>
    </Stack>
  );
}
