/**
 * Pure view-mappers for the home matchup surfaces (Phase 4). Turns the live
 * {@link MatchupSummary} rows from GET /v1/matchups/today into the lean-aware
 * shapes the Featured panel + Tonight's board render, with no React or fetch -
 * so the lean labelling, the ET first-pitch formatting, and the featured /
 * board split are unit-testable in isolation.
 *
 * The split mirrors the endpoint contract (best battle first): row 0 is the
 * Featured matchup; the rest are the board. The two lean-driven people are
 * already chosen by the backend classifier (two pitchers for a pitching lean,
 * two hitters for a hitters lean, the stronger side of each for a split), so
 * the frontend only labels and renders what it is handed.
 */

import type { MatchupSummary } from "./matchups";

/** One side of a matchup - the person the lean put forward, with team context. */
export type MatchupSide = {
  playerId: number;
  name: string;
  team: string;
  role: string; // "pitcher" | "hitter"
};

export type FeaturedMatchupView = {
  gameId: number;
  away: MatchupSide;
  home: MatchupSide;
  leanLabel: string;
  firstPitchEt: string;
  battleScore: number;
  /** "default" (pitcher-vs-pitcher morning pass) | "lineup" (re-classified). */
  stage: string;
};

export type BoardRowView = {
  gameId: number;
  awayTeam: string;
  homeTeam: string;
  firstPitchEt: string;
  away: MatchupSide;
  home: MatchupSide;
  leanLabel: string;
  battleScore: number;
  stage: string;
};

const ET_TIME = new Intl.DateTimeFormat("en-US", {
  hour: "numeric",
  minute: "2-digit",
  hour12: true,
  timeZone: "America/New_York",
});

/** Human label for the classifier lean. */
export function leanLabel(lean: string): string {
  switch (lean) {
    case "pitching":
      return "Pitching Duel";
    case "hitters":
      return "Hitters Duel";
    case "mixed":
      return "Split Lean";
    default:
      return "Matchup";
  }
}

/** First-pitch time in ET ("7:10 PM ET"), or "TBD" when the schedule had no time. */
export function firstPitchEt(iso: string | null): string {
  if (!iso) {
    return "TBD";
  }
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) {
    return "TBD";
  }
  return `${ET_TIME.format(d)} ET`;
}

function awaySide(m: MatchupSummary): MatchupSide {
  return {
    playerId: m.awayPlayerId,
    name: m.awayPlayerName,
    team: m.awayTeam,
    role: m.awayRole,
  };
}

function homeSide(m: MatchupSummary): MatchupSide {
  return {
    playerId: m.homePlayerId,
    name: m.homePlayerName,
    team: m.homeTeam,
    role: m.homeRole,
  };
}

export function toFeaturedView(m: MatchupSummary): FeaturedMatchupView {
  return {
    gameId: m.gameId,
    away: awaySide(m),
    home: homeSide(m),
    leanLabel: leanLabel(m.lean),
    firstPitchEt: firstPitchEt(m.gameTimeUtc),
    battleScore: m.battleScore,
    stage: m.stage,
  };
}

export function toBoardRow(m: MatchupSummary): BoardRowView {
  return {
    gameId: m.gameId,
    awayTeam: m.awayTeam,
    homeTeam: m.homeTeam,
    firstPitchEt: firstPitchEt(m.gameTimeUtc),
    away: awaySide(m),
    home: homeSide(m),
    leanLabel: leanLabel(m.lean),
    battleScore: m.battleScore,
    stage: m.stage,
  };
}

/**
 * Split the best-battle-first slate into the Featured highlight (row 0) and the
 * board (the rest). Empty in -> no featured, empty board.
 */
export function splitSlate(matchups: MatchupSummary[]): {
  featured: FeaturedMatchupView | null;
  board: BoardRowView[];
} {
  const [first, ...rest] = matchups;
  if (!first) {
    return { featured: null, board: [] };
  }
  return {
    featured: toFeaturedView(first),
    board: rest.map(toBoardRow),
  };
}
