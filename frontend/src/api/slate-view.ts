/**
 * Pure merge-mapper for the /games slate board. Joins the day's matchups
 * (GET /v1/matchups/today - the full slate spine: lean, battle score, the two
 * featured people, first pitch) with the live game rows (GET /v1/games/today -
 * scores + status, present only once a game has started) by `gameId`.
 *
 * Matchups are the spine because they cover the whole day; a game overlays live
 * state when it exists. A game with no matchup row still lands on the slate
 * (defensive: the slate should never hide a game that is actually being played).
 *
 * No React, no fetch - so the status derivation, the join, and the ordering are
 * unit-testable in isolation (same split as matchups-view).
 */

import type { GameSummary } from "./games";
import type { MatchupSummary } from "./matchups";
import { firstPitchEt, leanLabel, type MatchupSide } from "./matchups-view";

export type SlateStatus = "live" | "scheduled" | "final";

export type SlateCard = {
  gameId: number;
  awayTeam: string;
  homeTeam: string;
  status: SlateStatus;
  /** Scores are null until the game has live/final state. */
  awayScore: number | null;
  homeScore: number | null;
  inning: number | null;
  /** GameSummary.detailedState (e.g. "In Progress", "Final") when live/final. */
  detailedState: string | null;
  /** First-pitch ET label for scheduled games. */
  firstPitchEt: string | null;
  /** Matchup enrichment (null for a game with no matchup row). */
  leanLabel: string | null;
  battleScore: number | null;
  away: MatchupSide | null;
  home: MatchupSide | null;
};

const LIVE_STATUSES = new Set(["IN_PROGRESS", "MID_INNING"]);
const FINAL_STATUSES = new Set(["COMPLETED", "GAME_OVER", "FINAL"]);

/** Collapse the backend GameStatus enum into the board's three coarse buckets. */
export function slateStatus(status: string | undefined): SlateStatus {
  if (status && LIVE_STATUSES.has(status)) {
    return "live";
  }
  if (status && FINAL_STATUSES.has(status)) {
    return "final";
  }
  return "scheduled";
}

function matchupSide(
  id: number,
  name: string,
  team: string,
  role: string,
): MatchupSide {
  return { playerId: id, name, team, role };
}

function fromMatchup(m: MatchupSummary, g: GameSummary | undefined): SlateCard {
  return {
    gameId: m.gameId,
    awayTeam: m.awayTeam,
    homeTeam: m.homeTeam,
    status: slateStatus(g?.status),
    awayScore: g ? g.awayScore : null,
    homeScore: g ? g.homeScore : null,
    inning: g && g.inning > 0 ? g.inning : null,
    detailedState: g ? g.detailedState : null,
    firstPitchEt: firstPitchEt(m.gameTimeUtc),
    leanLabel: leanLabel(m.lean),
    battleScore: m.battleScore,
    away: matchupSide(m.awayPlayerId, m.awayPlayerName, m.awayTeam, m.awayRole),
    home: matchupSide(m.homePlayerId, m.homePlayerName, m.homeTeam, m.homeRole),
  };
}

function fromGameOnly(g: GameSummary): SlateCard {
  return {
    gameId: g.gameId,
    awayTeam: g.awayTeam,
    homeTeam: g.homeTeam,
    status: slateStatus(g.status),
    awayScore: g.awayScore,
    homeScore: g.homeScore,
    inning: g.inning > 0 ? g.inning : null,
    detailedState: g.detailedState,
    firstPitchEt: null,
    leanLabel: null,
    battleScore: null,
    away: null,
    home: null,
  };
}

const STATUS_ORDER: Record<SlateStatus, number> = {
  live: 0,
  scheduled: 1,
  final: 2,
};

/**
 * Merge matchups + games into the ordered slate: live first, then scheduled,
 * then final; best battle first within a bucket.
 */
export function mergeSlate(
  matchups: MatchupSummary[],
  games: GameSummary[],
): SlateCard[] {
  const gameById = new Map(games.map((g) => [g.gameId, g]));
  const seen = new Set<number>();
  const cards: SlateCard[] = [];

  for (const m of matchups) {
    seen.add(m.gameId);
    cards.push(fromMatchup(m, gameById.get(m.gameId)));
  }
  for (const g of games) {
    if (!seen.has(g.gameId)) {
      cards.push(fromGameOnly(g));
    }
  }

  return cards.sort(
    (a, b) =>
      STATUS_ORDER[a.status] - STATUS_ORDER[b.status] ||
      (b.battleScore ?? 0) - (a.battleScore ?? 0),
  );
}

/** Count of each status bucket, for the slate header tag. */
export function slateCounts(cards: SlateCard[]): Record<SlateStatus, number> {
  return cards.reduce(
    (acc, c) => {
      acc[c.status] += 1;
      return acc;
    },
    { live: 0, scheduled: 0, final: 0 } as Record<SlateStatus, number>,
  );
}
