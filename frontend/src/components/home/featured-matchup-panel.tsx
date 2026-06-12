/**
 * <FeaturedMatchupPanel> - the featured matchup on the broadcast identity
 * (redesign PR-4, decision [160]). Replaces <FeaturedMatchupCard>: a
 * LowerThird header over a cut-corner BroadcastPanel - batter vs pitcher
 * nameplates with team-color bars, the two key reads as body columns, and the
 * gold CTA link. Same props shape as the card it replaces.
 */

import { Link } from "react-router-dom";

import type { ScoutingPlayer } from "../../data/matchup-fixtures";
import { BroadcastPanel } from "../broadcast/broadcast-panel";
import { LowerThird } from "../broadcast/lower-third";
import { colors, typography } from "../../design/broadcast";
import { teamColor } from "../../design/teamColors";

export type FeaturedMatchupPanelProps = {
  batter: ScoutingPlayer;
  pitcher: ScoutingPlayer;
  /** Free-form context line shown in the lower-third meta slot. */
  context: string;
  /** Exactly two key-read paragraphs. */
  keyReads: [string, string];
  ctaHref: string;
  ctaLabel: string;
};

function Nameplate({ player }: { player: ScoutingPlayer }) {
  return (
    <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
      <span
        aria-hidden="true"
        style={{
          width: 5,
          height: 34,
          backgroundColor: teamColor(player.team),
          flex: "0 0 auto",
        }}
      />
      <div style={{ display: "flex", flexDirection: "column" }}>
        <span
          style={{
            fontFamily: typography.fonts.display,
            fontStyle: "italic",
            fontWeight: typography.weights.heavy,
            fontSize: 24,
            lineHeight: typography.lineHeights.display,
            textTransform: "uppercase",
            color: colors.ink,
          }}
        >
          {player.name}
        </span>
        <span
          style={{
            fontFamily: typography.fonts.mono,
            fontSize: 11,
            fontFeatureSettings: '"tnum" 1',
            letterSpacing: "0.04em",
            color: colors.textMuted,
          }}
        >
          {player.team} · {player.position} · {player.hand} · #{player.jersey}
        </span>
      </div>
    </div>
  );
}

export function FeaturedMatchupPanel({
  batter,
  pitcher,
  context,
  keyReads,
  ctaHref,
  ctaLabel,
}: FeaturedMatchupPanelProps) {
  return (
    <div>
      <div style={{ marginBottom: 12 }}>
        <LowerThird meta={context}>Featured Matchup</LowerThird>
      </div>
      <BroadcastPanel cut padding={20}>
        <div
          style={{
            display: "flex",
            alignItems: "center",
            flexWrap: "wrap",
            gap: 18,
            marginBottom: 16,
          }}
        >
          <Nameplate player={batter} />
          <span
            aria-hidden="true"
            style={{
              fontFamily: typography.fonts.display,
              fontStyle: "italic",
              fontWeight: typography.weights.bold,
              fontSize: 15,
              color: colors.goldInk,
              textTransform: "uppercase",
              letterSpacing: "0.08em",
            }}
          >
            vs
          </span>
          <Nameplate player={pitcher} />
        </div>

        <div
          style={{
            display: "grid",
            gridTemplateColumns: "repeat(auto-fit, minmax(280px, 1fr))",
            gap: 16,
          }}
        >
          {keyReads.map((read, i) => (
            <p
              key={i}
              style={{
                margin: 0,
                fontFamily: typography.fonts.body,
                fontSize: 14,
                lineHeight: typography.lineHeights.body,
                color: colors.text,
                borderTop: `2px solid ${colors.rule}`,
                paddingTop: 10,
              }}
            >
              {read}
            </p>
          ))}
        </div>

        <div style={{ marginTop: 18 }}>
          <Link
            to={ctaHref}
            style={{
              fontFamily: typography.fonts.display,
              fontStyle: "italic",
              fontWeight: typography.weights.bold,
              fontSize: 14,
              letterSpacing: "0.08em",
              textTransform: "uppercase",
              color: colors.goldInk,
              textDecoration: "none",
            }}
          >
            {ctaLabel}
          </Link>
        </div>
      </BroadcastPanel>
    </div>
  );
}
