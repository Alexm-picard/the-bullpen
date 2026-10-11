/**
 * Pure wording + ordering for the /games scoreboard ([195], SPEC-games-404 §4-§5). No React, so
 * every sentence the board prints is unit-testable on its own (slate-lines.test.ts).
 */
import type { SlateCard, SlateStatus } from "../../api/slate-view";

const COUNT_WORDS = [
  "no",
  "one",
  "two",
  "three",
  "four",
  "five",
  "six",
  "seven",
  "eight",
  "nine",
  "ten",
];

/** Spelled out through ten, digits above (the H1 reads as a sentence, not a stat). */
function countWord(n: number): string {
  return COUNT_WORDS[n] ?? String(n);
}

function capitalise(s: string): string {
  return s.charAt(0).toUpperCase() + s.slice(1);
}

/**
 * The line beside a game's team pair: its state in words. Never coloured, never a dot; a halted
 * game says what halted it.
 */
export function stateLine(card: SlateCard): string {
  if (card.status === "live") {
    const inning = card.inning ? `, inning ${card.inning}` : "";
    if (card.paused) {
      const reason = /^Delayed: (.+)$/.exec(card.detailedState ?? "");
      return reason
        ? `${reason[1]} delay${inning}`
        : `${card.detailedState ?? "Delayed"}${inning}`;
    }
    return card.inning ? `Inning ${card.inning}` : "In progress";
  }
  if (card.status === "scheduled") {
    const time =
      card.firstPitchEt && card.firstPitchEt !== "TBD"
        ? card.firstPitchEt
        : "Time TBD";
    return card.delayedStart ? `${time}, start delayed` : time;
  }
  if (card.postponed) return "Postponed";
  return card.inning && card.inning > 9
    ? `Final, ${card.inning} innings`
    : "Final";
}

export type BoardCounts = Record<SlateStatus, number> & { postponed: number };

/** Bucket counts plus the postponed games inside the final bucket. */
export function boardCounts(cards: readonly SlateCard[]): BoardCounts {
  const counts: BoardCounts = { live: 0, scheduled: 0, final: 0, postponed: 0 };
  for (const c of cards) {
    counts[c.status] += 1;
    if (c.postponed) counts.postponed += 1;
  }
  return counts;
}

/**
 * The page's H1, composed from the counts: "Four under way, five to come, three final."
 * Postponed games are not "final" (they were never played); a slate of nothing but postponements
 * says so.
 */
export function boardHeadline(counts: BoardCounts): string {
  const finals = counts.final - counts.postponed;
  const parts: string[] = [];
  if (counts.live) parts.push(`${countWord(counts.live)} under way`);
  if (counts.scheduled) parts.push(`${countWord(counts.scheduled)} to come`);
  if (finals) parts.push(`${countWord(finals)} final`);
  if (parts.length === 0) {
    return counts.postponed ? "Every game postponed." : "No games today.";
  }
  return `${capitalise(parts.join(", "))}.`;
}

/** The Final column's meta: "3 final, 1 postponed" (postponed games counted on their own). */
export function finalMeta(counts: BoardCounts): string {
  const finals = counts.final - counts.postponed;
  return counts.postponed
    ? `${finals} final, ${counts.postponed} postponed`
    : `${finals} final`;
}

function firstPitchMs(c: SlateCard): number {
  const t = c.firstPitchUtc ? Date.parse(c.firstPitchUtc) : Number.NaN;
  return Number.isNaN(t) ? Number.POSITIVE_INFINITY : t;
}

/**
 * The /games-local order, applied AFTER mergeSlate: "Still to come" by first pitch (unknown times
 * last, ties keep mergeSlate's order); live and final keep mergeSlate's order. Home does not call
 * this, so its slate order is unchanged (owner, 2026-10-11).
 */
export function orderForBoard(cards: readonly SlateCard[]): SlateCard[] {
  const scheduled = cards
    .map((c, i) => ({ c, i }))
    .filter(({ c }) => c.status === "scheduled")
    .sort((a, b) => firstPitchMs(a.c) - firstPitchMs(b.c) || a.i - b.i)
    .map(({ c }) => c);
  let next = 0;
  return cards.map((c) => (c.status === "scheduled" ? scheduled[next++]! : c));
}

/** "4:05 PM ET to 9:40 PM ET" (earliest..latest known first pitch, as home words it), or null. */
export function firstPitchWindow(cards: readonly SlateCard[]): string | null {
  const known = cards
    .filter(
      (c) => c.firstPitchUtc && c.firstPitchEt && c.firstPitchEt !== "TBD",
    )
    .sort((a, b) => firstPitchMs(a) - firstPitchMs(b));
  if (known.length === 0) return null;
  const lo = known[0]!.firstPitchEt!;
  const hi = known[known.length - 1]!.firstPitchEt!;
  return lo === hi ? lo : `${lo} to ${hi}`;
}
