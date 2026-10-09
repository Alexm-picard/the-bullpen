/**
 * Pure view helpers for the front page's lower band (kept out of the component file so the
 * components stay fast-refresh friendly and these stay unit-testable on their own).
 */

const COUNT_WORDS = [
  "No",
  "One",
  "Two",
  "Three",
  "Four",
  "Five",
  "Six",
  "Seven",
  "Eight",
  "Nine",
];

/** "One game tonight", "12 games tonight" (digits past nine); an empty night is "Nothing scheduled". */
export function slateHeadline(n: number): string {
  if (n === 0) return "Nothing scheduled";
  const word = n < COUNT_WORDS.length ? COUNT_WORDS[n] : String(n);
  return `${word} ${n === 1 ? "game" : "games"} tonight`;
}

export type FleetEntry = { modelName: string; version: string | null };

/** The four champion families, for when the registry cannot be reached (labelled as such). */
export const FLEET_FALLBACK: readonly FleetEntry[] = [
  { modelName: "pitch_outcome_pre", version: null },
  { modelName: "pitch_type_pre", version: null },
  { modelName: "pitch_outcome_post", version: null },
  { modelName: "battedball_outcome", version: null },
];
