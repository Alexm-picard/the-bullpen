/**
 * <Scorebug> - the persistent game-state chip of the broadcast identity
 * (decision [160]). Two team wells with team-color edge fills, mono scores, a
 * wedge-cut state block (inning / FINAL), and the gold on-air dot when live.
 *
 * Team color appears ONLY as fills/edges (never text) per [160]'s a11y rule.
 * Purely presentational; the caller owns data + polling.
 */

import { colors, cuts, radii, typography } from "../../design/broadcast";
import { teamColor } from "../../design/teamColors";

import "../../design/broadcast.css";

export type ScorebugProps = {
  awayTeam: string;
  homeTeam: string;
  awayScore: number;
  homeScore: number;
  /** Short state read, e.g. "TOP 6", "FINAL", "WARMUP". */
  state: string;
  /** Renders the pulsing on-air dot + LIVE wordmark. */
  live?: boolean;
  /** Optional trailing detail, e.g. last pitch "94.8 FF". */
  detail?: string;
};

const wellStyle: React.CSSProperties = {
  display: "flex",
  alignItems: "center",
  gap: 8,
  backgroundColor: colors.chromeDeep,
  padding: "6px 10px 6px 0",
};

const abbrevStyle: React.CSSProperties = {
  fontFamily: typography.fonts.display,
  fontSize: 18,
  fontWeight: typography.weights.bold,
  fontStyle: "italic",
  textTransform: "uppercase",
  letterSpacing: "0.04em",
  color: colors.textOnChrome,
};

const scoreStyle: React.CSSProperties = {
  fontFamily: typography.fonts.mono,
  fontSize: 18,
  fontWeight: typography.weights.heavy,
  fontFeatureSettings: '"tnum" 1',
  color: colors.textOnChrome,
};

function TeamWell({ team, score }: { team: string; score: number }) {
  return (
    <span style={wellStyle}>
      <span
        aria-hidden="true"
        style={{
          alignSelf: "stretch",
          width: 5,
          backgroundColor: teamColor(team),
        }}
      />
      <span style={abbrevStyle}>{team}</span>
      <span style={scoreStyle}>{score}</span>
    </span>
  );
}

export function Scorebug({
  awayTeam,
  homeTeam,
  awayScore,
  homeScore,
  state,
  live = false,
  detail,
}: ScorebugProps) {
  return (
    <div
      role="status"
      aria-label={`${awayTeam} ${awayScore}, ${homeTeam} ${homeScore}, ${state}${live ? ", live" : ""}`}
      style={{
        display: "inline-flex",
        alignItems: "stretch",
        backgroundColor: colors.chrome,
        border: `1px solid ${colors.chromeEdge}`,
        overflow: "hidden",
      }}
    >
      <TeamWell team={awayTeam} score={awayScore} />
      <span
        aria-hidden="true"
        style={{
          alignSelf: "center",
          padding: "0 8px",
          color: colors.steel,
          fontSize: 11,
        }}
      >
        ◆
      </span>
      <TeamWell team={homeTeam} score={homeScore} />
      <span
        style={{
          display: "inline-flex",
          alignItems: "center",
          gap: 8,
          padding: "0 14px 0 18px",
          marginLeft: 4,
          backgroundColor: colors.chromeEdge,
          clipPath: cuts.wedge,
          fontFamily: typography.fonts.display,
          fontStyle: "italic",
          fontWeight: typography.weights.bold,
          fontSize: 15,
          letterSpacing: "0.06em",
          textTransform: "uppercase",
          color: colors.textOnChrome,
        }}
      >
        {state}
        {live && (
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
                width: 8,
                height: 8,
                borderRadius: radii.pill,
                backgroundColor: colors.gold,
              }}
            />
            LIVE
          </span>
        )}
      </span>
      {detail && (
        <span
          style={{
            display: "inline-flex",
            alignItems: "center",
            padding: "0 12px",
            fontFamily: typography.fonts.mono,
            fontSize: 12,
            fontFeatureSettings: '"tnum" 1',
            color: colors.textOnChromeMuted,
          }}
        >
          {detail}
        </span>
      )}
    </div>
  );
}
