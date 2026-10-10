import { describe, expect, it } from "vitest";

import type { ReliabilityBin } from "../api/rolling-accuracy";

import { calibrationNotes, isOffTheLine, wilson99 } from "./calibration-notes";

/** A bin from (lower, n, observed, meanConfidence); hits derived so observed is exact-ish. */
function bin(
  lower: number,
  n: number,
  observed: number,
  meanConfidence: number,
): ReliabilityBin {
  return {
    lower,
    upper: Math.round((lower + 0.1) * 10) / 10,
    n,
    hits: Math.round(observed * n),
    meanConfidence,
    observed,
  };
}

describe("wilson99", () => {
  it("brackets the observed rate and narrows with n", () => {
    const small = wilson99(60, 100);
    const big = wilson99(6000, 10000);
    expect(small.lo).toBeLessThan(0.6);
    expect(small.hi).toBeGreaterThan(0.6);
    expect(big.hi - big.lo).toBeLessThan(small.hi - small.lo);
    // Known value: p=.6, n=100, z=2.576 -> roughly [.471, .717].
    expect(small.lo).toBeCloseTo(0.471, 2);
    expect(small.hi).toBeCloseTo(0.717, 2);
  });

  it("an empty bin is the whole unit interval, never NaN", () => {
    expect(wilson99(0, 0)).toEqual({ lo: 0, hi: 1 });
  });
});

describe("isOffTheLine - all three conditions must hold", () => {
  it("never flags a bin below the readable floor, however far off", () => {
    expect(isOffTheLine(bin(0.8, 29, 0.5, 0.85), 30)).toBe(false);
  });

  it("does NOT flag a 2-point gap at n ~ 10k (the practical floor), though Wilson alone would", () => {
    const b = bin(0.5, 10490, 0.53, 0.55);
    const { lo, hi } = wilson99(b.hits, b.n);
    expect(b.meanConfidence < lo || b.meanConfidence > hi).toBe(true);
    expect(isOffTheLine(b, 30)).toBe(false);
  });

  it("flags a 5-point gap at n = 4,330", () => {
    expect(isOffTheLine(bin(0.6, 4330, 0.6, 0.65), 30)).toBe(true);
  });

  it("does NOT flag a 4-point gap at n = 200 that sits inside the 99% interval", () => {
    expect(isOffTheLine(bin(0.6, 200, 0.61, 0.65), 30)).toBe(false);
  });
});

describe("calibrationNotes copy", () => {
  it("states the zero-flag sentence with the payload's readable floor", () => {
    expect(calibrationNotes([bin(0.5, 5000, 0.55, 0.55)], 30)).toEqual([
      "No bin with 30 or more calls is off the line by 3 points or more.",
    ]);
  });

  it("writes numbers, not adjectives, largest gap first", () => {
    const notes = calibrationNotes(
      [bin(0.7, 960, 0.7, 0.75), bin(0.6, 4330, 0.6, 0.65)],
      30,
    );
    expect(notes).toEqual([
      "At 0.6 to 0.7 confidence these calls came true 60% of the time, against 65% predicted (4,330 calls).",
      "At 0.7 to 0.8 confidence these calls came true 70% of the time, against 75% predicted (960 calls).",
    ]);
    expect(notes.join(" ")).not.toMatch(/too sure|slightly|modest/i);
  });

  it("caps at two sentences and counts the rest, without pointing at a visible table", () => {
    const notes = calibrationNotes(
      [
        bin(0.3, 5000, 0.4, 0.35),
        bin(0.5, 5000, 0.62, 0.55),
        bin(0.6, 5000, 0.55, 0.65),
        bin(0.7, 5000, 0.84, 0.75),
      ],
      30,
    );
    expect(notes).toHaveLength(3);
    expect(notes[0]).toContain("At 0.6 to 0.7");
    expect(notes[1]).toContain("At 0.7 to 0.8");
    expect(notes[2]).toBe("2 more bins are off the line.");
    expect(notes.join(" ")).not.toMatch(/table/i);
  });
});
