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
  /** Null when the score is not known yet: renders an en-dash, never a fabricated 0. */
  awayScore: number | null;
  homeScore: number | null;
  /** Short state read, e.g. "TOP 6", "FINAL", "WARMUP". */
  state: string;
  /** Renders the pulsing on-air dot + LIVE wordmark. */
  live?: boolean;
  /** Optional trailing detail, e.g. last pitch "94.8 FF". Rendered OUTSIDE the
   * live region so a new pitch does not re-announce the whole scorebug. */
  detail?: string;
  /** Fold `detail` into the announced label. For a detail that appears nowhere
   * else on the page (e.g. MLB's "Delayed Start: Rain"); a per-pitch detail
   * must stay unannounced. */
  announceDetail?: boolean;
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
  whiteSpace: "nowrap",
  flexShrink: 0,
};

const scoreStyle: React.CSSProperties = {
  fontFamily: typography.fonts.mono,
  fontSize: 18,
  fontWeight: typography.weights.heavy,
  fontFeatureSettings: '"tnum" 1',
  color: colors.textOnChrome,
  whiteSpace: "nowrap",
  flexShrink: 0,
  // A 9 -> 10 score must not shove the diamond sideways.
  minWidth: "2ch",
  textAlign: "right",
};

/** The score as shown and announced: an en-dash until it is actually known. */
const scoreText = (score: number | null): string =>
  score == null ? "–" : String(score);

function TeamWell({ team, score }: { team: string; score: number | null }) {
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
      <span style={scoreStyle}>{scoreText(score)}</span>
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
  announceDetail = false,
}: ScorebugProps) {
  return (
    // The chrome box wraps BOTH parts; only the wells + state are the live
    // region. Wrapping (flexWrap) drops the detail to a second row on a phone
    // instead of fracturing glyphs.
    <div
      style={{
        display: "flex",
        flexWrap: "wrap",
        alignItems: "stretch",
        width: "fit-content",
        maxWidth: "100%",
        backgroundColor: colors.chrome,
        border: `1px solid ${colors.chromeEdge}`,
        overflow: "hidden",
      }}
    >
      <div
        role="status"
        aria-label={`${awayTeam} ${scoreText(awayScore)}, ${homeTeam} ${scoreText(homeScore)}, ${state}${live ? ", live" : ""}${announceDetail && detail ? `, ${detail}` : ""}`}
        style={{
          display: "flex",
          flexWrap: "wrap",
          alignItems: "stretch",
          maxWidth: "100%",
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
            letterSpacing: typography.tracking.colHead,
            textTransform: "uppercase",
            color: colors.textOnChrome,
            whiteSpace: "nowrap",
            flexShrink: 0,
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
      </div>
      {detail && (
        <span
          aria-hidden="true"
          style={{
            display: "inline-flex",
            alignItems: "center",
            padding: "0 12px",
            fontFamily: typography.fonts.mono,
            fontSize: 12,
            fontFeatureSettings: '"tnum" 1',
            color: colors.textOnChromeMuted,
            whiteSpace: "nowrap",
            flexShrink: 0,
          }}
        >
          {detail}
        </span>
      )}
    </div>
  );
}
