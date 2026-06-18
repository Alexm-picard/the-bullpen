/**
 * <SlateBoard> - the /games slate as a card grid on the broadcast identity.
 * One card per {@link SlateCard}: team-color squares + scores, a status block
 * (gold LIVE badge / first-pitch ET / FINAL), the featured matchup + lean +
 * battle-score enrichment when present, and a numeric /games/:id link.
 *
 * Team color appears ONLY as the corner square fills ([160] a11y rule). On a
 * final, the losing side dims so the winner reads at a glance. The empty state
 * is first-class (no games match the active filter).
 */

import { Link } from "react-router-dom";

import type { SlateCard } from "../../api/slate-view";
import { colors, cuts, radii, typography } from "../../design/broadcast";
import { teamColor } from "../../design/teamColors";

import "../../design/broadcast.css";

const abbrevStyle: React.CSSProperties = {
  fontFamily: typography.fonts.display,
  fontStyle: "italic",
  fontWeight: typography.weights.bold,
  fontSize: 21,
  letterSpacing: "0.03em",
  textTransform: "uppercase",
  width: 52,
};

const scoreStyle: React.CSSProperties = {
  marginLeft: "auto",
  fontFamily: typography.fonts.mono,
  fontWeight: typography.weights.bold,
  fontSize: 21,
  fontFeatureSettings: '"tnum" 1',
};

function StatusBlock({ card }: { card: SlateCard }) {
  if (card.status === "live") {
    return (
      <span
        style={{
          display: "inline-flex",
          alignItems: "center",
          gap: 6,
          fontFamily: typography.fonts.mono,
          fontWeight: typography.weights.bold,
          fontSize: 11,
          letterSpacing: "0.08em",
          textTransform: "uppercase",
          color: colors.goldInk,
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
        Live{card.inning ? ` · Inn ${card.inning}` : ""}
      </span>
    );
  }
  return (
    <span
      style={{
        fontFamily: typography.fonts.mono,
        fontSize: 11,
        letterSpacing: "0.08em",
        textTransform: "uppercase",
        color: colors.textMuted,
      }}
    >
      {card.status === "final"
        ? (card.detailedState ?? "Final")
        : (card.firstPitchEt ?? "Scheduled")}
    </span>
  );
}

function TeamRow({
  team,
  score,
  dim,
}: {
  team: string;
  score: number | null;
  dim: boolean;
}) {
  const ink = dim ? colors.textMuted : colors.ink;
  return (
    <div
      style={{
        display: "flex",
        alignItems: "center",
        gap: 10,
        padding: "5px 0",
      }}
    >
      <span
        aria-hidden="true"
        style={{
          width: 11,
          height: 11,
          flex: "none",
          backgroundColor: teamColor(team),
        }}
      />
      <span style={{ ...abbrevStyle, color: ink }}>{team}</span>
      {score != null && (
        <span style={{ ...scoreStyle, color: ink }}>{score}</span>
      )}
    </div>
  );
}

function SlateCardView({ card }: { card: SlateCard }) {
  const isFinal = card.status === "final";
  const awayDim =
    isFinal &&
    card.awayScore != null &&
    card.homeScore != null &&
    card.awayScore < card.homeScore;
  const homeDim =
    isFinal &&
    card.awayScore != null &&
    card.homeScore != null &&
    card.homeScore < card.awayScore;

  return (
    <Link
      to={`/games/${card.gameId}`}
      aria-label={`Open game for ${card.awayTeam} at ${card.homeTeam}`}
      style={{
        display: "block",
        textDecoration: "none",
        backgroundColor: colors.panel,
        border: `1px solid ${colors.rule}`,
        clipPath: cuts.panelCorner,
        padding: "14px 16px 12px",
      }}
    >
      <div
        style={{
          display: "flex",
          alignItems: "center",
          justifyContent: "space-between",
          marginBottom: 6,
        }}
      >
        <StatusBlock card={card} />
        {card.battleScore != null && (
          <span
            style={{
              fontFamily: typography.fonts.mono,
              fontWeight: typography.weights.bold,
              fontSize: 13,
              fontFeatureSettings: '"tnum" 1',
              color: colors.ink,
            }}
          >
            <span
              style={{
                color: colors.textMuted,
                fontWeight: typography.weights.medium,
                fontSize: 9,
                letterSpacing: "0.1em",
              }}
            >
              BTL{" "}
            </span>
            {card.battleScore.toFixed(1)}
          </span>
        )}
      </div>

      <TeamRow team={card.awayTeam} score={card.awayScore} dim={awayDim} />
      <TeamRow team={card.homeTeam} score={card.homeScore} dim={homeDim} />

      {card.away && card.home && (
        <div
          style={{
            borderTop: `1px solid ${colors.rule}`,
            marginTop: 6,
            paddingTop: 9,
            fontFamily: typography.fonts.mono,
            fontSize: 11,
            letterSpacing: "0.02em",
            color: colors.textMuted,
          }}
        >
          {card.away.name} vs {card.home.name}
        </div>
      )}

      <div
        style={{
          display: "flex",
          alignItems: "center",
          justifyContent: "space-between",
          marginTop: 11,
        }}
      >
        {card.leanLabel ? (
          <span
            style={{
              fontFamily: typography.fonts.mono,
              fontSize: 10,
              fontWeight: typography.weights.medium,
              letterSpacing: "0.06em",
              textTransform: "uppercase",
              backgroundColor: colors.chrome,
              color: colors.textOnChrome,
              padding: "3px 9px",
            }}
          >
            {card.leanLabel}
          </span>
        ) : (
          <span />
        )}
        <span
          style={{
            fontFamily: typography.fonts.mono,
            fontSize: 11,
            letterSpacing: "0.06em",
            textTransform: "uppercase",
            color: colors.goldInk,
          }}
        >
          Open game &rarr;
        </span>
      </div>
    </Link>
  );
}

export function SlateBoard({ cards }: { cards: SlateCard[] }) {
  if (cards.length === 0) {
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
        No games in this view.
      </div>
    );
  }
  return (
    <div
      style={{
        display: "grid",
        gridTemplateColumns: "repeat(auto-fill, minmax(330px, 1fr))",
        gap: 14,
      }}
    >
      {cards.map((c) => (
        <SlateCardView key={c.gameId} card={c} />
      ))}
    </div>
  );
}
