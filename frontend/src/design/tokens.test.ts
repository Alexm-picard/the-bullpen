/**
 * Token contract test — scouting-report identity.
 *
 * tokens.ts is the single source of truth; both Tailwind 4's `@theme` block
 * and the Mantine theme in theme.ts derive from it. If anything renames or
 * restructures a token, downstream consumers break silently unless this
 * contract catches it.
 *
 * Updated 2026-05-29 per decision [133]: scouting-report tokens.
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

const HEX6 = /^#[0-9A-Fa-f]{6}$/;

describe("design tokens — scouting-report identity", () => {
  // ── Surfaces ──────────────────────────────────────────────────────────────

  it("exposes the four warm-paper surface tokens", () => {
    expect(colors.bgBase).toMatch(HEX6);
    expect(colors.bgSheet).toMatch(HEX6);
    expect(colors.bgSubtle).toMatch(HEX6);
    expect(colors.bgEmphasis).toMatch(HEX6);
  });

  // ── Chrome ────────────────────────────────────────────────────────────────

  it("pins the scarlet accent — the primary broadcast-chrome accent", () => {
    // Changing this hex changes the brand. Do it deliberately.
    expect(colors.scarlet).toBe("#C8102E");
  });

  it("pins the navy chrome — table headers and lower-third bars", () => {
    expect(colors.navy).toBe("#142A4C");
  });

  it("exposes navyDeep and silver chrome tokens as valid hex", () => {
    expect(colors.navyDeep).toMatch(HEX6);
    expect(colors.silver).toMatch(HEX6);
  });

  it("exposes textOnNavy for cream text on navy backgrounds", () => {
    expect(colors.textOnNavy).toMatch(HEX6);
  });

  // ── Text ──────────────────────────────────────────────────────────────────

  it("exposes a 3-level text ramp (strong / default / muted)", () => {
    expect(colors.textStrong).toMatch(HEX6);
    expect(colors.textDefault).toMatch(HEX6);
    expect(colors.textMuted).toMatch(HEX6);
  });

  // ── Conditional-format ramp ───────────────────────────────────────────────

  it("ships the 5-stop conditional-format diverging ramp", () => {
    const ramp = colors.condFormat;
    expect(ramp.bad3).toMatch(HEX6);
    expect(ramp.bad1).toMatch(HEX6);
    expect(ramp.neutral).toMatch(HEX6);
    expect(ramp.good1).toMatch(HEX6);
    expect(ramp.good3).toMatch(HEX6);
    // All five stops must be distinct.
    const stops = Object.values(ramp);
    expect(new Set(stops).size).toBe(stops.length);
  });

  it("ships a 4-stop heat ramp and a 4-stop spray ramp", () => {
    expect(colors.heatWarm).toHaveLength(4);
    expect(colors.spray).toHaveLength(4);
    for (const c of [...colors.heatWarm, ...colors.spray]) {
      expect(c).toMatch(HEX6);
    }
  });

  // ── Viz palettes ─────────────────────────────────────────────────────────

  it("ships a length-5 viridis ramp (contract — consumed by ReliabilityDiagram)", () => {
    expect(colors.viz.viridis).toHaveLength(5);
    for (const c of colors.viz.viridis) {
      expect(c).toMatch(HEX6);
    }
  });

  it("ships a length-5 categorical palette (contract — consumed by pitch outcome charts)", () => {
    expect(colors.viz.categorical).toHaveLength(5);
    for (const c of colors.viz.categorical) {
      expect(c).toMatch(HEX6);
    }
  });

  it("exposes colorblind-safe diverging alt (brick/teal)", () => {
    expect(colors.condFormatColorblind.bad).toMatch(HEX6);
    expect(colors.condFormatColorblind.good).toMatch(HEX6);
    expect(colors.condFormatColorblind.bad).not.toBe(
      colors.condFormatColorblind.good,
    );
  });

  // ── Typography ────────────────────────────────────────────────────────────

  it("declares the three scouting-report font families", () => {
    expect(typography.fonts.display).toContain("Saira Condensed");
    expect(typography.fonts.body).toContain("IBM Plex Sans");
    expect(typography.fonts.mono).toContain("IBM Plex Mono");
  });

  it("uses a tighter display line-height than body line-height", () => {
    expect(typography.lineHeights.display).toBeLessThan(
      typography.lineHeights.body,
    );
    // display = 1.1 (condensed faces want tight leading)
    expect(typography.lineHeights.display).toBe(1.1);
  });

  it("includes a heavy weight for condensed headlines", () => {
    expect(typography.weights.heavy).toBe(800);
  });

  it("orders the type scale monotonically and has 8 entries", () => {
    expect(typography.scale).toHaveLength(8);
    for (let i = 1; i < typography.scale.length; i++) {
      expect(typography.scale[i]).toBeGreaterThan(typography.scale[i - 1]);
    }
  });

  // ── Spacing ───────────────────────────────────────────────────────────────

  it("orders the 9-entry spacing scale monotonically (8-point grid)", () => {
    expect(spacing).toHaveLength(9);
    for (let i = 1; i < spacing.length; i++) {
      expect(spacing[i]).toBeGreaterThan(spacing[i - 1]);
    }
    // Floor is 4px (the grid's base unit, not 2px any more)
    expect(spacing[0]).toBe(4);
  });

  // ── Layout widths ─────────────────────────────────────────────────────────

  it("defines layout widths: editorial < reportSheet < analytical", () => {
    expect(layouts.editorialMaxWidth).toBeLessThan(layouts.reportSheetMaxWidth);
    expect(layouts.reportSheetMaxWidth).toBeLessThan(
      layouts.analyticalMaxWidth,
    );
    // Sidebar must fit inside the editorial column
    expect(layouts.analyticalSidebar).toBeLessThan(layouts.editorialMaxWidth);
  });

  it("exposes reportSheetMaxWidth = 1100 (new in scouting-report identity)", () => {
    expect(layouts.reportSheetMaxWidth).toBe(1100);
  });

  // ── Motion ────────────────────────────────────────────────────────────────

  it("defines motion durations short enough to feel snappy", () => {
    expect(motion.durationsMs.fast).toBeLessThan(motion.durationsMs.base);
    expect(motion.durationsMs.base).toBeLessThan(motion.durationsMs.slow);
    expect(motion.durationsMs.slow).toBeLessThanOrEqual(300);
  });

  // ── Radii + Shadows ───────────────────────────────────────────────────────

  it("uses tighter radii than the old editorial-data identity (sm=2, md=4)", () => {
    expect(radii.sm).toBe(2);
    expect(radii.md).toBe(4);
    expect(radii.lg).toBe(6);
    expect(radii.pill).toBeGreaterThan(radii.lg);
  });

  it("exposes card + popover shadows for Mantine compatibility", () => {
    expect(shadows.card).toContain("rgba");
    expect(shadows.popover).toContain("rgba");
  });
});
