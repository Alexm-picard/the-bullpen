import { describe, expect, it } from "vitest";

import type { ArsenalPitch } from "../../api/players";

import { headlineFor, surname, topPitchNames } from "./headline";

const pitch = (pitchType: string, usagePct: number): ArsenalPitch => ({
  pitchType,
  count: 100,
  usagePct,
  veloMinMph: 80,
  veloAvgMph: 85,
  veloMaxMph: 90,
});

const BASE = {
  awayName: "Parker Messick",
  homeName: "Hagen Smith",
  awayTeam: "CLE",
  homeTeam: "CWS",
};

describe("headlineFor", () => {
  it("names both starters and the pitcher's top three by career usage", () => {
    const h = headlineFor({
      ...BASE,
      pitcherName: "Parker Messick",
      arsenal: [
        pitch("CH", 0.17),
        pitch("FF", 0.41),
        pitch("SL", 0.24),
        pitch("CU", 0.08),
      ],
    });
    expect(h).toBe(
      "Messick and Smith, and Messick’s mix: four-seam, slider and changeup.",
    );
  });

  it("lists what exists when the arsenal is short", () => {
    const h = headlineFor({
      ...BASE,
      pitcherName: "Parker Messick",
      arsenal: [pitch("FF", 0.7), pitch("SL", 0.3)],
    });
    expect(h).toBe(
      "Messick and Smith, and Messick’s mix: four-seam and slider.",
    );
  });

  it("falls back to the matchup when there is no arsenal", () => {
    expect(
      headlineFor({ ...BASE, pitcherName: "Parker Messick", arsenal: null }),
    ).toBe("Messick and Smith: CLE at CWS.");
    expect(
      headlineFor({ ...BASE, pitcherName: null, arsenal: [pitch("FF", 1)] }),
    ).toBe("Messick and Smith: CLE at CWS.");
  });

  it("[183]: never carries a probability or a most-likely call", () => {
    const h = headlineFor({
      ...BASE,
      pitcherName: "Parker Messick",
      arsenal: [pitch("FF", 0.9), pitch("SL", 0.1)],
    });
    expect(h).not.toMatch(/%|\d/);
    expect(h).not.toMatch(/likely|expect|next pitch/i);
  });
});

describe("helpers", () => {
  it("topPitchNames skips zero-usage pitches", () => {
    expect(topPitchNames([pitch("FF", 0), pitch("SL", 0.5)])).toEqual([
      "slider",
    ]);
  });
  it("surname takes the family name", () => {
    expect(surname("  Ronald Acuña Jr. ")).toBe("Acuña");
    expect(surname("Cal Ripken Jr")).toBe("Ripken");
    expect(surname("Ohtani")).toBe("Ohtani");
  });
});
