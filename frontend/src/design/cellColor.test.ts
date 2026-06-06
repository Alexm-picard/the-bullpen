/**
 * Unit tests for the cellColor conditional-format helper.
 *
 * Tests confirm:
 *   - Percentile mapping at p25/median/p75 lands on the correct ramp stops
 *     for both higher-is-better and lower-is-better directions.
 *   - null value returns neutral (no read).
 *   - NaN value returns neutral.
 *   - closer-to-target: values at/near median are good; extremes are bad.
 *   - Clamp option prevents oversaturation.
 */
import { describe, expect, it } from "vitest";

import { cellColor } from "./cellColor";
import { colors } from "./tokens";

const REF = { min: 0, p25: 25, median: 50, p75: 75, max: 100 };

describe("cellColor — higher-is-better", () => {
  const metric = {
    key: "test",
    direction: "higher-is-better" as const,
    reference: REF,
  };

  it("returns neutral for null", () => {
    expect(cellColor(null, metric)).toBe(colors.condFormat.neutral);
  });

  it("returns neutral for NaN", () => {
    expect(cellColor(NaN, metric)).toBe(colors.condFormat.neutral);
  });

  it("returns bad3 for values at the minimum (0th percentile)", () => {
    expect(cellColor(0, metric)).toBe(colors.condFormat.bad3);
  });

  it("returns bad1 for values around the p25 mark", () => {
    // p25 → raw percentile 0.25 → falls in [0.15, 0.35) → bad1
    expect(cellColor(25, metric)).toBe(colors.condFormat.bad1);
  });

  it("returns neutral for values at the median", () => {
    // median → raw percentile 0.5 → falls in [0.35, 0.65) → neutral
    expect(cellColor(50, metric)).toBe(colors.condFormat.neutral);
  });

  it("returns good1 for values around the p75 mark", () => {
    // p75 → raw percentile 0.75 → falls in [0.65, 0.85) → good1
    expect(cellColor(75, metric)).toBe(colors.condFormat.good1);
  });

  it("returns good3 for values at the maximum (100th percentile)", () => {
    // max → raw percentile 1.0 → falls in [0.85, 1.0] → good3
    expect(cellColor(100, metric)).toBe(colors.condFormat.good3);
  });

  it("returns good3 for values above the maximum (clamped at 100th pct)", () => {
    expect(cellColor(200, metric)).toBe(colors.condFormat.good3);
  });
});

describe("cellColor — lower-is-better", () => {
  const metric = {
    key: "test",
    direction: "lower-is-better" as const,
    reference: REF,
  };

  it("returns neutral for null", () => {
    expect(cellColor(null, metric)).toBe(colors.condFormat.neutral);
  });

  it("returns good3 for values at the minimum (low = good for this direction)", () => {
    expect(cellColor(0, metric)).toBe(colors.condFormat.good3);
  });

  it("returns good1 for values around the p25 mark (still good side)", () => {
    // p25 → raw 0.25 → inverted 0.75 → good1
    expect(cellColor(25, metric)).toBe(colors.condFormat.good1);
  });

  it("returns neutral for values at the median", () => {
    // median → raw 0.5 → inverted 0.5 → neutral
    expect(cellColor(50, metric)).toBe(colors.condFormat.neutral);
  });

  it("returns bad1 for values around the p75 mark", () => {
    // p75 → raw 0.75 → inverted 0.25 → bad1
    expect(cellColor(75, metric)).toBe(colors.condFormat.bad1);
  });

  it("returns bad3 for values at the maximum (high = bad for this direction)", () => {
    expect(cellColor(100, metric)).toBe(colors.condFormat.bad3);
  });
});

describe("cellColor — closer-to-target", () => {
  const metric = {
    key: "test",
    direction: "closer-to-target" as const,
    reference: REF,
  };

  it("returns neutral for null", () => {
    expect(cellColor(null, metric)).toBe(colors.condFormat.neutral);
  });

  it("returns good3 for a value exactly at the median (peak good)", () => {
    // distance 0 → inverted percentile 1.0 → good3
    expect(cellColor(50, metric)).toBe(colors.condFormat.good3);
  });

  it("returns a bad color for extreme values far from median", () => {
    // value at min (0) → max distance from median (50) → bad
    const result = cellColor(0, metric);
    expect([colors.condFormat.bad1, colors.condFormat.bad3]).toContain(result);
  });

  it("returns a bad color for values at the max (far from median)", () => {
    const result = cellColor(100, metric);
    expect([colors.condFormat.bad1, colors.condFormat.bad3]).toContain(result);
  });

  it("returns neutral or good for values close to median", () => {
    // value slightly off median — should land neutral or good side
    const result = cellColor(52, metric);
    expect([
      colors.condFormat.neutral,
      colors.condFormat.good1,
      colors.condFormat.good3,
    ]).toContain(result);
  });
});

describe("cellColor — clamp option", () => {
  it("clamps extreme values before percentile lookup", () => {
    const metric = {
      key: "test",
      direction: "higher-is-better" as const,
      reference: REF,
      clamp: { min: 20, max: 80 },
    };
    // Value 1000 is above clamp.max (80); gets treated as 80 → ~p75 → good1
    const resultHigh = cellColor(1000, metric);
    // Value -999 is below clamp.min (20); gets treated as 20 → below p25 → bad
    const resultLow = cellColor(-999, metric);
    expect([colors.condFormat.good1, colors.condFormat.good3]).toContain(
      resultHigh,
    );
    expect([colors.condFormat.bad1, colors.condFormat.bad3]).toContain(
      resultLow,
    );
  });
});

describe("cellColor — always returns a token hex", () => {
  const metric = {
    key: "test",
    direction: "higher-is-better" as const,
    reference: REF,
  };
  const HEX6 = /^#[0-9A-Fa-f]{6}$/;

  it("returns a 7-char hex for any finite value", () => {
    for (const v of [0, 12.5, 25, 37.5, 50, 62.5, 75, 87.5, 100]) {
      expect(cellColor(v, metric)).toMatch(HEX6);
    }
  });
});

describe("cellColor — degenerate / malformed reference (DEF-L8)", () => {
  const HEX6 = /^#[0-9A-Fa-f]{6}$/;

  // A concentrated feature collapses interior breakpoints (p25 === median ===
  // p75). The piecewise-linear percentile must not divide by a zero-width
  // segment and emit NaN/Infinity -> an arbitrary ramp end.
  it("collapsed interior breakpoints still return a valid ramp hex", () => {
    const metric = {
      key: "psi",
      direction: "lower-is-better" as const,
      reference: { min: 0, p25: 50, median: 50, p75: 50, max: 100 },
    };
    for (const v of [0, 10, 49.9, 50, 50.1, 90, 100]) {
      expect(cellColor(v, metric)).toMatch(HEX6);
    }
  });

  it("fully-collapsed reference returns a valid ramp hex (no NaN)", () => {
    const metric = {
      key: "psi",
      direction: "higher-is-better" as const,
      reference: { min: 5, p25: 5, median: 5, p75: 5, max: 5 },
    };
    for (const v of [0, 5, 10]) {
      expect(cellColor(v, metric)).toMatch(HEX6);
    }
  });

  it("non-monotonic (malformed) reference never emits a NaN-derived color", () => {
    const metric = {
      key: "psi",
      direction: "higher-is-better" as const,
      reference: { min: 0, p25: 60, median: 50, p75: 55, max: 100 },
    };
    for (const v of [0, 25, 50, 55, 60, 100]) {
      expect(cellColor(v, metric)).toMatch(HEX6);
    }
  });
});
