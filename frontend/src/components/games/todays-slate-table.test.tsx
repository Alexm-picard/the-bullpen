/**
 * <TodaysSlateTable> - the data-driven states the page smoke test cannot
 * reach through a live query: populated rows with NUMERIC /games/{id} hrefs
 * (the FE-H1 contract) and the first-class empty state.
 */
import { renderToStaticMarkup } from "react-dom/server";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it } from "vitest";

import type { GameSummary } from "../../api/games";

import { TodaysSlateTable } from "./todays-slate-table";

function render(ui: React.ReactElement): string {
  return renderToStaticMarkup(<MemoryRouter>{ui}</MemoryRouter>);
}

function game(overrides: Partial<GameSummary> = {}): GameSummary {
  return {
    gameId: 745804,
    gameDate: "2026-06-11",
    homeTeam: "BOS",
    awayTeam: "BAL",
    homeScore: 2,
    awayScore: 1,
    inning: 6,
    status: "IN_PROGRESS",
    detailedState: "In Progress",
    ...overrides,
  };
}

describe("TodaysSlateTable", () => {
  it("renders a row per game with matchup, status, score, and inning", () => {
    const html = render(
      <TodaysSlateTable
        games={[
          game(),
          game({
            gameId: 745811,
            awayTeam: "NYY",
            homeTeam: "DET",
            inning: 1,
            detailedState: "Warmup",
          }),
        ]}
      />,
    );
    expect(html).toContain("BAL");
    expect(html).toContain("BOS");
    expect(html).toContain("In Progress");
    expect(html).toContain("Warmup");
    expect(html).toContain("NYY");
  });

  it("links each row to /games/{gameId} with the NUMERIC gamePk (FE-H1)", () => {
    const html = render(<TodaysSlateTable games={[game()]} />);
    expect(html).toContain('href="/games/745804"');
    // No slug-shaped hrefs survive anywhere in the row.
    expect(html).not.toMatch(/href="\/games\/[a-z]/);
  });

  it("renders the first-class empty state when the API returns []", () => {
    const html = render(<TodaysSlateTable games={[]} />);
    expect(html).toContain("No games yet today");
    expect(html).toContain("first status transition");
    expect(html).not.toContain("<table");
  });

  it("renders an em-dash inning placeholder before the first inning is known", () => {
    const html = render(<TodaysSlateTable games={[game({ inning: 0 })]} />);
    expect(html).toContain("—");
  });
});
