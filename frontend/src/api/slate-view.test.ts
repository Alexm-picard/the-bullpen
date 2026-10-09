/**
 * mergeSlate / slateStatus / slateCounts - the /games board's pure join.
 */
import { describe, expect, it } from "vitest";

import type { GameSummary } from "./games";
import type { MatchupSummary } from "./matchups";
import {
  mergeSlate,
  slateCounts,
  slatePaused,
  slateStatus,
} from "./slate-view";

function matchup(o: Partial<MatchupSummary> = {}): MatchupSummary {
  return {
    gameId: 1,
    gameDate: "2026-06-13",
    gameTimeUtc: "2026-06-13T23:10:00Z",
    homeTeam: "DET",
    awayTeam: "NYY",
    lean: "pitching",
    homePlayerId: 2,
    homePlayerName: "Tarik Skubal",
    homeRole: "pitcher",
    awayPlayerId: 3,
    awayPlayerName: "Gerrit Cole",
    awayRole: "pitcher",
    battleScore: 8.6,
    stage: "default",
    ...o,
  };
}

function game(o: Partial<GameSummary> = {}): GameSummary {
  return {
    gameId: 1,
    gameDate: "2026-06-13",
    homeTeam: "DET",
    awayTeam: "NYY",
    homeScore: 1,
    awayScore: 2,
    inning: 5,
    status: "IN_PROGRESS",
    detailedState: "In Progress",
    currentMatchup: null,
    mostRecentBattedBall: null,
    ...o,
  };
}

describe("slateStatus", () => {
  it("buckets the GameStatus enum into live / final / scheduled", () => {
    expect(slateStatus("IN_PROGRESS")).toBe("live");
    expect(slateStatus("MID_INNING")).toBe("live");
    expect(slateStatus("COMPLETED")).toBe("final");
    expect(slateStatus("SCHEDULED")).toBe("scheduled");
    expect(slateStatus("WARMUP")).toBe("scheduled");
    expect(slateStatus(undefined)).toBe("scheduled");
  });

  it("keeps a started DELAYED game in the live bucket, flagged paused", () => {
    expect(slateStatus("DELAYED", 5)).toBe("live");
    expect(slatePaused("DELAYED")).toBe(true);
    const c = mergeSlate(
      [matchup({ gameId: 5 })],
      [
        game({
          gameId: 5,
          status: "DELAYED",
          inning: 5,
          detailedState: "Delayed: Rain",
        }),
      ],
    )[0]!;
    expect(c.status).toBe("live");
    expect(c.paused).toBe(true);
    expect(c.detailedState).toBe("Delayed: Rain");
  });

  it("keeps a delay before first pitch in the scheduled bucket", () => {
    expect(slateStatus("DELAYED", 0)).toBe("scheduled");
    expect(slateStatus("DELAYED", null)).toBe("scheduled");
    expect(slateStatus("DELAYED")).toBe("scheduled");
  });

  it("keeps a SUSPENDED game in the live bucket, flagged paused", () => {
    expect(slateStatus("SUSPENDED")).toBe("live");
    expect(slatePaused("SUSPENDED")).toBe(true);
    const c = mergeSlate([], [game({ gameId: 6, status: "SUSPENDED" })])[0]!;
    expect(c.status).toBe("live");
    expect(c.paused).toBe(true);
  });

  it("buckets a POSTPONED game as final (never a first-pitch time slot)", () => {
    expect(slateStatus("POSTPONED")).toBe("final");
    expect(slatePaused("POSTPONED")).toBe(false);
    const c = mergeSlate(
      [matchup({ gameId: 8 })],
      [
        game({
          gameId: 8,
          status: "POSTPONED",
          detailedState: "Postponed: Rain",
        }),
      ],
    )[0]!;
    expect(c.status).toBe("final");
    expect(c.paused).toBe(false);
  });

  it("does not flag an in-progress or scheduled game as paused", () => {
    expect(slatePaused("IN_PROGRESS")).toBe(false);
    expect(slatePaused(undefined)).toBe(false);
    expect(mergeSlate([matchup()], [])[0]!.paused).toBe(false);
  });
});

describe("mergeSlate", () => {
  it("overlays live game state onto the matchup spine by gameId", () => {
    const cards = mergeSlate([matchup({ gameId: 1 })], [game({ gameId: 1 })]);
    expect(cards).toHaveLength(1);
    const c = cards[0]!;
    expect(c.status).toBe("live");
    expect(c.awayScore).toBe(2);
    expect(c.homeScore).toBe(1);
    expect(c.inning).toBe(5);
    // matchup enrichment survives the merge
    expect(c.leanLabel).toBe("Pitching Duel");
    expect(c.battleScore).toBe(8.6);
    expect(c.away?.name).toBe("Gerrit Cole");
  });

  it("keeps a matchup with no game row as a SCHEDULED card (no scores)", () => {
    const cards = mergeSlate([matchup({ gameId: 7 })], []);
    expect(cards[0]!.status).toBe("scheduled");
    expect(cards[0]!.awayScore).toBeNull();
    expect(cards[0]!.firstPitchEt).toContain("ET");
  });

  it("includes a game that has no matchup row (defensive)", () => {
    const cards = mergeSlate([], [game({ gameId: 99 })]);
    expect(cards).toHaveLength(1);
    expect(cards[0]!.gameId).toBe(99);
    expect(cards[0]!.leanLabel).toBeNull();
    expect(cards[0]!.status).toBe("live");
  });

  it("orders live first, then scheduled, then final; best battle first within", () => {
    const cards = mergeSlate(
      [
        matchup({ gameId: 1, battleScore: 4 }), // -> final (game below)
        matchup({ gameId: 2, battleScore: 9 }), // -> scheduled (no game)
        matchup({ gameId: 3, battleScore: 5 }), // -> live (game below)
        matchup({ gameId: 4, battleScore: 8 }), // -> live (game below)
      ],
      [
        game({ gameId: 1, status: "COMPLETED", detailedState: "Final" }),
        game({ gameId: 3, status: "IN_PROGRESS" }),
        game({ gameId: 4, status: "MID_INNING" }),
      ],
    );
    expect(cards.map((c) => c.gameId)).toEqual([4, 3, 2, 1]);
  });
});

describe("slateCounts", () => {
  it("tallies each status bucket", () => {
    const cards = mergeSlate(
      [matchup({ gameId: 1 }), matchup({ gameId: 2 }), matchup({ gameId: 3 })],
      [
        game({ gameId: 1, status: "IN_PROGRESS" }),
        game({ gameId: 2, status: "COMPLETED" }),
      ],
    );
    expect(slateCounts(cards)).toEqual({ live: 1, scheduled: 1, final: 1 });
  });
});
