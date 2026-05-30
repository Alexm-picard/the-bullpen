/**
 * <LiveGameHeader> — the masthead for the /games Live Game page (Stage 3d).
 *
 * Pattern: same eyebrow → two-line nameplate → byline-strip → mono-context
 * structure as `<CoverSheetHeader>` on /home, but the byline uses the
 * MatchupHeader vocabulary (BATTER ⟷ PITCHER · STATE) so this page reads
 * as the live-game variant of the matchup report at /players/:id rather
 * than as a "today's games" slate.
 *
 * Composition (top → bottom):
 *   1. HeroEyebrow: "The Bullpen · Live Game · {issueDate}" (scarlet, mono).
 *   2. Two-line nameplate h1: "LIVE GAME" / "{away} @ {home}", each on its
 *      own line via display:block spans. Saira Condensed heavy 64px, uppercase.
 *      Down-shifts to 48px on <600px viewports via `.live-game__title`.
 *   3. Byline strip with 1px top/bottom bgEmphasis rules: BATTER ⟷ PITCHER
 *      · halfInning · score (scarlet ⟷ arrow signals the matchup axis).
 *   4. Mono context line: issued timestamp + model version.
 *
 * Pure presentation; takes a small props bag.
 */

import { Title } from "@mantine/core";

import { colors, typography } from "../../design/tokens";
import { HeroEyebrow } from "../shared/hero-eyebrow";

export type LiveGameHeaderProps = {
  /** Human-friendly issue date, e.g. "Wed · May 30, 2026". */
  issueDate: string;
  /** Visiting team abbreviation, e.g. "NYY". */
  awayTeam: string;
  /** Home team abbreviation, e.g. "DET". */
  homeTeam: string;
  /** Visiting team score. */
  awayScore: number;
  /** Home team score. */
  homeScore: number;
  /** Half-inning string, e.g. "BOT 5TH". */
  halfInning: string;
  /** Batter display name. */
  batterName: string;
  /** Pitcher display name. */
  pitcherName: string;
  /** Issue timestamp, e.g. "8:42 PM ET". */
  issuedAt: string;
  /** Model fleet label, e.g. "pitch_outcome_pre v3.2 LIVE". */
  modelLabel: string;
};

function lastName(full: string): string {
  const parts = full.split(/\s+/);
  return (parts[parts.length - 1] ?? full).toUpperCase();
}

export function LiveGameHeader({
  issueDate,
  awayTeam,
  homeTeam,
  awayScore,
  homeScore,
  halfInning,
  batterName,
  pitcherName,
  issuedAt,
  modelLabel,
}: LiveGameHeaderProps) {
  const scoreLine = `${awayTeam} ${awayScore} — ${homeTeam} ${homeScore}`;
  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 10 }}>
      <HeroEyebrow>The Bullpen · Live Game · {issueDate}</HeroEyebrow>
      <Title
        order={1}
        className="live-game__title"
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
        <span style={{ display: "block" }}>Live Game</span>
        <span style={{ display: "block" }}>
          {awayTeam} @ {homeTeam}
        </span>
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
          {lastName(batterName)}
        </span>
        <span
          aria-hidden="true"
          style={{
            color: colors.scarlet,
            fontWeight: typography.weights.bold,
            fontSize: typography.scale[3],
            padding: "0 8px",
          }}
        >
          ⟷
        </span>
        <span className="sr-only">facing</span>
        <span style={{ fontWeight: typography.weights.semibold }}>
          {lastName(pitcherName)}
        </span>
        <span style={{ color: colors.textMuted }}>·</span>
        <span
          style={{
            fontFamily: typography.fonts.mono,
            fontSize: typography.scale[1], // 14
            color: colors.textMuted,
            letterSpacing: "0.04em",
            textTransform: "uppercase",
          }}
        >
          {halfInning}
        </span>
        <span style={{ color: colors.textMuted }}>·</span>
        <span
          style={{
            fontFamily: typography.fonts.mono,
            fontSize: typography.scale[1], // 14
            color: colors.textMuted,
            fontFeatureSettings: '"tnum" 1',
          }}
        >
          {scoreLine}
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
        ISSUED {issuedAt} · MODEL {modelLabel}
      </div>
    </div>
  );
}
