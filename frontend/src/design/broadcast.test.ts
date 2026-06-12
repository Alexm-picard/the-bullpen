/**
 * Invariants for the broadcast token layer (decision [160]) - the contracts
 * other modules rely on, not the taste choices.
 */
import { describe, expect, it } from "vitest";

import { colors, cuts, motion, spacing, typography } from "./broadcast";
import { TEAM_ABBREVIATIONS, teamColor } from "./teamColors";

describe("broadcast tokens", () => {
  it("keeps viridis at exactly 5 stops (decision [106]; ReliabilityDiagram contract)", () => {
    expect(colors.viz.viridis).toHaveLength(5);
  });

  it("keeps the categorical palette at exactly 5 stops", () => {
    expect(colors.viz.categorical).toHaveLength(5);
  });

  it("keeps the condFormat ramp's five named stops (cellColor contract)", () => {
    expect(Object.keys(colors.condFormat).sort()).toEqual([
      "bad1",
      "bad3",
      "good1",
      "good3",
      "neutral",
    ]);
  });

  it("carries the three [160] faces", () => {
    expect(typography.fonts.display).toContain("Barlow Condensed");
    expect(typography.fonts.body).toContain("Inter");
    expect(typography.fonts.mono).toContain("JetBrains Mono");
  });

  it("keeps [112] motion limits: 150-300ms functional range", () => {
    expect(motion.durationsMs.fast).toBe(150);
    expect(motion.durationsMs.slow).toBe(300);
  });

  it("keeps the 8-point spacing grid", () => {
    expect(spacing).toEqual([4, 8, 12, 16, 24, 32, 48, 64, 96]);
  });

  it("exposes the three diagonal-cut clip paths", () => {
    for (const cut of [cuts.panelCorner, cuts.lowerThirdEdge, cuts.wedge]) {
      expect(cut).toMatch(/^polygon\(/);
    }
  });
});

describe("teamColors", () => {
  it("maps all 30 clubs", () => {
    expect(TEAM_ABBREVIATIONS).toHaveLength(30);
  });

  it("every mapped value is a hex color", () => {
    for (const abbr of TEAM_ABBREVIATIONS) {
      expect(teamColor(abbr)).toMatch(/^#[0-9A-Fa-f]{6}$/);
    }
  });

  it("is case-insensitive and resolves legacy aliases", () => {
    expect(teamColor("bos")).toBe(teamColor("BOS"));
    expect(teamColor("ARI")).toBe(teamColor("AZ"));
    expect(teamColor("CHW")).toBe(teamColor("CWS"));
    expect(teamColor("OAK")).toBe(teamColor("ATH"));
    expect(teamColor("WSN")).toBe(teamColor("WSH"));
  });

  it("falls back to steel for unknown or missing abbreviations (never invisible, never throws)", () => {
    expect(teamColor("ZZZ")).toBe(colors.steel);
    expect(teamColor(null)).toBe(colors.steel);
    expect(teamColor(undefined)).toBe(colors.steel);
  });
});
