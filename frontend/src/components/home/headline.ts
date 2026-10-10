/**
 * The front-page headline, composed from the featured matchup and the pitcher's CAREER arsenal.
 *
 * [183] in copy form: the headline names people and a mix, never a most-likely pitch and never a
 * probability. The arsenal is career usage, so the wording says "mix", not "tonight" (approved
 * SPEC-home §13 answer 1). It is stable for the whole game: nothing here changes per pitch, so the
 * headline does not jitter while the prior beside it updates.
 */
import type { ArsenalPitch } from "../../api/players";
import { pitchName } from "../../lib/pitch-type-prior";

export type HeadlineInput = {
  awayName: string;
  homeName: string;
  awayTeam: string;
  homeTeam: string;
  /** Whose arsenal the headline describes (the pitcher on the mound, else the away starter). */
  pitcherName: string | null;
  arsenal: readonly ArsenalPitch[] | null;
};

/** Oxford-free list join: "a", "a and b", "a, b and c". */
function joinList(items: string[]): string {
  if (items.length <= 1) return items.join("");
  return `${items.slice(0, -1).join(", ")} and ${items[items.length - 1]}`;
}

/** The pitcher's top three pitches by career usage, as lower-case names. */
export function topPitchNames(
  arsenal: readonly ArsenalPitch[],
  n = 3,
): string[] {
  return [...arsenal]
    .filter((p) => Number.isFinite(p.usagePct) && p.usagePct > 0)
    .sort((a, b) => b.usagePct - a.usagePct)
    .slice(0, n)
    .map((p) => pitchName(p.pitchType).toLowerCase());
}

/** Family name for the "Messick's mix" possessive ("Parker Messick" -> "Messick"). */
const SUFFIXES = new Set(["jr", "jr.", "sr", "sr.", "ii", "iii", "iv"]);

export function surname(fullName: string): string {
  const parts = fullName.trim().split(/\s+/);
  // "Ronald Acuña Jr." -> "Acuña": a generational suffix is not the name a headline uses.
  while (
    parts.length > 1 &&
    SUFFIXES.has((parts[parts.length - 1] ?? "").toLowerCase())
  ) {
    parts.pop();
  }
  return parts[parts.length - 1] ?? fullName;
}

export function headlineFor(input: HeadlineInput): string {
  const away = surname(input.awayName);
  const home = surname(input.homeName);
  const pitches =
    input.arsenal && input.pitcherName ? topPitchNames(input.arsenal) : [];
  if (input.pitcherName && pitches.length > 0) {
    return `${away} and ${home}, and ${surname(input.pitcherName)}\u2019s mix: ${joinList(pitches)}.`;
  }
  return `${away} and ${home}: ${input.awayTeam} at ${input.homeTeam}.`;
}

/** The evergreen headline for a night with no games (offseason, off days). */
export const NO_GAMES_HEADLINE =
  "Four models, scored against what actually happened.";
