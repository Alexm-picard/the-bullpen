/**
 * cellColor — the conditional-format helper. The signature primitive of the
 * scouting-report identity.
 *
 * Maps a numeric value to a diverging-ramp hex color (the 5-stop condFormat
 * ramp from tokens: good3 / good1 / neutral / bad1 / bad3) by computing the
 * value's percentile within a reference distribution and applying the metric's
 * declared direction.
 *
 * ACCESSIBILITY RULE (§8): cell color is NEVER the sole carrier of meaning.
 * Every heat-mapped cell MUST render the value text in mono on top of the fill.
 * The ramp pairs hue with luminance (strong ends differ in lightness), so it
 * survives a grayscale screenshot. A brick↔teal toggle (condFormatColorblind)
 * is available for deuteranopia / protanopia.
 *
 * @module
 */

import { colors } from "./tokens";

// ── Types ────────────────────────────────────────────────────────────────────

export type MetricDirection =
  | "higher-is-better"
  | "lower-is-better"
  | "closer-to-target";

export type MetricMeta = {
  /** Unique key for the metric (e.g. "whiff_rate", "era_plus"). */
  key: string;
  /** Which direction along the value axis is "good." */
  direction: MetricDirection;
  /**
   * Reference distribution for percentile mapping. The simplest form is
   * (min, p25, median, p75, max) along the value axis. cellColor maps a
   * value's percentile (within this distribution) onto the diverging ramp.
   * Use league averages or the player's own population.
   */
  reference: {
    min: number;
    p25: number;
    median: number;
    p75: number;
    max: number;
  };
  /**
   * Optional clamp — values outside this range still get a color but the
   * percentile is clamped so we don't oversaturate at the extremes.
   */
  clamp?: { min: number; max: number };
};

// ── 5-stop diverging ramp ────────────────────────────────────────────────────

/**
 * The ordered 5-stop diverging ramp. Index 0 = strongly bad, index 4 = strongly
 * good. The neutral midpoint is index 2 (league-average / no read).
 */
const RAMP: [string, string, string, string, string] = [
  colors.condFormat.bad3, // 0 — strongly unfavorable
  colors.condFormat.bad1, // 1 — mildly unfavorable
  colors.condFormat.neutral, // 2 — league-average
  colors.condFormat.good1, // 3 — mildly favorable
  colors.condFormat.good3, // 4 — strongly favorable
];

// ── Internal utilities ───────────────────────────────────────────────────────

/**
 * Piecewise-linear percentile estimate from a 5-point summary
 * (min, p25, median, p75, max). Returns a value in [0, 1].
 */
function estimatePercentile(
  value: number,
  ref: MetricMeta["reference"],
): number {
  const breakpoints: [number, number][] = [
    [ref.min, 0],
    [ref.p25, 0.25],
    [ref.median, 0.5],
    [ref.p75, 0.75],
    [ref.max, 1.0],
  ];

  // Below minimum
  if (value <= ref.min) return 0;
  // Above maximum
  if (value >= ref.max) return 1;

  // Find the segment containing value and interpolate linearly.
  // i ranges over [1, length), so i-1 and i are both valid indices.
  for (let i = 1; i < breakpoints.length; i++) {
    const [x0, p0] = breakpoints[i - 1]!;
    const [x1, p1] = breakpoints[i]!;
    if (value <= x1) {
      const t = (value - x0) / (x1 - x0);
      return p0 + t * (p1 - p0);
    }
  }

  return 1;
}

/**
 * Maps a percentile in [0, 1] to one of the 5 ramp stops.
 *
 * Mapping:
 *   [0,   0.15) → bad3   (strongly unfavorable)
 *   [0.15, 0.35) → bad1   (mildly unfavorable)
 *   [0.35, 0.65) → neutral (league-average)
 *   [0.65, 0.85) → good1  (mildly favorable)
 *   [0.85, 1.0]  → good3  (strongly favorable)
 *
 * These thresholds keep the neutral band wide so only genuine outliers
 * hit the saturated ends — consistent with real scouting-report convention.
 */
function percentileToRampIndex(percentile: number): 0 | 1 | 2 | 3 | 4 {
  if (percentile < 0.15) return 0;
  if (percentile < 0.35) return 1;
  if (percentile < 0.65) return 2;
  if (percentile < 0.85) return 3;
  return 4;
}

// ── Public API ───────────────────────────────────────────────────────────────

/**
 * Maps a numeric value to a diverging-ramp hex color via the metric's
 * percentile + direction.
 *
 * Returns a token-source hex from the 5-stop condFormat ramp:
 *   - `good3` (#2E8B57) — clearly favorable
 *   - `good1` (#BFE3C6) — mildly favorable
 *   - `neutral` (#EDEAE0) — league-average / no read
 *   - `bad1` (#F6C9C2) — mildly unfavorable
 *   - `bad3` (#D8483A) — clearly unfavorable
 *
 * For `closer-to-target`: the median is the peak (good); extremes in either
 * direction are bad. Percentile is recomputed as distance from median.
 *
 * **ACCESSIBILITY**: always render value text alongside the colored cell.
 * Color is never the sole carrier of meaning.
 *
 * @param value   The numeric metric value. `null` returns neutral.
 * @param metric  The MetricMeta descriptor with direction + reference dist.
 */
export function cellColor(value: number | null, metric: MetricMeta): string {
  if (value === null || !Number.isFinite(value)) {
    return colors.condFormat.neutral;
  }

  const ref = metric.reference;

  // Apply optional clamp.
  const clamped = metric.clamp
    ? Math.max(metric.clamp.min, Math.min(metric.clamp.max, value))
    : value;

  if (metric.direction === "closer-to-target") {
    // Distance from median as a fraction of the half-range.
    // 0 = at median (best), 1 = at or beyond the extreme.
    const halfRange = Math.max(
      ref.median - ref.min,
      ref.max - ref.median,
      Number.EPSILON,
    );
    const distance = Math.abs(clamped - ref.median) / halfRange;
    const distancePct = Math.min(distance, 1);

    // Invert: distance 0 → percentile 1 (good), distance 1 → percentile 0 (bad).
    const invertedPct = 1 - distancePct;
    const rampIndex = percentileToRampIndex(invertedPct);
    return RAMP[rampIndex];
  }

  const rawPct = estimatePercentile(clamped, ref);

  let goodPct: number;
  if (metric.direction === "higher-is-better") {
    // Higher raw percentile → more favorable.
    goodPct = rawPct;
  } else {
    // lower-is-better: invert so high raw percentile (high value) = bad.
    goodPct = 1 - rawPct;
  }

  const rampIndex = percentileToRampIndex(goodPct);
  return RAMP[rampIndex];
}
