/**
 * The /games scoreboard's wording and order (SPEC-games-404 §4-§5). Expectations are literal
 * sentences: the oracle is written out here, never derived from the module under test.
 */
import { describe, expect, it } from "vitest";

import type { SlateCard } from "../../api/slate-view";

import {
  boardCounts,
  boardHeadline,
  finalMeta,
  firstPitchWindow,
  orderForBoard,
  stateLine,
} from "./slate-lines";

function card(o: Partial<SlateCard> = {}): SlateCard {
  return {
    gameId: 1,
    awayTeam: "NYY",
    homeTeam: "DET",
    status: "scheduled",
    paused: false,
    postponed: false,
    delayedStart: false,
    awayScore: null,
    homeScore: null,
    inning: null,
    detailedState: null,
    firstPitchEt: "7:10 PM ET",
    firstPitchUtc: "2026-06-13T23:10:00Z",
    leanLabel: "Pitching duel",
    battleScore: 8.6,
    away: null,
    home: null,
    ...o,
  };
}

describe("stateLine", () => {
  it("prints the inning for a game under way, in words and without a dot", () => {
    expect(stateLine(card({ status: "live", inning: 7 }))).toBe("Inning 7");
    expect(stateLine(card({ status: "live", inning: null }))).toBe(
      "In progress",
    );
  });

  it("names what halted a paused game", () => {
    expect(
      stateLine(
        card({
          status: "live",
          paused: true,
          inning: 4,
          detailedState: "Delayed: Rain",
        }),
      ),
    ).toBe("Rain delay, inning 4");
    expect(
      stateLine(
        card({
          status: "live",
          paused: true,
          inning: 6,
          detailedState: "Suspended: Rain",
        }),
      ),
    ).toBe("Suspended: Rain, inning 6");
    expect(
      stateLine(
        card({ status: "live", paused: true, inning: 2, detailedState: null }),
      ),
    ).toBe("Delayed, inning 2");
  });

  it("prints first pitch for a scheduled game, and says when the start is delayed", () => {
    expect(stateLine(card())).toBe("7:10 PM ET");
    expect(stateLine(card({ firstPitchEt: "TBD" }))).toBe("Time TBD");
    expect(stateLine(card({ firstPitchEt: null }))).toBe("Time TBD");
    expect(stateLine(card({ delayedStart: true }))).toBe(
      "7:10 PM ET, start delayed",
    );
  });

  it("says Postponed, extra innings, or Final for the final bucket", () => {
    expect(stateLine(card({ status: "final", postponed: true }))).toBe(
      "Postponed",
    );
    expect(stateLine(card({ status: "final", inning: 10 }))).toBe(
      "Final, 10 innings",
    );
    expect(stateLine(card({ status: "final", inning: 9 }))).toBe("Final");
  });
});

describe("boardHeadline", () => {
  const counts = (
    live: number,
    scheduled: number,
    final: number,
    postponed = 0,
  ) =>
    boardCounts([
      ...Array.from({ length: live }, () => card({ status: "live" })),
      ...Array.from({ length: scheduled }, () => card()),
      ...Array.from({ length: final - postponed }, () =>
        card({ status: "final" }),
      ),
      ...Array.from({ length: postponed }, () =>
        card({ status: "final", postponed: true }),
      ),
    ]);

  it("composes the counts into one sentence, postponed games excluded from final", () => {
    expect(boardHeadline(counts(4, 5, 4, 1))).toBe(
      "Four under way, five to come, three final.",
    );
  });

  it("omits empty parts", () => {
    expect(boardHeadline(counts(0, 9, 0))).toBe("Nine to come.");
    expect(boardHeadline(counts(0, 0, 3))).toBe("Three final.");
  });

  it("spells through ten and uses digits above", () => {
    expect(boardHeadline(counts(0, 10, 0))).toBe("Ten to come.");
    expect(boardHeadline(counts(0, 11, 0))).toBe("11 to come.");
  });

  it("says when every game is postponed, and when there are none", () => {
    expect(boardHeadline(counts(0, 0, 2, 2))).toBe("Every game postponed.");
    expect(boardHeadline(counts(0, 0, 0))).toBe("No games today.");
  });

  it("splits postponed games out of the Final column meta", () => {
    expect(finalMeta(counts(0, 0, 4, 1))).toBe("3 final, 1 postponed");
    expect(finalMeta(counts(0, 0, 2))).toBe("2 final");
  });
});

describe("orderForBoard", () => {
  it("puts still-to-come games in first-pitch order, unknown times last, ties stable", () => {
    const ordered = orderForBoard([
      card({ gameId: 1, status: "live" }),
      card({ gameId: 2, firstPitchUtc: "2026-06-14T02:15:00Z" }),
      card({ gameId: 3, firstPitchUtc: null }),
      card({ gameId: 4, firstPitchUtc: "2026-06-13T23:10:00Z" }),
      card({ gameId: 5, firstPitchUtc: "2026-06-13T23:10:00Z" }),
      card({ gameId: 6, status: "final" }),
    ]);
    expect(ordered.map((c) => c.gameId)).toEqual([1, 4, 5, 2, 3, 6]);
  });

  it("leaves live and final games in the order it was given", () => {
    const ordered = orderForBoard([
      card({ gameId: 9, status: "live" }),
      card({ gameId: 8, status: "live" }),
      card({ gameId: 7, status: "final" }),
      card({ gameId: 6, status: "final" }),
    ]);
    expect(ordered.map((c) => c.gameId)).toEqual([9, 8, 7, 6]);
  });
});

describe("firstPitchWindow", () => {
  it("spans the earliest to the latest known first pitch", () => {
    expect(
      firstPitchWindow([
        card({
          firstPitchUtc: "2026-06-14T02:15:00Z",
          firstPitchEt: "10:15 PM ET",
        }),
        card({
          firstPitchUtc: "2026-06-13T22:40:00Z",
          firstPitchEt: "6:40 PM ET",
        }),
        card({ firstPitchUtc: null, firstPitchEt: null }),
      ]),
    ).toBe("6:40 PM ET to 10:15 PM ET");
    expect(firstPitchWindow([card()])).toBe("7:10 PM ET");
    expect(firstPitchWindow([card({ firstPitchUtc: null })])).toBeNull();
  });
});
