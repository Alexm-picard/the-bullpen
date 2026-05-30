/**
 * <CoverSheetHeader> — the masthead for the Tonight's Slate cover-sheet (/home).
 *
 * Pattern: lifts the eyebrow → h1 → byline-strip → mono-context structure from
 * `<MatchupHeader>` but takes a date + slate-count payload instead of two
 * ScoutingPlayers, so the type signatures don't unify cleanly. The two
 * components share visual language, not code.
 *
 * Layout (top → bottom):
 *   1. HeroEyebrow: "The Bullpen · Advance Scouting" (scarlet, mono, tracked).
 *   2. Two-line nameplate h1: "TONIGHT'S" / "SLATE", each on its own line
 *      via display:block spans. Saira Condensed heavy 64px, uppercase. The
 *      two-line break is intentional (locked pick A) — it lets the masthead
 *      breathe and reads as a printed packet cover.
 *   3. Byline strip with the same border-top + border-bottom bgEmphasis 1-px
 *      treatment as MatchupHeader: date, matchup count, L/R hand counts.
 *   4. Mono context line: ISSUE timestamp + first-pitch window.
 *
 * Pure presentation; takes a small props bag.
 */

import { Stack, Title } from "@mantine/core";

import { colors, typography } from "../../design/tokens";
import { HeroEyebrow } from "../shared/hero-eyebrow";

export type CoverSheetHeaderProps = {
  /** Human-friendly issue date, e.g. "Wed · May 30, 2026". */
  issueDate: string;
  /** Total matchups on the slate. */
  matchupCount: number;
  /** Number of left-handed starters scheduled. */
  lhpCount: number;
  /** Number of right-handed starters scheduled. */
  rhpCount: number;
  /** Issue timestamp, e.g. "19:05 ET". */
  issuedAt: string;
  /** First-pitch window string, e.g. "FIRST PITCH 18:40 ET — 22:15 ET". */
  firstPitchWindow: string;
};

export function CoverSheetHeader({
  issueDate,
  matchupCount,
  lhpCount,
  rhpCount,
  issuedAt,
  firstPitchWindow,
}: CoverSheetHeaderProps) {
  return (
    <Stack gap={10}>
      <HeroEyebrow>The Bullpen · Advance Scouting</HeroEyebrow>
      <Title
        order={1}
        className="home-cover__title"
        style={{
          fontFamily: typography.fonts.display,
          fontSize: typography.scale[7], // 64
          fontWeight: typography.weights.heavy,
          color: colors.textStrong,
          textTransform: "uppercase",
          letterSpacing: "0.005em",
          lineHeight: typography.lineHeights.display,
          margin: 0,
        }}
      >
        <span style={{ display: "block" }}>Tonight&rsquo;s</span>
        <span style={{ display: "block" }}>Slate</span>
      </Title>
      <div
        style={{
          display: "flex",
          alignItems: "center",
          gap: 12,
          flexWrap: "wrap",
          fontFamily: typography.fonts.body,
          fontSize: typography.scale[2], // 16
          color: colors.textDefault,
          paddingTop: 6,
          paddingBottom: 6,
          borderTop: `1px solid ${colors.bgEmphasis}`,
          borderBottom: `1px solid ${colors.bgEmphasis}`,
        }}
      >
        <span style={{ fontWeight: typography.weights.semibold }}>
          {issueDate}
        </span>
        <span style={{ color: colors.textMuted }}>·</span>
        <span style={{ fontWeight: typography.weights.semibold }}>
          {matchupCount} matchups
        </span>
        <span style={{ color: colors.textMuted }}>·</span>
        <span
          style={{
            fontFamily: typography.fonts.mono,
            fontSize: typography.scale[1], // 14
            color: colors.textMuted,
          }}
        >
          {lhpCount}L
        </span>
        <span style={{ color: colors.textMuted }}>/</span>
        <span
          style={{
            fontFamily: typography.fonts.mono,
            fontSize: typography.scale[1], // 14
            color: colors.textMuted,
          }}
        >
          {rhpCount}R starters
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
        ISSUE {issuedAt} · {firstPitchWindow}
      </div>
    </Stack>
  );
}
