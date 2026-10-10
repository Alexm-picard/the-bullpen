/**
 * The ONLY per-chart note /accuracy may print (decision [199]): a sentence generated from the
 * reliability bins by a declared rule, never a hand-written reading.
 *
 * A bin is flagged only when all three hold:
 *   1. n >= minReadableN (30, from the payload) - fewer calls are drawn hollow and never read;
 *   2. its mean predicted confidence lies OUTSIDE the 99% Wilson interval of its observed rate.
 *      99%, not 95%: about 8 bins x 3 charts are tested on every render, and at 95% a perfectly
 *      calibrated set would throw a false flag on most renders;
 *   3. |observed - predicted| >= 0.03, a practical floor - at n ~ 10k the interval alone flags a
 *      2-point gap nobody should be told about.
 *
 * Copy carries numbers, never adjectives ("too sure", "slightly"): the direction is in the
 * numbers, so no generated sentence can editorialise.
 */
import type { ReliabilityBin } from "../api/rolling-accuracy";

export const WILSON_Z_99 = 2.576;
export const PRACTICAL_GAP = 0.03;
export const MAX_NOTES = 2;

/** The 99% Wilson score interval for hits/n. */
export function wilson99(hits: number, n: number): { lo: number; hi: number } {
  if (n <= 0) return { lo: 0, hi: 1 };
  const z = WILSON_Z_99;
  const p = hits / n;
  const z2 = z * z;
  const denom = 1 + z2 / n;
  const center = (p + z2 / (2 * n)) / denom;
  const half = (z * Math.sqrt((p * (1 - p)) / n + z2 / (4 * n * n))) / denom;
  return { lo: Math.max(0, center - half), hi: Math.min(1, center + half) };
}

/** True when the rule above flags this bin. */
export function isOffTheLine(
  bin: ReliabilityBin,
  minReadableN: number,
): boolean {
  if (bin.n < minReadableN) return false;
  if (Math.abs(bin.observed - bin.meanConfidence) < PRACTICAL_GAP) return false;
  const { lo, hi } = wilson99(bin.hits, bin.n);
  return bin.meanConfidence < lo || bin.meanConfidence > hi;
}

const COUNT = new Intl.NumberFormat("en-US");
const pct = (f: number) => `${Math.round(f * 100)}%`;
const edge = (x: number) => x.toFixed(1);

/**
 * The generated sentences for one chart: the zero-flag sentence, or up to MAX_NOTES flagged
 * bins (largest gap first, ties by the lower bin), plus a count of any further flagged bins.
 */
export function calibrationNotes(
  bins: ReliabilityBin[],
  minReadableN: number,
): string[] {
  const flagged = bins
    .filter((b) => isOffTheLine(b, minReadableN))
    .sort(
      (a, b) =>
        Math.abs(b.observed - b.meanConfidence) -
          Math.abs(a.observed - a.meanConfidence) || a.lower - b.lower,
    );
  if (flagged.length === 0) {
    return [
      `No bin with ${minReadableN} or more calls is off the line by ${Math.round(
        PRACTICAL_GAP * 100,
      )} points or more.`,
    ];
  }
  const out = flagged
    .slice(0, MAX_NOTES)
    .map(
      (b) =>
        `At ${edge(b.lower)} to ${edge(b.upper)} confidence these calls came true ${pct(
          b.observed,
        )} of the time, against ${pct(b.meanConfidence)} predicted (${COUNT.format(
          b.n,
        )} calls).`,
    );
  const more = flagged.length - MAX_NOTES;
  if (more > 0) {
    out.push(
      `${more} more ${more === 1 ? "bin is" : "bins are"} off the line.`,
    );
  }
  return out;
}
