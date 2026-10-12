/**
 * teamColor contracts (moved from the retired broadcast.test.ts): every club maps to a hex fill,
 * lookups are case-insensitive with legacy aliases, and anything unknown falls back to the
 * editorial neutral rather than throwing or rendering an invisible mark.
 */
import { describe, expect, it } from "vitest";

import { TEAM_ABBREVIATIONS, teamColor } from "./teamColors";

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

  it("falls back to the editorial ink3 neutral for unknown or missing abbreviations", () => {
    expect(teamColor("ZZZ")).toBe("var(--ed-ink3)");
    expect(teamColor(null)).toBe("var(--ed-ink3)");
    expect(teamColor(undefined)).toBe("var(--ed-ink3)");
  });
});
