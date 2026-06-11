/**
 * <TodaysSlateTable> - the live slate on /games (FE-H1 closure).
 *
 * Renders GET /v1/games/today rows as a 5-column report table:
 *   col 1 (row label) - MATCHUP, "AWY @ HOM"
 *   col 2             - STATUS, the humanized detailedState, mono
 *   col 3             - SCORE, "away - home", mono tabular
 *   col 4             - INNING, mono
 *   col 5             - OPEN, scarlet uppercase Link -> /games/{gameId}
 *
 * The OPEN href carries the NUMERIC gamePk (game-page.tsx does Number(id)),
 * never a slug - the fixture-era slug hrefs rendered "Invalid game id" on
 * every in-app link to the one live page.
 *
 * Custom <table> (not <StatTable>) for the same reason as
 * <TonightsMatchupsTable>: the OPEN cell must be a real <a>.
 *
 * Empty slate is a first-class state, not an error: /v1/games/today returns
 * [] until a game's first OBSERVED status transition writes (~first pitch,
 * not schedule time).
 */

import { Link } from "react-router-dom";

import type { GameSummary } from "../../api/games";
import { radii, colors, typography } from "../../design/tokens";

export type TodaysSlateTableProps = {
  games: GameSummary[];
  caption?: string;
};

const tableBorder = `1px solid ${colors.bgEmphasis}`;

const headerCellStyle: React.CSSProperties = {
  backgroundColor: colors.navy,
  color: colors.textOnNavy,
  fontFamily: typography.fonts.display,
  fontSize: 14,
  fontWeight: typography.weights.bold,
  textTransform: "uppercase",
  letterSpacing: "0.04em",
  lineHeight: typography.lineHeights.display,
  padding: "8px 12px",
  borderBottom: tableBorder,
  borderRight: tableBorder,
  whiteSpace: "nowrap",
  verticalAlign: "middle",
  textAlign: "left",
};

const labelCellStyle: React.CSSProperties = {
  backgroundColor: colors.silver,
  color: colors.textStrong,
  fontFamily: typography.fonts.display,
  fontSize: 15,
  fontWeight: typography.weights.bold,
  textTransform: "uppercase",
  letterSpacing: "0.02em",
  padding: "10px 12px",
  borderBottom: tableBorder,
  borderRight: tableBorder,
  whiteSpace: "nowrap",
  verticalAlign: "middle",
};

const dataCellBaseStyle: React.CSSProperties = {
  fontFamily: typography.fonts.body,
  fontSize: 14,
  color: colors.textStrong,
  padding: "10px 12px",
  borderBottom: tableBorder,
  borderRight: tableBorder,
  verticalAlign: "middle",
};

const monoDataCellStyle: React.CSSProperties = {
  ...dataCellBaseStyle,
  fontFamily: typography.fonts.mono,
  fontFeatureSettings: '"tnum" 1',
};

export function TodaysSlateTable({ games, caption }: TodaysSlateTableProps) {
  if (games.length === 0) {
    return (
      <div
        role="status"
        style={{
          backgroundColor: colors.bgSheet,
          border: tableBorder,
          borderRadius: radii.sm,
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
    <div
      style={{
        overflowX: "auto",
        border: tableBorder,
        borderRadius: radii.sm,
        backgroundColor: colors.bgSheet,
      }}
    >
      <table
        style={{
          borderCollapse: "collapse",
          width: "100%",
          backgroundColor: colors.bgSheet,
          tableLayout: "auto",
        }}
      >
        {caption && (
          <caption
            style={{
              captionSide: "top",
              textAlign: "left",
              fontFamily: typography.fonts.body,
              fontSize: 12,
              fontWeight: typography.weights.semibold,
              color: colors.textMuted,
              padding: "8px 12px 4px",
              letterSpacing: "0.04em",
              textTransform: "uppercase",
            }}
          >
            {caption}
          </caption>
        )}
        <thead>
          <tr>
            <th scope="col" style={headerCellStyle}>
              Matchup
            </th>
            <th scope="col" style={headerCellStyle}>
              Status
            </th>
            <th scope="col" style={{ ...headerCellStyle, textAlign: "right" }}>
              Score
            </th>
            <th scope="col" style={{ ...headerCellStyle, textAlign: "right" }}>
              Inning
            </th>
            <th
              scope="col"
              style={{
                ...headerCellStyle,
                textAlign: "right",
                borderRight: "none",
              }}
            >
              Open
            </th>
          </tr>
        </thead>
        <tbody>
          {games.map((g) => (
            <tr key={g.gameId}>
              <th scope="row" style={labelCellStyle}>
                {g.awayTeam}{" "}
                <span
                  style={{
                    color: colors.textMuted,
                    fontWeight: typography.weights.regular,
                  }}
                >
                  @
                </span>{" "}
                {g.homeTeam}
              </th>
              <td style={monoDataCellStyle}>{g.detailedState}</td>
              <td
                style={{ ...monoDataCellStyle, textAlign: "right" }}
                aria-label={`Score ${g.awayTeam} ${g.awayScore}, ${g.homeTeam} ${g.homeScore}`}
              >
                {g.awayScore}&ndash;{g.homeScore}
              </td>
              <td style={{ ...monoDataCellStyle, textAlign: "right" }}>
                {g.inning > 0 ? g.inning : "—"}
              </td>
              <td
                style={{
                  ...dataCellBaseStyle,
                  textAlign: "right",
                  borderRight: "none",
                }}
              >
                <Link
                  to={`/games/${g.gameId}`}
                  style={{
                    fontFamily: typography.fonts.display,
                    fontSize: 13,
                    fontWeight: typography.weights.bold,
                    textTransform: "uppercase",
                    letterSpacing: "0.06em",
                    color: colors.scarlet,
                    textDecoration: "none",
                    whiteSpace: "nowrap",
                  }}
                  aria-label={`Open live view for ${g.awayTeam} at ${g.homeTeam}`}
                >
                  Open &rarr;
                </Link>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
