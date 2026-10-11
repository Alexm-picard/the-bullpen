/**
 * <Scoreboard> - the /games board ([195], SPEC-games-404 §2): three status columns (in progress,
 * still to come, final) of small line scores, separated by column rules, never boxed. Pure
 * presentation: the page owns the queries, the posture and the order (orderForBoard).
 *
 * Data in ink; the accent is the team-pair link only. Team colour appears ONLY as the 7px mark
 * beside the code (owner, 2026-10-11, extending SPEC-players' index-only rule to /games). On a
 * final the winner carries weight and the loser recedes - weight and ink, never colour. A
 * postponed game prints no runs. Showcase rows are plain text: their fixture ids do not resolve
 * on the game page, and a link to /games from /games is a link to here (SPEC §6.3).
 */
import { VisuallyHidden } from "@mantine/core";
import { Link } from "react-router";

import type { SlateCard, SlateStatus } from "../../api/slate-view";
import { teamColor } from "../../design/teamColors";

import { finalMeta, stateLine, type BoardCounts } from "./slate-lines";

const RATING = new Intl.NumberFormat("en-US", {
  minimumFractionDigits: 1,
  maximumFractionDigits: 1,
});

type Column = {
  status: SlateStatus;
  title: string;
  /** Shown in place of the line scores when the column is empty. */
  none: string;
  meta: (counts: BoardCounts) => string;
};

const COLUMNS: readonly Column[] = [
  {
    status: "live",
    title: "In progress",
    none: "None in progress yet.",
    meta: (c) => `${c.live} ${c.live === 1 ? "game" : "games"}`,
  },
  {
    status: "scheduled",
    title: "Still to come",
    none: "Every game has started.",
    meta: () => "by first pitch",
  },
  {
    status: "final",
    title: "Final",
    none: "No finals yet.",
    meta: finalMeta,
  },
];

/** What a filtered view with no games says ("No live games right now."). */
const FILTER_NOUN: Record<SlateStatus, string> = {
  live: "live",
  scheduled: "scheduled",
  final: "final",
};

function result(card: SlateCard, side: "away" | "home"): string | undefined {
  if (card.status !== "final" || card.postponed) return undefined;
  const mine = side === "away" ? card.awayScore : card.homeScore;
  const theirs = side === "away" ? card.homeScore : card.awayScore;
  if (mine == null || theirs == null || mine === theirs) return undefined;
  return mine > theirs ? "won" : "lost";
}

function TeamLine({ card, side }: { card: SlateCard; side: "away" | "home" }) {
  const team = side === "away" ? card.awayTeam : card.homeTeam;
  // A game still to come has no runs, even when the feed sends 0-0 (a delayed start does).
  const runs =
    card.status === "scheduled"
      ? null
      : side === "away"
        ? card.awayScore
        : card.homeScore;
  return (
    <tr>
      <th scope="row" className="gm-code">
        <span
          className="pl-mark"
          aria-hidden="true"
          style={{ backgroundColor: teamColor(team) }}
        />
        {team}
      </th>
      <td className="gm-r" data-result={result(card, side)}>
        {runs ?? ""}
      </td>
    </tr>
  );
}

export function LineScore({
  card,
  showcase,
}: {
  card: SlateCard;
  showcase: boolean;
}) {
  const pair = `${card.awayTeam} at ${card.homeTeam}`;
  return (
    <article
      className="gm-box"
      aria-label={pair}
      data-postponed={card.postponed ? "true" : undefined}
    >
      <p className="gm-box__head">
        {showcase ? (
          <span className="gm-pair">{pair}</span>
        ) : (
          <Link
            className="ed-link"
            to={`/games/${card.gameId}`}
            aria-label={`Open game for ${pair}`}
          >
            {pair}
          </Link>
        )}
        <span
          className="gm-box__st"
          data-paused={
            card.status === "live" && card.paused ? "true" : undefined
          }
        >
          {stateLine(card)}
        </span>
      </p>
      <table className="gm-ls">
        <tbody>
          <TeamLine card={card} side="away" />
          <TeamLine card={card} side="home" />
        </tbody>
      </table>
      {card.away && card.home ? (
        <p className="gm-box__who">
          {card.away.name} vs {card.home.name}
          <span className="gm-box__meta">
            <span className="gm-box__sep">, </span>
            {card.leanLabel ? (
              <span className="gm-box__lean">{card.leanLabel}</span>
            ) : null}
            {card.leanLabel && card.battleScore != null ? ", " : null}
            {card.battleScore != null
              ? `rating ${RATING.format(card.battleScore)}`
              : null}
          </span>
        </p>
      ) : (
        <p className="gm-box__who" data-none="true">
          No matchup read for this game.
        </p>
      )}
    </article>
  );
}

function BoardColumn({
  column,
  cards,
  counts,
  showcase,
}: {
  column: Column;
  cards: readonly SlateCard[];
  counts: BoardCounts;
  showcase: boolean;
}) {
  const id = `gm-col-${column.status}`;
  return (
    <section
      className="gm-col"
      aria-labelledby={id}
      data-empty={cards.length === 0 ? "true" : undefined}
    >
      <div className="gm-col__head">
        <h2 id={id}>{column.title}</h2>
        <span>{column.meta(counts)}</span>
      </div>
      {cards.length === 0 ? (
        <p className="gm-none">{column.none}</p>
      ) : (
        <div className="gm-boxes">
          {cards.map((c) => (
            <LineScore key={c.gameId} card={c} showcase={showcase} />
          ))}
        </div>
      )}
    </section>
  );
}

export function Scoreboard({
  cards,
  counts,
  filter,
  showcase,
  onReset,
}: {
  /** Already in board order (orderForBoard). */
  cards: readonly SlateCard[];
  counts: BoardCounts;
  filter: "all" | SlateStatus;
  showcase: boolean;
  onReset: () => void;
}) {
  if (filter !== "all") {
    const column = COLUMNS.find((c) => c.status === filter)!;
    const shown = cards.filter((c) => c.status === filter);
    if (shown.length === 0) {
      return (
        <div className="gm-b" data-filter={filter}>
          <div className="gm-col" role="status">
            <p className="gm-none">No {FILTER_NOUN[filter]} games right now.</p>
            <button type="button" className="pl-facet" onClick={onReset}>
              Show all games
            </button>
          </div>
        </div>
      );
    }
    return (
      <div className="gm-b" data-filter={filter}>
        <BoardColumn
          column={column}
          cards={shown}
          counts={counts}
          showcase={showcase}
        />
      </div>
    );
  }
  return (
    <div className="gm-b">
      {COLUMNS.map((column) => (
        <BoardColumn
          key={column.status}
          column={column}
          cards={cards.filter((c) => c.status === column.status)}
          counts={counts}
          showcase={showcase}
        />
      ))}
    </div>
  );
}

/** Static, pre-sized loading shell: three columns of two line-score blocks (no shimmer). */
export function ScoreboardShell() {
  return (
    <div className="gm-b" role="status" aria-busy="true">
      <VisuallyHidden>Loading today&rsquo;s games</VisuallyHidden>
      {COLUMNS.map((column) => (
        <div className="gm-col" key={column.status} aria-hidden="true">
          <div className="gm-col__head">
            <span
              className="ed-shell"
              style={{ display: "block", height: "1.5rem", width: "8rem" }}
            />
          </div>
          {[0, 1].map((i) => (
            <div className="gm-box" key={i}>
              <span
                className="ed-shell"
                style={{ display: "block", height: "7.5rem" }}
              />
            </div>
          ))}
        </div>
      ))}
    </div>
  );
}
