import { describe, expect, it } from "vitest";

import type { MatchupSummary } from "./matchups";
import {
  firstPitchEt,
  leanLabel,
  splitSlate,
  toBoardRow,
  toFeaturedView,
} from "./matchups-view";

function row(over: Partial<MatchupSummary> = {}): MatchupSummary {
  return {
    gameId: 1,
    gameDate: "2026-06-13",
    gameTimeUtc: "2026-06-13T23:10:00Z", // 7:10 PM ET
    homeTeam: "DET",
    awayTeam: "NYY",
    lean: "pitching",
    homePlayerId: 10,
    homePlayerName: "Tarik Skubal",
    homeRole: "pitcher",
    awayPlayerId: 20,
    awayPlayerName: "Gerrit Cole",
    awayRole: "pitcher",
    battleScore: 7.4,
    stage: "default",
    ...over,
  };
}

describe("leanLabel", () => {
  it("maps each classifier lean to its display label", () => {
    expect(leanLabel("pitching")).toBe("Pitching Duel");
    expect(leanLabel("hitters")).toBe("Hitters Duel");
    expect(leanLabel("mixed")).toBe("Split Lean");
  });

  it("falls back to a generic label for an unknown lean", () => {
    expect(leanLabel("whatever")).toBe("Matchup");
  });
});

describe("firstPitchEt", () => {
  it("formats an ISO instant in ET with an ET suffix", () => {
    // 23:10 UTC on 2026-06-13 is 7:10 PM EDT.
    expect(firstPitchEt("2026-06-13T23:10:00Z")).toBe("7:10 PM ET");
  });

  it("returns TBD for a null or unparseable time", () => {
    expect(firstPitchEt(null)).toBe("TBD");
    expect(firstPitchEt("not-a-date")).toBe("TBD");
  });
});

describe("toFeaturedView / toBoardRow", () => {
  it("carries the two lean-driven sides with team context", () => {
    const v = toFeaturedView(row());
    expect(v.leanLabel).toBe("Pitching Duel");
    expect(v.firstPitchEt).toBe("7:10 PM ET");
    expect(v.away).toEqual({
      playerId: 20,
      name: "Gerrit Cole",
      team: "NYY",
      role: "pitcher",
    });
    expect(v.home.name).toBe("Tarik Skubal");
    expect(v.battleScore).toBe(7.4);
    expect(v.stage).toBe("default");
  });

  it("a hitters lean carries two hitters into the board row", () => {
    const b = toBoardRow(
      row({
        lean: "hitters",
        homeRole: "hitter",
        awayRole: "hitter",
        homePlayerName: "Riley Greene",
        awayPlayerName: "Aaron Judge",
      }),
    );
    expect(b.leanLabel).toBe("Hitters Duel");
    expect(b.away.role).toBe("hitter");
    expect(b.home.role).toBe("hitter");
    expect(b.awayTeam).toBe("NYY");
    expect(b.homeTeam).toBe("DET");
  });
});

describe("splitSlate", () => {
  it("returns no featured and an empty board for an empty slate", () => {
    expect(splitSlate([])).toEqual({ featured: null, board: [] });
  });

  it("takes row 0 as featured and the rest as the board (best battle first)", () => {
    const slate = [
      row({ gameId: 1, battleScore: 9.1 }),
      row({ gameId: 2, battleScore: 6.0, lean: "hitters" }),
      row({ gameId: 3, battleScore: 4.2, lean: "mixed" }),
    ];
    const { featured, board } = splitSlate(slate);
    expect(featured?.gameId).toBe(1);
    expect(board.map((b) => b.gameId)).toEqual([2, 3]);
    expect(board.map((b) => b.leanLabel)).toEqual([
      "Hitters Duel",
      "Split Lean",
    ]);
  });
});
