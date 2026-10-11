/**
 * The player file's generated dek (SPEC-players section 7): one or two plain sentences written
 * from the live record, never by hand.
 *
 * Rules that hold for both builders:
 *  - every number in the dek is a number the page also prints, rounded the same way (the profile
 *    test checks the rendered page, not this module, so the oracle is independent of the subject);
 *  - no evaluative words (no "elite", "nasty", "dominant", "best") - the record speaks;
 *  - a clause whose input is missing or below its floor is DROPPED, never filled with a guess;
 *  - no em-dashes (the project writing rule).
 */
import type { ArsenalPitch, BattedBallRow } from "../api/players";

import { pitchName } from "./pitch-type-prior";

/** A pitch needs this many tracked throws before its velocity range is worth a sentence. */
export const DEK_RANGE_FLOOR = 100;
/** The second pitch is only compared when it carries at least this share of the mix. */
export const DEK_SECOND_PITCH_SHARE = 0.1;
/** A speed gap under this many mph reads as "about the same speed". */
export const DEK_SPEED_GAP_MPH = 3;
/** Batted-ball type shares need at least this many typed balls to be stated. */
export const DEK_TYPE_FLOOR = 20;

const SMALL = [
  "zero",
  "one",
  "two",
  "three",
  "four",
  "five",
  "six",
  "seven",
  "eight",
  "nine",
];

/** One to nine spelled out, numerals from 10 (newspaper style). */
export function countWord(n: number): string {
  return (n >= 0 && n <= 9 ? SMALL[n] : undefined) ?? String(n);
}

function capitalize(s: string): string {
  return s.charAt(0).toUpperCase() + s.slice(1);
}

/** Usage share as a whole percent, the same rounding the ranked aside uses. */
export function sharePct(share: number): number {
  return Math.round(share * 100);
}

/** Arsenal rows ranked as the page ranks them: usage desc, then count desc, then code. */
export function rankArsenal(arsenal: readonly ArsenalPitch[]): ArsenalPitch[] {
  return [...arsenal].sort(
    (a, b) =>
      b.usagePct - a.usagePct ||
      b.count - a.count ||
      a.pitchType.localeCompare(b.pitchType),
  );
}

/** "four-seam", "changeup": the reading-voice name of a pitch code. */
function pitchNoun(code: string): string {
  return pitchName(code).toLowerCase();
}

/**
 * The pitcher dek (rules P0-P4). Null when there is no arsenal on file (P0) - the caller then
 * renders no dek element at all.
 */
export function pitcherDek(arsenal: readonly ArsenalPitch[]): string | null {
  if (arsenal.length === 0) return null; // P0
  const ranked = rankArsenal(arsenal);
  const n = ranked.length;
  // P1
  const first =
    n === 1
      ? "One pitch on file."
      : `${capitalize(countWord(n))} pitches on file.`;

  const lead = ranked[0];
  if (lead == null) return null;
  // P2 / P2'
  let second = `The ${pitchNoun(lead.pitchType)} leads at ${sharePct(lead.usagePct)}%`;
  const leadRanged = lead.count >= DEK_RANGE_FLOOR;
  if (leadRanged) {
    second += ` and runs ${Math.round(lead.veloMinMph)} to ${Math.round(lead.veloMaxMph)} mph`;
  }
  // P3
  const next = ranked[1];
  if (
    next != null &&
    next.usagePct >= DEK_SECOND_PITCH_SHARE &&
    leadRanged &&
    next.count >= DEK_RANGE_FLOOR
  ) {
    const gap = Math.round(lead.veloAvgMph - next.veloAvgMph);
    const head = `; the ${pitchNoun(next.pitchType)}, at ${sharePct(next.usagePct)}%,`;
    if (Math.abs(gap) >= DEK_SPEED_GAP_MPH) {
      const miles = Math.abs(gap) === 1 ? "mile" : "miles";
      second += `${head} sits ${countWord(Math.abs(gap))} ${miles} an hour ${gap > 0 ? "under" : "over"} it`;
    } else {
      second += `${head} comes in at about the same speed`;
    }
  }
  // P4
  return `${first} ${second}.`;
}

