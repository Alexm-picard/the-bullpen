/**
 * <MatchupHeader> — the hero block at the top of the Matchup Report.
 *
 * Layout:
 *   1. HeroEyebrow: "ADVANCE SCOUTING" (scarlet, mono, uppercase, tracked).
 *   2. Title order={1}: "HITTING REPORT — JUDGE vs. SKUBAL (LHP)".
 *      Saira Condensed heavy uppercase per scouting-report identity.
 *   3. Byline strip: "Primary · Position · Hand  ⟷  Opponent · Position · Hand"
 *      with the ⟷ glyph rendered in scarlet to signal the matchup axis.
 *   4. Metadata line: small mono muted text — the game context string.
 *
 * Pure presentation; takes a {@link ScoutingPlayer} for each side plus the
 * context string.
 */

import { Stack, Title } from "@mantine/core";

import type { ScoutingPlayer } from "../../data/matchup-fixtures";
import { colors, typography } from "../../design/tokens";
import { HeroEyebrow } from "../shared/hero-eyebrow";

export type MatchupHeaderProps = {
  primary: ScoutingPlayer;
  opponent: ScoutingPlayer;
  context: string;
};

function shortName(p: ScoutingPlayer): string {
  // "Aaron Judge" → "JUDGE".
  const parts = p.name.split(/\s+/);
  return (parts[parts.length - 1] ?? p.name).toUpperCase();
}

function reportKind(primary: ScoutingPlayer, opponent: ScoutingPlayer): string {
  // If primary is a pitcher, it's a "PITCHING REPORT".
  const pitcherPositions = ["SP", "RP", "P", "CL"];
  if (pitcherPositions.includes(primary.position)) return "PITCHING REPORT";
  if (pitcherPositions.includes(opponent.position)) return "HITTING REPORT";
  return "MATCHUP REPORT";
}

export function MatchupHeader({
  primary,
  opponent,
  context,
}: MatchupHeaderProps) {
  const kind = reportKind(primary, opponent);
  const opponentHand = opponent.hand.split("/")[1] ?? opponent.hand;
  const oppoHandLabel = `${opponentHand}H${opponent.position === "SP" || opponent.position === "P" || opponent.position === "RP" ? "P" : ""}`;
  const titleText = `${kind} — ${shortName(primary)} vs. ${shortName(opponent)} (${oppoHandLabel})`;

  return (
    <Stack gap={10}>
      <HeroEyebrow>Advance Scouting</HeroEyebrow>
      <Title
        order={1}
        style={{
          fontFamily: typography.fonts.display,
          fontSize: typography.scale[6], // 48
          fontWeight: typography.weights.heavy,
          color: colors.textStrong,
          textTransform: "uppercase",
          letterSpacing: "0.005em",
          lineHeight: typography.lineHeights.display,
          margin: 0,
        }}
      >
        {titleText}
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
          paddingTop: 4,
          paddingBottom: 4,
          borderTop: `1px solid ${colors.bgEmphasis}`,
          borderBottom: `1px solid ${colors.bgEmphasis}`,
        }}
      >
        <span style={{ fontWeight: typography.weights.semibold }}>
          {primary.name}
        </span>
        <span style={{ color: colors.textMuted }}>·</span>
        <span style={{ color: colors.textMuted }}>{primary.position}</span>
        <span style={{ color: colors.textMuted }}>·</span>
        <span
          style={{
            fontFamily: typography.fonts.mono,
            fontSize: typography.scale[1],
            color: colors.textMuted,
          }}
        >
          {primary.hand}
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
        <span className="sr-only">versus</span>
        <span style={{ fontWeight: typography.weights.semibold }}>
          {opponent.name}
        </span>
        <span style={{ color: colors.textMuted }}>·</span>
        <span style={{ color: colors.textMuted }}>{opponent.position}</span>
        <span style={{ color: colors.textMuted }}>·</span>
        <span
          style={{
            fontFamily: typography.fonts.mono,
            fontSize: typography.scale[1],
            color: colors.textMuted,
          }}
        >
          {opponent.hand}
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
        {context}
      </div>
    </Stack>
  );
}
