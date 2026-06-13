/**
 * <LiveTonightStrip> - the home page's LIVE tonight's-games teaser (B3 / FE-H1),
 * fed by the same /v1/games/today slate the /games page uses. A compact row of
 * game chips (each links to its live game page) plus a CTA to the full slate.
 * Distinct from the showcase <TonightsMatchupsBoard> below it, which carries
 * edge model reads that have no live endpoint yet.
 *
 * Presentational: the page owns the useTodaysGames query + its loading/error
 * states; this renders the resolved slate (with its own empty state).
 */

import { Link } from "react-router-dom";

import type { GameSummary } from "../../api/games";
import { colors, typography } from "../../design/broadcast";

export type LiveTonightStripProps = {
  games: GameSummary[];
};

const chipStyle: React.CSSProperties = {
  display: "inline-flex",
  flexDirection: "column",
  gap: 2,
  padding: "6px 10px",
  border: `1px solid ${colors.rule}`,
  borderRadius: 2,
  backgroundColor: colors.panel,
  textDecoration: "none",
};

export function LiveTonightStrip({ games }: LiveTonightStripProps) {
  if (games.length === 0) {
    return (
      <p style={{ fontFamily: typography.fonts.body, color: colors.textMuted }}>
        No games on the schedule today.
      </p>
    );
  }

  return (
    <div
      style={{
        display: "flex",
        flexWrap: "wrap",
        gap: 8,
        alignItems: "stretch",
      }}
    >
      {games.map((g) => (
        <Link key={g.gameId} to={`/games/${g.gameId}`} style={chipStyle}>
          <span
            style={{
              fontFamily: typography.fonts.display,
              fontStyle: "italic",
              fontWeight: typography.weights.semibold,
              fontSize: 14,
              letterSpacing: "0.03em",
              color: colors.ink,
            }}
          >
            {g.awayTeam} @ {g.homeTeam}
          </span>
          <span
            style={{
              fontFamily: typography.fonts.mono,
              fontSize: 11,
              letterSpacing: "0.02em",
              color: colors.textMuted,
            }}
          >
            {g.detailedState}
          </span>
        </Link>
      ))}
      <Link
        to="/games"
        style={{
          display: "inline-flex",
          alignItems: "center",
          padding: "6px 12px",
          fontFamily: typography.fonts.mono,
          fontSize: 12,
          fontWeight: typography.weights.semibold,
          letterSpacing: "0.04em",
          textTransform: "uppercase",
          color: colors.goldInk,
          textDecoration: "none",
        }}
      >
        View all games &rarr;
      </Link>
    </div>
  );
}
