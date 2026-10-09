/**
 * <SlateBoard> - static-render coverage for the /games card grid: live badge,
 * final winner/loser emphasis, lean + battle enrichment, numeric hrefs, and the
 * first-class empty state.
 */
import { renderToStaticMarkup } from "react-dom/server";
import { MemoryRouter } from "react-router";
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
    paused: false,
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
  it("renders a live card with the static gold dot, inning, lean, and battle", () => {
    const html = render(<SlateBoard cards={[card()]} />);
    expect(html).toContain('data-slate-live-dot="true"');
    // The grid does not pulse: the Scorebug owns the one on-air pulse.
    expect(html).not.toContain("broadcast-live-dot");
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
    expect(html).not.toContain("data-slate-live-dot");
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
    expect(html).not.toContain("data-slate-live-dot");
  });

  it("renders a delayed game's state (no live dot, no first-pitch time)", () => {
    const html = render(
      <SlateBoard
        cards={[
          card({ paused: true, detailedState: "Delayed: Rain", inning: 5 }),
        ]}
      />,
    );
    expect(html).toContain("Delayed: Rain");
    expect(html).toContain("Inn 5");
    expect(html).not.toContain("data-slate-live-dot");
    expect(html).not.toContain("7:10 PM ET");
    expect(html).not.toContain(">Live");
  });

  it("falls back to 'Delayed' when a paused game has no detailed state", () => {
    const html = render(
      <SlateBoard cards={[card({ paused: true, detailedState: null })]} />,
    );
    expect(html).toContain("Delayed");
    expect(html).not.toContain("data-slate-live-dot");
  });

  it("renders a postponed game as its state, never a first-pitch time", () => {
    const html = render(
      <SlateBoard
        cards={[
          card({
            status: "final",
            detailedState: "Postponed: Rain",
            awayScore: null,
            homeScore: null,
            inning: null,
          }),
        ]}
      />,
    );
    expect(html).toContain("Postponed: Rain");
    expect(html).not.toContain("7:10 PM ET");
  });

  it("gives each card the pressable surface classes (state lives in CSS)", () => {
    const html = render(<SlateBoard cards={[card()]} />);
    expect(html).toContain(
      'class="bp-surface bp-pressable bp-pressable--soft bp-pressable--inset"',
    );
    // An inline background would outrank the surface hover rule.
    expect(html).not.toMatch(/<a[^>]*style="[^"]*background-color/);
  });

  it("renders the first-class empty state when no cards match the filter", () => {
    const html = render(<SlateBoard cards={[]} />);
    expect(html).toContain("No games in this view");
    expect(html).not.toContain("href=");
    expect(html).not.toContain("Show all games");
  });

  it("names the active filter and offers a reset when a filtered view is empty", () => {
    const html = render(
      <SlateBoard cards={[]} filterLabel="Live" onReset={() => {}} />,
    );
    expect(html).toContain("No live games right now.");
    expect(html).toContain("Show all games");
    expect(html).not.toContain("No games in this view");
  });
});
