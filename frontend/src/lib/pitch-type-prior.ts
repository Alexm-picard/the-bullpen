/**
 * The pitch-type prior as DATA - the shared subject behind every surface that renders it (the game
 * page's PitchTypePanel and the home page's PitchTypeAgate). Sharing it is deliberate: two copies of
 * the label map or the ranking rule would let the two surfaces drift into disagreeing about the
 * same distribution. Tests keep their OWN literal expectations (the oracle is never shared).
 *
 * [183] lives in what this module does NOT do: it ranks, it never picks. There is no "most likely"
 * helper here, because the prior's top-1 is right ~45% of the time and a helper would invite a
 * headline.
 */

/** The seven classes `pitch_type_pre` emits, with display names. Order here is not display order. */
export const PITCH_TYPE_CLASS_LABELS: Readonly<Record<string, string>> = {
  FF: "Four-seam",
  SI: "Sinker",
  FC: "Cutter",
  SL: "Slider",
  CU: "Curveball",
  CH: "Changeup",
  OFF: "Other",
};

/**
 * Display names for raw Statcast pitch codes (the arsenal endpoint reports these, which are finer
 * than the model's seven classes). Unknown codes fall back to the code itself - never a guess.
 */
const STATCAST_PITCH_NAMES: Readonly<Record<string, string>> = {
  FF: "Four-seam",
  FA: "Fastball",
  SI: "Sinker",
  FT: "Two-seam",
  FC: "Cutter",
  SL: "Slider",
  ST: "Sweeper",
  SV: "Slurve",
  CU: "Curveball",
  KC: "Knuckle curve",
  CS: "Slow curve",
  CH: "Changeup",
  FS: "Splitter",
  FO: "Forkball",
  SC: "Screwball",
  KN: "Knuckleball",
  EP: "Eephus",
};

export function pitchName(code: string): string {
  return STATCAST_PITCH_NAMES[code] ?? PITCH_TYPE_CLASS_LABELS[code] ?? code;
}

export type RankedShare = { code: string; label: string; share: number };

/**
 * Rank a class -> probability map, highest first, ties broken by code so the order is stable across
 * polls. Shares are clamped to [0, 1] (they drive a scaleX); labels come from the class map, then the
 * Statcast map, then the raw code.
 */
export function rankShares(
  shares: Readonly<Record<string, number>>,
): RankedShare[] {
  return Object.entries(shares)
    .filter(([, p]) => Number.isFinite(p))
    .map(([code, p]) => ({
      code,
      label: PITCH_TYPE_CLASS_LABELS[code] ?? pitchName(code),
      share: Math.max(0, Math.min(1, p)),
    }))
    .sort((a, b) => b.share - a.share || a.code.localeCompare(b.code));
}

const TWO_DP = new Intl.NumberFormat("en-US", {
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
});

/** Agate style: ".34" (leading zero dropped, as box scores print rates); 1 stays "1.00". */
export function agateShare(share: number): string {
  const s = TWO_DP.format(share);
  return s.startsWith("0.") ? s.slice(1) : s;
}
