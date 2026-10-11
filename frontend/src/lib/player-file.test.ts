import { describe, expect, it } from "vitest";

import type { ArsenalPitch, BattedBallRow } from "../api/players";
import { TEAM_ABBREVIATIONS } from "../design/teamColors";

import {
  battedWindowPhrase,
  batterFigures,
  calledPhrase,
  DIVISIONS,
  happenedPhrase,
  pitcherFigures,
  positionWord,
  roleOf,
  TEAM_PLACE,
  teamPlace,
} from "./player-file";

const ALIASES = new Set(["ARI", "CHW", "OAK", "WSN"]);
const PRIMARY = TEAM_ABBREVIATIONS.filter((t) => !ALIASES.has(t));

describe("role routing", () => {
  it("routes P/SP/RP to the pitcher file, TWP to both, everything else to the batter file", () => {
    expect(roleOf("P")).toBe("pitcher");
    expect(roleOf(" sp ")).toBe("pitcher");
    expect(roleOf("RP")).toBe("pitcher");
    expect(roleOf("TWP")).toBe("two-way");
    expect(roleOf("RF")).toBe("batter");
    expect(roleOf("")).toBe("batter");
  });

  it("prints a known position as words and an unknown code as itself", () => {
    expect(positionWord("RF")).toBe("Right field");
    expect(positionWord("XX")).toBe("XX");
  });
});

describe("team index", () => {
  it("DIVISIONS covers exactly the 30 primary team codes, once each", () => {
    const listed = DIVISIONS.flatMap((d) => d.teams);
    expect(listed).toHaveLength(30);
    expect(new Set(listed).size).toBe(30);
    expect([...listed].sort()).toEqual([...PRIMARY].sort());
  });

  it("every primary code has a place name; an empty team has none", () => {
    for (const t of PRIMARY) expect(TEAM_PLACE[t]).toBeTruthy();
    expect(teamPlace("DET")).toBe("Detroit");
    expect(teamPlace("")).toBeNull();
  });
});

describe("pitcherFigures", () => {
  const arsenal: ArsenalPitch[] = [
    {
      pitchType: "FF",
      count: 4548,
      usagePct: 0.6,
      veloMinMph: 93.1,
      veloAvgMph: 96.6,
      veloMaxMph: 99.8,
    },
    {
      pitchType: "CH",
      count: 3411,
      usagePct: 0.4,
      veloMinMph: 84,
      veloAvgMph: 87.4,
      veloMaxMph: 89.9,
    },
  ];

  it("sums pitches, counts types, and names the hardest pitch", () => {
    const [tracked, types, hardest] = pitcherFigures(arsenal);
    expect(tracked?.value).toBe("7,959");
    expect(types?.value).toBe("2");
    expect(hardest?.value).toBe("99.8");
    expect(hardest?.basis).toBe("Four-seam, the top of its range.");
  });

  it("an empty arsenal prints no number at all, never a 0", () => {
    for (const f of pitcherFigures([])) {
      expect(f.value).toBeNull();
      expect(f.basis).toBe("Not tracked in this file.");
    }
  });
});

describe("batterFigures", () => {
  const row = (over: Partial<BattedBallRow>): BattedBallRow => ({
    gameDate: "2026-06-01",
    events: "single",
    bbType: "line_drive",
    launchSpeedMph: 90,
    launchAngleDeg: 12,
    hitDistanceFt: 250,
    parkId: "NYY",
    stand: "R",
    ...over,
  });

  it("hardest, longest and the 95+ share over the tracked balls only", () => {
    const [ev, dist, hard] = batterFigures(
      [
        row({ launchSpeedMph: 117.4, hitDistanceFt: 468 }),
        row({ launchSpeedMph: 95 }),
        row({ launchSpeedMph: 80 }),
        row({ launchSpeedMph: null, hitDistanceFt: null }),
      ],
      200,
    );
    expect(ev?.value).toBe("117.4");
    expect(ev?.basis).toBe("Of his 4 balls in play on file.");
    expect(dist?.value).toBe("468");
    expect(hard?.value).toBe("67%");
  });

  it("all speeds untracked: no number at all, never 0.0", () => {
    const figs = batterFigures([row({ launchSpeedMph: null })], 200);
    expect(figs[0]?.value).toBeNull();
    expect(figs[2]?.value).toBeNull();
  });

  it("names the window honestly", () => {
    expect(battedWindowPhrase(200, 200)).toBe("his last 200 balls in play");
    expect(battedWindowPhrase(87, 200)).toBe("his 87 balls in play on file");
    expect(battedWindowPhrase(1, 200)).toBe("his one ball in play on file");
  });
});

describe("call phrases", () => {
  it("reads outcome classes as sentence fragments", () => {
    expect(calledPhrase("called_strike")).toBe("a called strike");
    expect(calledPhrase("in_play")).toBe("in play");
    expect(happenedPhrase("in_play")).toBe("put in play");
    expect(happenedPhrase("weird_thing")).toBe("weird thing");
  });
});
