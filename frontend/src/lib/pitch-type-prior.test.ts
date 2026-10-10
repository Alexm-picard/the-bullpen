import { describe, expect, it } from "vitest";

import { agateShare, pitchName, rankShares } from "./pitch-type-prior";

describe("rankShares", () => {
  it("ranks highest first with labels from the seven-class map", () => {
    const rows = rankShares({ SL: 0.2, FF: 0.5, OFF: 0.3 });
    expect(rows.map((r) => r.code)).toEqual(["FF", "OFF", "SL"]);
    expect(rows.map((r) => r.label)).toEqual(["Four-seam", "Other", "Slider"]);
  });

  it("breaks ties by code so the order is stable across polls", () => {
    expect(rankShares({ SL: 0.25, CH: 0.25 }).map((r) => r.code)).toEqual([
      "CH",
      "SL",
    ]);
  });

  it("clamps shares into [0, 1] and drops non-finite values", () => {
    const rows = rankShares({ FF: 1.2, SL: -0.1, CH: Number.NaN });
    expect(rows).toEqual([
      { code: "FF", label: "Four-seam", share: 1 },
      { code: "SL", label: "Slider", share: 0 },
    ]);
  });
});

describe("pitchName", () => {
  it("names Statcast codes finer than the model's classes", () => {
    expect(pitchName("ST")).toBe("Sweeper");
    expect(pitchName("KC")).toBe("Knuckle curve");
  });
  it("falls back to the raw code rather than guessing", () => {
    expect(pitchName("ZZ")).toBe("ZZ");
  });
});

describe("agateShare", () => {
  it("prints rates the way a box score does", () => {
    expect(agateShare(0.34)).toBe(".34");
    expect(agateShare(0.045)).toBe(".05");
    expect(agateShare(1)).toBe("1.00");
  });
});
