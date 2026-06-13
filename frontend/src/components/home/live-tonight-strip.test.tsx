import { renderToStaticMarkup } from "react-dom/server";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it } from "vitest";

import type { GameSummary } from "../../api/games";

import { LiveTonightStrip } from "./live-tonight-strip";

function game(over: Partial<GameSummary>): GameSummary {
  return {
    gameId: 1,
    gameDate: "2026-06-13",
    homeTeam: "NYY",
    awayTeam: "BOS",
    homeScore: 0,
    awayScore: 0,
    inning: 1,
    status: "SCHEDULED",
    detailedState: "Scheduled",
    ...over,
  };
}

describe("LiveTonightStrip", () => {
  it("renders a chip per game linking to its live page, plus the all-games CTA", () => {
    const html = renderToStaticMarkup(
      <MemoryRouter>
        <LiveTonightStrip
          games={[game({ gameId: 7, awayTeam: "BOS", homeTeam: "NYY" })]}
        />
      </MemoryRouter>,
    );
    expect(html).toContain("BOS @ NYY");
    expect(html).toContain("Scheduled");
    expect(html).toContain('href="/games/7"');
    expect(html).toContain('href="/games"');
    expect(html).toContain("View all games");
  });

  it("shows an empty state when no games are scheduled", () => {
    const html = renderToStaticMarkup(
      <MemoryRouter>
        <LiveTonightStrip games={[]} />
      </MemoryRouter>,
    );
    expect(html).toContain("No games on the schedule today");
  });
});
