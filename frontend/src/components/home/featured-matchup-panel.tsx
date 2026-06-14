/**
 * <FeaturedMatchupPanel> - the best battle of the slate on the broadcast
 * identity (Phase 4b). Reads one {@link FeaturedMatchupView}: the lean badge in
 * the lower-third meta, the two lean-driven people as team-color nameplates
 * (two pitchers for a pitching duel, two hitters for a hitters duel, the
 * stronger side of each for a split lean - the backend classifier already
 * chose them), the battle score, and real CTAs (nameplates -> player report,
 * gold link -> the live game).
 */

import { Link } from "react-router-dom";

import type { MatchupSide } from "../../api/matchups-view";
import type { FeaturedMatchupView } from "../../api/matchups-view";
import { BroadcastPanel } from "../broadcast/broadcast-panel";
import { LowerThird } from "../broadcast/lower-third";
import { colors, typography } from "../../design/broadcast";
import { teamColor } from "../../design/teamColors";

export type FeaturedMatchupPanelProps = {
  matchup: FeaturedMatchupView;
};

function Nameplate({ side }: { side: MatchupSide }) {
  return (
    <Link
      to={`/players/${side.playerId}`}
      style={{
        display: "flex",
        alignItems: "center",
        gap: 10,
        textDecoration: "none",
      }}
    >
      <span
        aria-hidden="true"
        style={{
          width: 5,
          height: 34,
          backgroundColor: teamColor(side.team),
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
          {side.name}
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
          {side.team} · {side.role.toUpperCase()}
        </span>
      </div>
    </Link>
  );
}

const badgeStyle: React.CSSProperties = {
  display: "inline-block",
  backgroundColor: colors.chromeDeep,
  color: colors.textOnChrome,
  fontFamily: typography.fonts.display,
  fontStyle: "italic",
  fontWeight: typography.weights.bold,
  fontSize: 11,
  letterSpacing: "0.08em",
  textTransform: "uppercase",
  padding: "3px 8px",
};

export function FeaturedMatchupPanel({ matchup }: FeaturedMatchupPanelProps) {
  const stageNote =
    matchup.stage === "lineup" ? "lineup confirmed" : "probables";
  return (
    <div>
      <div style={{ marginBottom: 12 }}>
        <LowerThird meta={`${matchup.leanLabel} · ${matchup.firstPitchEt}`}>
          Featured Matchup
        </LowerThird>
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
          <Nameplate side={matchup.away} />
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
          <Nameplate side={matchup.home} />
        </div>

        <div
          style={{
            display: "flex",
            alignItems: "baseline",
            gap: 12,
            borderTop: `2px solid ${colors.rule}`,
            paddingTop: 12,
          }}
        >
          <span style={badgeStyle}>{matchup.leanLabel}</span>
          <span
            style={{
              fontFamily: typography.fonts.mono,
              fontSize: 13,
              fontFeatureSettings: '"tnum" 1',
              color: colors.text,
            }}
          >
            Battle score {matchup.battleScore.toFixed(1)}
          </span>
          <span
            style={{
              fontFamily: typography.fonts.mono,
              fontSize: 11,
              letterSpacing: "0.04em",
              textTransform: "uppercase",
              color: colors.textMuted,
            }}
          >
            {stageNote}
          </span>
        </div>

        <div style={{ marginTop: 18 }}>
          <Link
            to={`/games/${matchup.gameId}`}
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
            Open the game &rarr;
          </Link>
        </div>
      </BroadcastPanel>
    </div>
  );
}
