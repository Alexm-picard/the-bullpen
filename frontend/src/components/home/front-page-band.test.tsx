/**
 * The front page's slate column (SPEC-home §5). First test for the band: it pins the state line
 * for a postponed game, which used to read "Final 0-0" because the feed sends 0-0 for a game that
 * was never played (SPEC-games-404 §5.2; mergeSlate now nulls those runs).
 */
import { renderToStaticMarkup } from "react-dom/server";
import { MemoryRouter } from "react-router";
import { describe, expect, it } from "vitest";

import type { GameSummary } from "../../api/games";
import type { MatchupSummary } from "../../api/matchups";
import { mergeSlate } from "../../api/slate-view";
import { visibleText } from "../../test-support/visible-text";

import { SlateColumn } from "./front-page-band";

const MATCHUP: MatchupSummary = {
  gameId: 823358,
  gameDate: "2026-06-13",
  gameTimeUtc: "2026-06-13T22:50:00Z",
  homeTeam: "TB",
  awayTeam: "BAL",
  lean: "pitching",
  homePlayerId: 1,
  homePlayerName: "Shane McClanahan",
  homeRole: "pitcher",
  awayPlayerId: 2,
  awayPlayerName: "Zach Eflin",
  awayRole: "pitcher",
  battleScore: 3.8,
  stage: "default",
};

function game(o: Partial<GameSummary>): GameSummary {
  return {
    gameId: 823358,
    gameDate: "2026-06-13",
    homeTeam: "TB",
    awayTeam: "BAL",
    homeScore: 0,
    awayScore: 0,
    inning: 0,
    status: "POSTPONED",
    detailedState: "Postponed",
    currentMatchup: null,
    mostRecentBattedBall: null,
    ...o,
  };
}

function bandText(g: GameSummary): string {
  return visibleText(
    renderToStaticMarkup(
      <MemoryRouter>
        <SlateColumn cards={mergeSlate([MATCHUP], [g])} source="live" />
      </MemoryRouter>,
    ),
  );
}

describe("SlateColumn (front page band)", () => {
  it("reads a postponed game as Postponed, never as a 0-0 final", () => {
    const text = bandText(game({}));
    expect(text).toContain("Postponed");
    expect(text).not.toContain("Final 0-0");
  });

  it("still prints the score of a game that was played", () => {
    const text = bandText(
      game({
        status: "COMPLETED",
        detailedState: "Final",
        awayScore: 6,
        homeScore: 3,
        inning: 9,
      }),
    );
    expect(text).toContain("Final 6-3");
  });
});
