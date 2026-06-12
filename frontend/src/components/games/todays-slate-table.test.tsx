/**
 * <TodaysSlateTable> on the broadcast identity (redesign PR-3) - the
 * data-driven states plus the contracts that predate the redesign: numeric
 * /games/{id} hrefs (FE-H1) and the exact first-class empty-state copy.
 */
import { renderToStaticMarkup } from "react-dom/server";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it } from "vitest";

import type { GameSummary } from "../../api/games";
import { teamColor } from "../../design/teamColors";

import { TodaysSlateTable } from "./todays-slate-table";

function render(ui: React.ReactElement): string {
  return renderToStaticMarkup(<MemoryRouter>{ui}</MemoryRouter>);
}

function game(overrides: Partial<GameSummary> = {}): GameSummary {
  return {
    gameId: 745804,
    gameDate: "2026-06-12",
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

describe("TodaysSlateTable (broadcast)", () => {
  it("renders a strip per game with teams, scores, state, and team-color bars", () => {
    const html = render(
      <TodaysSlateTable
        games={[
          game(),
          game({
            gameId: 745811,
            awayTeam: "NYY",
            homeTeam: "DET",
            status: "WARMUP",
            detailedState: "Warmup",
            inning: 1,
          }),
        ]}
      />,
    );
    expect(html).toContain("BAL");
    expect(html).toContain("BOS");
    expect(html).toContain("In Progress");
    expect(html).toContain("Warmup");
    expect(html).toContain(teamColor("BAL"));
    expect(html).toContain(teamColor("DET"));
  });

  it("links each strip to /games/{gameId} with the NUMERIC gamePk (FE-H1)", () => {
    const html = render(<TodaysSlateTable games={[game()]} />);
    expect(html).toContain('href="/games/745804"');
    expect(html).toContain("Open live view for BAL at BOS");
    expect(html).not.toMatch(/href="\/games\/[a-z]/);
  });

  it("shows the gold on-air dot only for live games", () => {
    const live = render(<TodaysSlateTable games={[game()]} />);
    expect(live).toContain("broadcast-live-dot");
    expect(live).toContain("LIVE");

    const final = render(
      <TodaysSlateTable
        games={[game({ status: "COMPLETED", detailedState: "Final" })]}
      />,
    );
    expect(final).not.toContain("broadcast-live-dot");
  });

  it("renders the first-class empty state when the API returns []", () => {
    const html = render(<TodaysSlateTable games={[]} />);
    expect(html).toContain("No games yet today");
    expect(html).toContain("first status transition");
    expect(html).not.toContain("href=");
  });

  it("renders an em-dash inning placeholder before the first inning is known", () => {
    const html = render(<TodaysSlateTable games={[game({ inning: 0 })]} />);
    expect(html).toContain("—");
  });
});
