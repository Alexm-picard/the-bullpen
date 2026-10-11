/**
 * The generated dek's rule set (SPEC-players section 7). Each rule is pinned by the case that
 * would red if the rule changed: the floors, the tie-break, the under/over boundary, the dropped
 * clause on a missing input. The cross-check that every dek number is printed elsewhere on the page
 * lives in the profile page test (it uses the rendered page as the oracle, not this module).
 */
import { describe, expect, it } from "vitest";

import type { ArsenalPitch, BattedBallRow } from "../api/players";

import {
  batterDek,
  countWord,
  DEK_RANGE_FLOOR,
  pitcherDek,
  rankArsenal,
  rankBattedTypes,
} from "./player-dek";

function pitch(
  pitchType: string,
  usagePct: number,
  veloAvgMph: number,
  count = 1000,
  spread = 3,
): ArsenalPitch {
  return {
    pitchType,
    count,
    usagePct,
    veloMinMph: veloAvgMph - spread,
    veloAvgMph,
    veloMaxMph: veloAvgMph + spread,
  };
}

function ball(over: Partial<BattedBallRow> = {}): BattedBallRow {
  return {
    gameDate: "2026-06-01",
    events: "field_out",
    bbType: "fly_ball",
    launchSpeedMph: 90,
    launchAngleDeg: 20,
    hitDistanceFt: 300,
    parkId: "NYY",
    stand: "R",
    ...over,
  };
}

const SKUBAL: ArsenalPitch[] = [
  {
    pitchType: "FF",
    count: 4548,
    usagePct: 0.32,
    veloMinMph: 93.1,
    veloAvgMph: 96.6,
    veloMaxMph: 99.8,
  },
  {
    pitchType: "CH",
    count: 3411,
    usagePct: 0.24,
    veloMinMph: 84.0,
    veloAvgMph: 87.4,
    veloMaxMph: 89.9,
  },
  pitch("SI", 0.16, 95.7, 2274),
  pitch("SL", 0.15, 88.9, 2132),
  pitch("CU", 0.08, 80.2, 1137),
  pitch("FC", 0.05, 90.6, 710),
];

describe("countWord", () => {
  it("spells one to nine and prints numerals from 10", () => {
    expect(countWord(1)).toBe("one");
    expect(countWord(9)).toBe("nine");
    expect(countWord(10)).toBe("10");
    expect(countWord(12)).toBe("12");
  });
});

describe("pitcherDek", () => {
  it("P0: no arsenal, no dek", () => {
    expect(pitcherDek([])).toBeNull();
  });

  it("writes the full P1-P3 dek for a real-shaped arsenal", () => {
    expect(pitcherDek(SKUBAL)).toBe(
      "Six pitches on file. The four-seam leads at 32% and runs 93 to 100 mph; the changeup, at 24%, sits nine miles an hour under it.",
    );
  });

  it("P1: one pitch reads 'One pitch'; 12 pitches use numerals", () => {
    expect(pitcherDek([pitch("FF", 1, 94)])).toMatch(/^One pitch on file\. /);
    const twelve = Array.from({ length: 12 }, (_, i) =>
      pitch(`X${i}`, 0.08, 90),
    );
    expect(pitcherDek(twelve)).toMatch(/^12 pitches on file\. /);
  });

  it("P2': a thin lead pitch gets its share but no velocity range", () => {
    const dek = pitcherDek([
      pitch("FF", 0.6, 95, DEK_RANGE_FLOOR - 1),
      pitch("SL", 0.4, 86),
    ]);
    expect(dek).toBe("Two pitches on file. The four-seam leads at 60%.");
  });

  it("P3: under, over and 'about the same' at the 3 mph boundary", () => {
    const at = (gap: number) =>
      pitcherDek([pitch("FF", 0.5, 90), pitch("SL", 0.3, 90 - gap)]);
    expect(at(3)).toContain("sits three miles an hour under it");
    expect(at(-3)).toContain("sits three miles an hour over it");
    expect(at(2)).toContain("comes in at about the same speed");
    expect(at(-2)).toContain("comes in at about the same speed");
    expect(at(0)).toContain("comes in at about the same speed");
  });

  it("P3 is skipped when the second pitch carries under 10% of the mix", () => {
    const dek = pitcherDek([pitch("FF", 0.92, 95), pitch("SL", 0.08, 85)]);
    expect(dek).toBe(
      "Two pitches on file. The four-seam leads at 92% and runs 92 to 98 mph.",
    );
  });

  it("ranks by usage, then count, then code", () => {
    const ranked = rankArsenal([
      pitch("SL", 0.3, 88, 100),
      pitch("CH", 0.3, 86, 100),
      pitch("FF", 0.3, 95, 200),
    ]).map((p) => p.pitchType);
    expect(ranked).toEqual(["FF", "CH", "SL"]);
  });

  it("never uses evaluative words, across many generated arsenals", () => {
    const banned = /\b(elite|nasty|dominant|best|filthy|great)\b/i;
    for (let n = 1; n <= 8; n++) {
      for (let g = -12; g <= 12; g += 3) {
        const arsenal = Array.from({ length: n }, (_, i) =>
          pitch(
            ["FF", "SL", "CH", "CU", "SI", "FC", "ST", "FS"][i] ?? "FF",
            (n - i) / 20,
            92 - i * g,
          ),
        );
        const dek = pitcherDek(arsenal) ?? "";
        expect(dek).not.toMatch(banned);
        expect(dek).not.toContain("—");
      }
    }
  });
});

