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

import { Link } from "react-router";

import type { SlateCard } from "../../api/slate-view";
import { colors, cuts, radii, typography } from "../../design/broadcast";
import { teamColor } from "../../design/teamColors";

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
  if (card.status === "live" && card.paused) {
    // A halted game (rain delay, suspension): its state, not the on-air dot.
    return (
      <span
        style={{
          fontFamily: typography.fonts.mono,
          fontWeight: typography.weights.bold,
          fontSize: 11,
          letterSpacing: typography.tracking.label,
          textTransform: "uppercase",
          color: colors.textMuted,
        }}
      >
        {card.detailedState ?? "Delayed"}
        {card.inning ? ` · Inn ${card.inning}` : ""}
      </span>
    );
  }
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
        {/* Static dot: ~15 synchronized pulses in a scanned grid is noise; the
            Scorebug keeps the one pulsing on-air dot. */}
        <span
          data-slate-live-dot="true"
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
      className="bp-surface bp-pressable bp-pressable--soft bp-pressable--inset"
      style={{
        display: "block",
        textDecoration: "none",
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
                fontSize: 10,
                letterSpacing: typography.tracking.label,
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

export function SlateBoard({
  cards,
  filterLabel,
  onReset,
}: {
  cards: SlateCard[];
  /** The active status filter's label; absent when the view is unfiltered. */
  filterLabel?: string;
  /** Clears the filter; renders a "Show all games" control when present. */
  onReset?: () => void;
}) {
  if (cards.length === 0) {
    return (
      <div
        role="status"
        style={{
          display: "flex",
          flexDirection: "column",
          alignItems: "center",
          gap: 12,
          backgroundColor: colors.panel,
          border: `1px solid ${colors.rule}`,
          padding: 24,
          fontFamily: typography.fonts.body,
          fontSize: 14,
          color: colors.textMuted,
          textAlign: "center",
        }}
      >
        <span>
          {filterLabel
            ? `No ${filterLabel.toLowerCase()} games right now.`
            : "No games in this view."}
        </span>
        {filterLabel && onReset && (
          <button
            type="button"
            className="bp-button-chrome bp-pressable"
            onClick={onReset}
            style={{
              fontFamily: typography.fonts.mono,
              fontWeight: typography.weights.medium,
              fontSize: 12,
              letterSpacing: typography.tracking.label,
              textTransform: "uppercase",
              padding: "6px 14px",
              border: "none",
            }}
          >
            Show all games
          </button>
        )}
      </div>
    );
  }
  return (
    <div
      style={{
        display: "grid",
        gridTemplateColumns: "repeat(auto-fill, minmax(min(330px, 100%), 1fr))",
        gap: 14,
      }}
    >
      {cards.map((c) => (
        <SlateCardView key={c.gameId} card={c} />
      ))}
    </div>
  );
}
