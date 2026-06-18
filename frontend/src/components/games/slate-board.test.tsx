/**
 * <SlateBoard> - static-render coverage for the /games card grid: live badge,
 * final winner/loser emphasis, lean + battle enrichment, numeric hrefs, and the
 * first-class empty state.
 */
import { renderToStaticMarkup } from "react-dom/server";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it } from "vitest";

import type { SlateCard } from "../../api/slate-view";
import { teamColor } from "../../design/teamColors";

import { SlateBoard } from "./slate-board";

function render(ui: React.ReactElement): string {
  return renderToStaticMarkup(<MemoryRouter>{ui}</MemoryRouter>);
}

function card(o: Partial<SlateCard> = {}): SlateCard {
  return {
    gameId: 823370,
    awayTeam: "NYY",
    homeTeam: "DET",
    status: "live",
    awayScore: 2,
    homeScore: 1,
    inning: 5,
    detailedState: "In Progress",
    firstPitchEt: "7:10 PM ET",
    leanLabel: "Pitching Duel",
    battleScore: 8.6,
    away: { playerId: 3, name: "Gerrit Cole", team: "NYY", role: "pitcher" },
    home: { playerId: 2, name: "Tarik Skubal", team: "DET", role: "pitcher" },
    ...o,
  };
}

describe("SlateBoard", () => {
  it("renders a live card with the gold on-air dot, inning, lean, and battle", () => {
    const html = render(<SlateBoard cards={[card()]} />);
    expect(html).toContain("broadcast-live-dot");
    expect(html).toContain("Inn 5");
    expect(html).toContain("Pitching Duel");
    expect(html).toContain("8.6");
    expect(html).toContain("Gerrit Cole vs Tarik Skubal");
    expect(html).toContain(teamColor("NYY"));
  });

  it("links each card to /games/{gameId} (numeric)", () => {
    const html = render(<SlateBoard cards={[card()]} />);
    expect(html).toContain('href="/games/823370"');
    expect(html).toContain("Open game for NYY at DET");
  });

  it("shows the first-pitch time for a scheduled game and no score", () => {
    const html = render(
      <SlateBoard
        cards={[
          card({
            status: "scheduled",
            awayScore: null,
            homeScore: null,
            inning: null,
          }),
        ]}
      />,
    );
    expect(html).toContain("7:10 PM ET");
    expect(html).not.toContain("broadcast-live-dot");
  });

  it("renders a final with both scores and the final state (no live dot)", () => {
    const html = render(
      <SlateBoard
        cards={[
          card({
            status: "final",
            detailedState: "Final",
            awayScore: 6,
            homeScore: 3,
          }),
        ]}
      />,
    );
    expect(html).toContain("Final");
    expect(html).toContain(">6<");
    expect(html).toContain(">3<");
    expect(html).not.toContain("broadcast-live-dot");
  });

  it("renders the first-class empty state when no cards match the filter", () => {
    const html = render(<SlateBoard cards={[]} />);
    expect(html).toContain("No games in this view");
    expect(html).not.toContain("href=");
  });
});
