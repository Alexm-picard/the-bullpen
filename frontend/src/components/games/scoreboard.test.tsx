/**
 * <Scoreboard> / <LineScore> (SPEC-games-404 §2, §6). Static renders; text assertions run on the
 * VISIBLE text (test-support/visible-text), structure assertions on the markup.
 */
import { renderToStaticMarkup } from "react-dom/server";
import { MemoryRouter } from "react-router";
import { describe, expect, it } from "vitest";

import type { SlateCard, SlateStatus } from "../../api/slate-view";
import { visibleText } from "../../test-support/visible-text";

import { Scoreboard } from "./scoreboard";
import { boardCounts } from "./slate-lines";

function card(o: Partial<SlateCard> = {}): SlateCard {
  return {
    gameId: 823370,
    awayTeam: "NYY",
    homeTeam: "DET",
    status: "live",
    paused: false,
    postponed: false,
    delayedStart: false,
    awayScore: 2,
    homeScore: 1,
    inning: 7,
    detailedState: "In Progress",
    firstPitchEt: "7:10 PM ET",
    firstPitchUtc: "2026-06-13T23:10:00Z",
    leanLabel: "Pitching duel",
    battleScore: 8.6,
    away: {
      playerId: 543037,
      name: "Gerrit Cole",
      team: "NYY",
      role: "pitcher",
    },
    home: {
      playerId: 669373,
      name: "Tarik Skubal",
      team: "DET",
      role: "pitcher",
    },
    ...o,
  };
}

function render(
  cards: SlateCard[],
  opts: { filter?: "all" | SlateStatus; showcase?: boolean } = {},
): string {
  return renderToStaticMarkup(
    <MemoryRouter>
      <Scoreboard
        cards={cards}
        counts={boardCounts(cards)}
        filter={opts.filter ?? "all"}
        showcase={opts.showcase ?? false}
        onReset={() => {}}
      />
    </MemoryRouter>,
  );
}

describe("Scoreboard", () => {
  it("lays out three status columns in reading order", () => {
    const text = visibleText(render([card()]));
    const at = (s: string) => text.indexOf(s);
    expect(at("In progress")).toBeGreaterThanOrEqual(0);
    expect(at("In progress")).toBeLessThan(at("Still to come"));
    expect(at("Still to come")).toBeLessThan(at("Final"));
  });

  it("links a live game to /games/{numeric id}, named for the pair it shows", () => {
    const html = render([card()]);
    expect(html).toContain('href="/games/823370"');
    expect(html).toContain('aria-label="Open game for NYY at DET"');
    expect(visibleText(html)).toContain("NYY at DET");
  });

  it("prints the state in words, the runs, and the matchup with its lean and rating", () => {
    const text = visibleText(render([card()]));
    expect(text).toContain("Inning 7");
    expect(text).toMatch(/NYY\s+2\s+DET\s+1/);
    expect(text).toContain("Gerrit Cole vs Tarik Skubal");
    expect(text).toContain("Pitching duel");
    expect(text).toContain("rating 8.6");
  });

  it("prints first pitch and no runs for a game still to come", () => {
    const html = render([
      card({
        status: "scheduled",
        awayScore: null,
        homeScore: null,
        inning: null,
      }),
    ]);
    const text = visibleText(html);
    expect(text).toContain("7:10 PM ET");
    expect(html).toMatch(/<td class="gm-r"><\/td>/);
  });

  it("prints no runs for a delayed start, though the feed sends 0-0", () => {
    const html = render([
      card({
        status: "scheduled",
        delayedStart: true,
        awayScore: 0,
        homeScore: 0,
        inning: null,
      }),
    ]);
    expect(visibleText(html)).toContain("7:10 PM ET, start delayed");
    expect(html).not.toContain('<td class="gm-r">0</td>');
  });

  it("weights the winner of a final and recedes the loser", () => {
    const html = render([
      card({ status: "final", awayScore: 5, homeScore: 7, inning: 10 }),
    ]);
    expect(visibleText(html)).toContain("Final, 10 innings");
    expect(html).toContain('<td class="gm-r" data-result="lost">5</td>');
    expect(html).toContain('<td class="gm-r" data-result="won">7</td>');
  });

  it("marks a halted game's state and names what halted it", () => {
    const html = render([
      card({ paused: true, inning: 4, detailedState: "Delayed: Rain" }),
    ]);
    expect(visibleText(html)).toContain("Rain delay, inning 4");
    expect(html).toContain('data-paused="true"');
  });

  it("prints a postponed game without runs and splits it out of the Final count", () => {
    const html = render([
      card({
        gameId: 2,
        status: "final",
        postponed: true,
        awayScore: null,
        homeScore: null,
        detailedState: "Postponed",
      }),
      card({
        gameId: 3,
        status: "final",
        awayScore: 6,
        homeScore: 3,
        inning: 9,
      }),
    ]);
    const text = visibleText(html);
    expect(text).toContain("Postponed");
    expect(text).toContain("1 final, 1 postponed");
    expect(html).toContain('data-postponed="true"');
  });

  it("says so when a column has no games", () => {
    const text = visibleText(render([card()]));
    expect(text).toContain("Every game has started.");
    expect(text).toContain("No finals yet.");
  });

  it("says when a game has no matchup read", () => {
    const text = visibleText(
      render([
        card({ away: null, home: null, leanLabel: null, battleScore: null }),
      ]),
    );
    expect(text).toContain("No matchup read for this game.");
  });

  it("draws team colour only as hidden marks beside the codes", () => {
    const html = render([card()]);
    const marks = html.match(/<span class="pl-mark" aria-hidden="true"/g) ?? [];
    expect(marks).toHaveLength(2);
  });

  it("renders showcase pairs as plain text, never a link", () => {
    const html = render([card()], { showcase: true });
    expect(html).not.toContain('href="/games/');
    expect(visibleText(html)).toContain("NYY at DET");
  });

  it("shows only the chosen column when filtered", () => {
    const text = visibleText(
      render([card(), card({ gameId: 9, status: "final", inning: 9 })], {
        filter: "final",
      }),
    );
    expect(text).toContain("Final");
    expect(text).not.toContain("In progress");
  });

  it("offers a way back when a filter matches nothing", () => {
    const html = render([card()], { filter: "scheduled" });
    const text = visibleText(html);
    expect(text).toContain("No scheduled games right now.");
    expect(text).toContain("Show all games");
    expect(html).toContain('role="status"');
  });
});
