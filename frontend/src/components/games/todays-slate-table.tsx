/**
 * <TodaysSlateTable> - the live slate on the BROADCAST identity (redesign
 * PR-3, decision [160]). Each game renders as a full-width clickable telecast
 * strip: team-color bars beside mono scores, a wedge state block with the
 * gold on-air dot for in-progress games, and the numeric /games/{gameId}
 * href (the FE-H1 contract).
 *
 * Team color appears ONLY as bars/fills ([160] a11y rule). The empty slate
 * stays a first-class state with its exact copy: /v1/games/today returns []
 * until a game's first OBSERVED status transition (~first pitch).
 */

import { Link } from "react-router-dom";

import type { GameSummary } from "../../api/games";
import { colors, cuts, radii, typography } from "../../design/broadcast";
import { teamColor } from "../../design/teamColors";

import "../../design/broadcast.css";

export type TodaysSlateTableProps = {
  games: GameSummary[];
  /** Optional mono context line above the strips. */
  caption?: string;
};

function isLive(g: GameSummary): boolean {
  return g.status === "IN_PROGRESS" || g.status === "MID_INNING";
}

const teamReadStyle: React.CSSProperties = {
  display: "inline-flex",
  alignItems: "center",
  gap: 8,
};

const abbrevStyle: React.CSSProperties = {
  fontFamily: typography.fonts.display,
  fontStyle: "italic",
  fontWeight: typography.weights.bold,
  fontSize: 19,
  letterSpacing: "0.04em",
  textTransform: "uppercase",
  color: colors.ink,
};

const scoreStyle: React.CSSProperties = {
  fontFamily: typography.fonts.mono,
  fontWeight: typography.weights.heavy,
  fontSize: 19,
  fontFeatureSettings: '"tnum" 1',
  color: colors.ink,
};

function TeamRead({ team, score }: { team: string; score: number }) {
  return (
    <span style={teamReadStyle}>
      <span
        aria-hidden="true"
        style={{
          width: 5,
          alignSelf: "stretch",
          minHeight: 22,
          backgroundColor: teamColor(team),
        }}
      />
      <span style={abbrevStyle}>{team}</span>
      <span style={scoreStyle}>{score}</span>
    </span>
  );
}

export function TodaysSlateTable({ games, caption }: TodaysSlateTableProps) {
  if (games.length === 0) {
    return (
      <div
        role="status"
        style={{
          backgroundColor: colors.panel,
          border: `1px solid ${colors.rule}`,
          padding: 24,
          fontFamily: typography.fonts.body,
          fontSize: 14,
          color: colors.textMuted,
          textAlign: "center",
        }}
      >
        No games yet today. A game joins the slate when its first status
        transition is observed - around first pitch, not at the scheduled start.
      </div>
    );
  }

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 8 }}>
      {caption && (
        <span
          style={{
            fontFamily: typography.fonts.mono,
            fontSize: 11,
            letterSpacing: "0.04em",
            textTransform: "uppercase",
            color: colors.textMuted,
          }}
        >
          {caption}
        </span>
      )}
      {games.map((g) => (
        <Link
          key={g.gameId}
          to={`/games/${g.gameId}`}
          className="broadcast-strip"
          aria-label={`Open live view for ${g.awayTeam} at ${g.homeTeam}`}
          style={{
            display: "flex",
            alignItems: "stretch",
            gap: 18,
            textDecoration: "none",
            backgroundColor: colors.panel,
            border: `1px solid ${colors.rule}`,
            padding: "10px 0 10px 14px",
            overflow: "hidden",
          }}
        >
          <TeamRead team={g.awayTeam} score={g.awayScore} />
          <span
            aria-hidden="true"
            style={{
              alignSelf: "center",
              color: colors.steel,
              fontSize: 11,
            }}
          >
            ◆
          </span>
          <TeamRead team={g.homeTeam} score={g.homeScore} />

          <span
            style={{
              display: "inline-flex",
              alignItems: "center",
              gap: 8,
              marginLeft: "auto",
              padding: "0 18px 0 22px",
              backgroundColor: colors.chrome,
              clipPath: cuts.wedge,
              fontFamily: typography.fonts.display,
              fontStyle: "italic",
              fontWeight: typography.weights.bold,
              fontSize: 14,
              letterSpacing: "0.06em",
              textTransform: "uppercase",
              color: colors.textOnChrome,
            }}
          >
            {g.detailedState}
            {isLive(g) && (
              <span
                style={{
                  display: "inline-flex",
                  alignItems: "center",
                  gap: 5,
                  color: colors.gold,
                }}
              >
                <span
                  className="broadcast-live-dot"
                  aria-hidden="true"
                  style={{
                    width: 7,
                    height: 7,
                    borderRadius: radii.pill,
                    backgroundColor: colors.gold,
                  }}
                />
                LIVE
              </span>
            )}
          </span>
          <span
            style={{
              display: "inline-flex",
              alignItems: "center",
              minWidth: 64,
              fontFamily: typography.fonts.mono,
              fontSize: 12,
              fontFeatureSettings: '"tnum" 1',
              color: colors.textMuted,
            }}
          >
            {g.inning > 0 ? `INN ${g.inning}` : "—"}
          </span>
          <span
            style={{
              display: "inline-flex",
              alignItems: "center",
              paddingRight: 16,
              fontFamily: typography.fonts.display,
              fontStyle: "italic",
              fontWeight: typography.weights.bold,
              fontSize: 13,
              letterSpacing: "0.08em",
              textTransform: "uppercase",
              color: colors.goldInk,
              whiteSpace: "nowrap",
            }}
          >
            Open &rarr;
          </span>
        </Link>
      ))}
    </div>
  );
}
