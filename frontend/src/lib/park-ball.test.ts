import { describe, expect, it } from "vitest";

import {
  carryExtremes,
  describeBall,
  probText,
  sameBall,
  sprayValueText,
  sprayWords,
} from "./park-ball";

describe("sprayWords", () => {
  it("speaks the API's absolute convention (+ toward left field), never pull/oppo or foul", () => {
    expect(sprayWords(0)).toBe("Straightaway");
    expect(sprayWords(20)).toBe("20 deg to left field");
    expect(sprayWords(-20)).toBe("20 deg to right field");
    for (const d of [-90, -60, -52.7, 52.7, 60, 90]) {
      expect(sprayWords(d)).not.toMatch(/foul|pull|oppo/i);
      expect(sprayValueText(d)).not.toMatch(/foul|pull|oppo/i);
    }
    expect(sprayValueText(-22)).toBe("22 degrees to right field");
  });
});

describe("describeBall", () => {
  it("names the ball in words, up or down, with the batter's hand", () => {
    expect(
      describeBall({
        launchSpeedMph: 110,
        launchAngleDeg: 28,
        sprayAngleDeg: 0,
        stand: "R",
      }),
    ).toBe("110 mph, 28 deg up, straightaway, right-handed batter");
    expect(
      describeBall({
        launchSpeedMph: 95,
        launchAngleDeg: -5,
        sprayAngleDeg: 12,
        stand: "L",
      }),
    ).toBe("95 mph, 5 deg down, 12 deg to left field, left-handed batter");
  });

  it("compares balls field by field", () => {
    const a = {
      launchSpeedMph: 1,
      launchAngleDeg: 2,
      sprayAngleDeg: 3,
      stand: "R" as const,
    };
    expect(sameBall(a, { ...a })).toBe(true);
    expect(sameBall(a, { ...a, stand: "L" })).toBe(false);
  });
});

describe("carryExtremes", () => {
  it("returns the shortest and longest park, rounded, ties broken by id", () => {
    expect(
      carryExtremes({ SF: 412.4, COL: 439.2, ATL: 426, NYY: 412.4 }),
    ).toEqual({
      min: { id: "NYY", name: "Yankee Stadium", ft: 412 },
      max: { id: "COL", name: "Coors Field", ft: 439 },
    });
  });

  it("is null when the champion serves no carry", () => {
    expect(carryExtremes(undefined)).toBeNull();
    expect(carryExtremes({})).toBeNull();
  });
});

describe("probText", () => {
  it("prints the agate voice and never rounds to a certainty", () => {
    expect(probText(0.62)).toBe(".62");
    expect(probText(0.05)).toBe(".05");
    expect(probText(0.996)).toBe(">.99");
    expect(probText(0.004)).toBe("<.01");
  });
});