const BB_PLURAL: Readonly<Record<string, string>> = {
  ground_ball: "ground balls",
  line_drive: "line drives",
  fly_ball: "fly balls",
  popup: "popups",
};

const EVENT_PHRASE: Readonly<Record<string, string>> = {
  home_run: "a home run",
  triple: "a triple",
  double: "a double",
  single: "a single",
};

const MONTH_DAY = new Intl.DateTimeFormat("en-US", {
  month: "long",
  day: "numeric",
  timeZone: "UTC",
});
const MONTH_DAY_YEAR = new Intl.DateTimeFormat("en-US", {
  month: "long",
  day: "numeric",
  year: "numeric",
  timeZone: "UTC",
});

/** Batted-ball type shares, ranked share desc then type name; types outside the four are skipped. */
export function rankBattedTypes(
  balls: readonly BattedBallRow[],
): { type: string; share: number }[] {
  const typed = balls.filter((b) => BB_PLURAL[b.bbType] != null);
  if (typed.length === 0) return [];
  const counts = new Map<string, number>();
  for (const b of typed) counts.set(b.bbType, (counts.get(b.bbType) ?? 0) + 1);
  return [...counts.entries()]
    .map(([type, c]) => ({ type, share: c / typed.length }))
    .sort((a, b) => b.share - a.share || a.type.localeCompare(b.type));
}

/**
 * The batter dek (rules B0-B4) over the UNFILTERED balls in play (the hook's newest-first window).
 * `currentSeason` decides whether the oldest date carries a year. `windowLimit` is the fetch limit:
 * a full window reads "his last N", a short one is everything on file.
 */
export function batterDek(
  balls: readonly BattedBallRow[],
  currentSeason: number,
  windowLimit: number,
): string | null {
  if (balls.length === 0) return null; // B0
  // B1
  const oldest = balls.reduce(
    (min, b) => (b.gameDate < min ? b.gameDate : min),
    balls[0]?.gameDate ?? "",
  );
  const oldestDate = new Date(`${oldest}T00:00:00Z`);
  const when =
    oldestDate.getUTCFullYear() === currentSeason
      ? MONTH_DAY.format(oldestDate)
      : MONTH_DAY_YEAR.format(oldestDate);
  // A full window reads "his last N"; a short one is everything on file.
  const lead =
    balls.length >= windowLimit
      ? `His last ${balls.length} balls in play reach`
      : balls.length === 1
        ? "His one ball in play on file reaches"
        : `His ${balls.length} balls in play on file reach`;
  const first = `${lead} back to ${when}.`;

  const clauses: string[] = [];
  // B2
  const hardest = balls
    .filter((b) => b.launchSpeedMph != null)
    .reduce<BattedBallRow | null>(
      (best, b) =>
        best == null || (b.launchSpeedMph ?? 0) > (best.launchSpeedMph ?? 0)
          ? b
          : best,
      null,
    );
  if (hardest?.launchSpeedMph != null) {
    const phrase = EVENT_PHRASE[hardest.events];
    clauses.push(
      `The hardest left the bat at ${hardest.launchSpeedMph.toFixed(1)} mph${phrase ? `, ${phrase}` : ""}`,
    );
  }
  // B3
  const typedCount = balls.filter((b) => BB_PLURAL[b.bbType] != null).length;
  const ranked = rankBattedTypes(balls);
  const [a, b] = ranked;
  if (typedCount >= DEK_TYPE_FLOOR && a != null) {
    const pa = sharePct(a.share);
    let clause: string;
    if (b != null && sharePct(b.share) === pa) {
      clause = `${BB_PLURAL[a.type]} and ${BB_PLURAL[b.type]} were level, at ${pa}% each`;
    } else {
      clause = `the most common were ${BB_PLURAL[a.type]}, at ${pa}%`;
    }
    clauses.push(clauses.length === 0 ? capitalize(clause) : clause);
  }
  // B4
  if (clauses.length === 0) return first;
  return `${first} ${clauses.join("; ")}.`;
}
