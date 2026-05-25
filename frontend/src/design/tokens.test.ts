/**
 * Token contract test. tokens.ts is the single source of truth — both Tailwind 4's
 * `@theme` block in index.css and the Mantine theme in theme.ts derive from it.
 * If anything renames or restructures a token, downstream consumers break silently
 * unless this contract catches it.
 */
import { describe, expect, it } from "vitest";

import {
  colors,
  layouts,
  motion,
  radii,
  shadows,
  spacing,
  typography,
} from "./tokens";

const HEX = /^#[0-9A-F]{6}$/;

describe("design tokens", () => {
  it("exposes the four warm-paper surface tokens", () => {
    expect(colors.bgBase).toMatch(HEX);
    expect(colors.bgElevated).toMatch(HEX);
    expect(colors.bgSubtle).toMatch(HEX);
    expect(colors.bgEmphasis).toMatch(HEX);
  });

  it("exposes a 4-step text ramp", () => {
    expect(colors.textStrong).toMatch(HEX);
    expect(colors.textDefault).toMatch(HEX);
    expect(colors.textMuted).toMatch(HEX);
    expect(colors.textSubtle).toMatch(HEX);
  });

  it("pins the stitching-red accent — the single chromatic anchor", () => {
    // If you change this hex you are changing the brand. Do it deliberately.
    expect(colors.accent).toBe("#D7373F");
  });

  it("ships a 5-stop viridis ramp and a 5-stop categorical palette", () => {
    expect(colors.viz.viridis).toHaveLength(5);
    expect(colors.viz.categorical).toHaveLength(5);
    for (const c of [...colors.viz.viridis, ...colors.viz.categorical]) {
      expect(c).toMatch(HEX);
    }
  });

  it("ships four status colors distinct from each other", () => {
    const all = Object.values(colors.status);
    expect(new Set(all).size).toBe(all.length);
  });

  it("declares the three editorial-data font families", () => {
    // 2026-05-25 redesign: display maps to Inter (Source Serif 4 dropped — see tokens.ts).
    // The three named slots are preserved so consumers reading `typography.fonts.display`
    // keep working; display + ui simply share Inter now.
    expect(typography.fonts.ui).toContain("Inter");
    expect(typography.fonts.data).toContain("JetBrains Mono");
    expect(typography.fonts.display).toContain("Inter");
  });

  it("orders the spacing scale monotonically", () => {
    for (let i = 1; i < spacing.length; i++) {
      expect(spacing[i]).toBeGreaterThan(spacing[i - 1]);
    }
  });

  it("defines layout widths consistent with editorial / analytical split", () => {
    expect(layouts.editorialMaxWidth).toBeLessThan(layouts.analyticalMaxWidth);
    expect(layouts.analyticalSidebar).toBeLessThan(layouts.editorialMaxWidth);
  });

  it("defines motion durations short enough to feel snappy", () => {
    expect(motion.durationsMs.fast).toBeLessThan(motion.durationsMs.base);
    expect(motion.durationsMs.base).toBeLessThan(motion.durationsMs.slow);
    expect(motion.durationsMs.slow).toBeLessThanOrEqual(300);
  });

  it("exposes radii + shadows used by the card / popover surfaces", () => {
    expect(radii.sm).toBeGreaterThan(0);
    expect(radii.pill).toBeGreaterThan(radii.lg);
    expect(shadows.card).toContain("rgba");
    expect(shadows.popover).toContain("rgba");
  });
});