describe("batterDek", () => {
  const SEASON = 2026;

  it("B0: no balls, no dek", () => {
    expect(batterDek([], SEASON, 200)).toBeNull();
  });

  it("B1: a full window reads 'his last N'; a short one is everything on file", () => {
    const full = Array.from({ length: 200 }, () => ball());
    expect(batterDek(full, SEASON, 200)).toMatch(
      /^His last 200 balls in play reach back to June 1\./,
    );
    expect(batterDek([ball(), ball()], SEASON, 200)).toMatch(
      /^His 2 balls in play on file reach back to June 1\./,
    );
    expect(batterDek([ball()], SEASON, 200)).toMatch(
      /^His one ball in play on file reaches back to June 1\./,
    );
  });

  it("B1: a prior-season oldest date carries its year", () => {
    expect(
      batterDek([ball({ gameDate: "2025-09-30" })], SEASON, 200),
    ).toContain("back to September 30, 2025.");
  });

  it("B2: the hardest ball, with an event phrase only for hits", () => {
    const hr = [ball({ launchSpeedMph: 117.4, events: "home_run" }), ball()];
    expect(batterDek(hr, SEASON, 200)).toContain(
      "The hardest left the bat at 117.4 mph, a home run.",
    );
    const out = [ball({ launchSpeedMph: 110.2, events: "field_out" })];
    expect(batterDek(out, SEASON, 200)).toContain(
      "The hardest left the bat at 110.2 mph.",
    );
  });

  it("B2 is dropped when no ball has a tracked speed (never 0.0)", () => {
    const dek = batterDek([ball({ launchSpeedMph: null })], SEASON, 200) ?? "";
    expect(dek).not.toContain("hardest");
    expect(dek).not.toContain("0.0");
  });

  it("B3: stated only from 20 typed balls; a tie reads 'level'", () => {
    const nineteen = Array.from({ length: 19 }, () => ball());
    expect(batterDek(nineteen, SEASON, 200)).not.toContain("most common");

    const mostly = [
      ...Array.from({ length: 12 }, () => ball({ bbType: "ground_ball" })),
      ...Array.from({ length: 8 }, () => ball({ bbType: "fly_ball" })),
    ];
    expect(batterDek(mostly, SEASON, 200)).toContain(
      "; the most common were ground balls, at 60%.",
    );

    const level = [
      ...Array.from({ length: 10 }, () => ball({ bbType: "ground_ball" })),
      ...Array.from({ length: 10 }, () => ball({ bbType: "fly_ball" })),
    ];
    expect(batterDek(level, SEASON, 200)).toContain(
      "fly balls and ground balls were level, at 50% each.",
    );
  });

  it("B3 alone is capitalised when B2 is dropped", () => {
    const rows = Array.from({ length: 20 }, () =>
      ball({ launchSpeedMph: null, bbType: "line_drive" }),
    );
    expect(batterDek(rows, SEASON, 200)).toContain(
      " The most common were line drives, at 100%.",
    );
  });

  it("rankBattedTypes ignores unknown types and ranks share desc then name", () => {
    const ranked = rankBattedTypes([
      ball({ bbType: "popup" }),
      ball({ bbType: "fly_ball" }),
      ball({ bbType: "" }),
    ]);
    expect(ranked).toEqual([
      { type: "fly_ball", share: 0.5 },
      { type: "popup", share: 0.5 },
    ]);
  });
});
