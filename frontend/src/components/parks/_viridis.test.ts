import { describe, expect, it } from "vitest";

import { colors } from "../../design/broadcast";

import { viridis } from "./_viridis";

describe("viridis colormap", () => {
  it("anchors at the first stop for x ≤ 0", () => {
    expect(viridis(0)).toBe(colors.viz.viridis[0]);
    expect(viridis(-1)).toBe(colors.viz.viridis[0]);
  });

  it("anchors at the last stop for x ≥ 1", () => {
    expect(viridis(1)).toBe(colors.viz.viridis[4]);
    expect(viridis(2)).toBe(colors.viz.viridis[4]);
  });

  it("returns the first stop for NaN", () => {
    expect(viridis(Number.NaN)).toBe(colors.viz.viridis[0]);
  });

  it("returns a 7-char hex for any in-range value", () => {
    for (let t = 0.0; t <= 1.0; t += 0.05) {
      const c = viridis(t);
      expect(c).toMatch(/^#[0-9a-f]{6}$/);
    }
  });

  it("hits each anchor at 0/0.25/0.5/0.75/1.0 (case-insensitive)", () => {
    expect(viridis(0).toLowerCase()).toBe(colors.viz.viridis[0].toLowerCase());
    expect(viridis(0.25).toLowerCase()).toBe(
      colors.viz.viridis[1].toLowerCase(),
    );
    expect(viridis(0.5).toLowerCase()).toBe(
      colors.viz.viridis[2].toLowerCase(),
    );
    expect(viridis(0.75).toLowerCase()).toBe(
      colors.viz.viridis[3].toLowerCase(),
    );
    expect(viridis(1).toLowerCase()).toBe(colors.viz.viridis[4].toLowerCase());
  });
});
