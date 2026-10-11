/**
 * Pure helpers for the player file (SPEC-players sections 6 and 6.1): role routing, the words the
 * dateline and slug use, the browse index's division register, and the three lede figures.
 *
 * Everything here derives from the live hooks' responses; nothing is invented. A figure whose input
 * is missing returns `value: null`, which the page prints as an em-dash with "Not tracked in this
 * file." - never a 0.
 */
import type {
  ArsenalPitch,
  BattedBallRow,
  PlayerPredictionRow,
} from "../api/players";

import { pitchName } from "./pitch-type-prior";

// ── Role routing ────────────────────────────────────────────────────────────

/** What the profile shows: a pitcher's arsenal, a batter's balls in play, or (two-way) both. */
export type PlayerRole = "pitcher" | "batter" | "two-way";

const PITCHER_CODES = new Set(["P", "SP", "RP"]);

export function roleOf(primaryPosition: string): PlayerRole {
  const p = primaryPosition.trim().toUpperCase();
  if (p === "TWP") return "two-way";
  return PITCHER_CODES.has(p) ? "pitcher" : "batter";
}

const POSITION_WORDS: Readonly<Record<string, string>> = {
  P: "Pitcher",
  SP: "Starting pitcher",
  RP: "Relief pitcher",
  C: "Catcher",
  "1B": "First base",
  "2B": "Second base",
  "3B": "Third base",
  SS: "Shortstop",
  LF: "Left field",
  CF: "Center field",
  RF: "Right field",
  OF: "Outfield",
  DH: "Designated hitter",
  TWP: "Two-way player",
};

/** "Pitcher", "Right field"; an unknown code is printed as itself, never guessed. */
export function positionWord(primaryPosition: string): string {
  const p = primaryPosition.trim().toUpperCase();
  return POSITION_WORDS[p] ?? (p || "Player");
}

// ── Teams ───────────────────────────────────────────────────────────────────

/** Place names as a newspaper prints them; the two-team cities carry their league. */
export const TEAM_PLACE: Readonly<Record<string, string>> = {
  ATH: "Athletics",
  ATL: "Atlanta",
  AZ: "Arizona",
  BAL: "Baltimore",
  BOS: "Boston",
  CHC: "Chicago (NL)",
  CIN: "Cincinnati",
  CLE: "Cleveland",
  COL: "Colorado",
  CWS: "Chicago (AL)",
  DET: "Detroit",
  HOU: "Houston",
  KC: "Kansas City",
  LAA: "Los Angeles (AL)",
  LAD: "Los Angeles (NL)",
  MIA: "Miami",
  MIL: "Milwaukee",
  MIN: "Minnesota",
  NYM: "New York (NL)",
  NYY: "New York (AL)",
  PHI: "Philadelphia",
  PIT: "Pittsburgh",
  SD: "San Diego",
  SEA: "Seattle",
  SF: "San Francisco",
  STL: "St. Louis",
  TB: "Tampa Bay",
  TEX: "Texas",
  TOR: "Toronto",
  WSH: "Washington",
};

export function teamPlace(team: string): string | null {
  const t = team.trim().toUpperCase();
  if (!t) return null;
  return TEAM_PLACE[t] ?? t;
}

/** The browse index's register: six divisions of five, in the order a standings page prints. */
export const DIVISIONS: readonly { name: string; teams: readonly string[] }[] =
  [
    { name: "AL East", teams: ["BAL", "BOS", "NYY", "TB", "TOR"] },
    { name: "AL Central", teams: ["CWS", "CLE", "DET", "KC", "MIN"] },
    { name: "AL West", teams: ["ATH", "HOU", "LAA", "SEA", "TEX"] },
    { name: "NL East", teams: ["ATL", "MIA", "NYM", "PHI", "WSH"] },
    { name: "NL Central", teams: ["CHC", "CIN", "MIL", "PIT", "STL"] },
    { name: "NL West", teams: ["AZ", "COL", "LAD", "SD", "SF"] },
  ];

// ── Lede figures ────────────────────────────────────────────────────────────

export type Figure = {
  /** Printed value, or null when the input is missing (rendered as an em-dash). */
  value: string | null;
  label: string;
  basis: string;
};

const COUNT = new Intl.NumberFormat("en-US");
const NOT_TRACKED = "Not tracked in this file.";

export function pitcherFigures(arsenal: readonly ArsenalPitch[]): Figure[] {
  const tracked = arsenal.reduce((s, p) => s + p.count, 0);
  const hardest = arsenal.reduce<ArsenalPitch | null>(
    (best, p) => (best == null || p.veloMaxMph > best.veloMaxMph ? p : best),
    null,
  );
  return [
    {
      value: arsenal.length > 0 ? COUNT.format(tracked) : null,
      label: "Pitches tracked",
      basis: arsenal.length > 0 ? "All seasons on file." : NOT_TRACKED,
    },
    {
      value: arsenal.length > 0 ? String(arsenal.length) : null,
      label: "Pitch types on file",
      basis: arsenal.length > 0 ? "Each with a tracked velocity." : NOT_TRACKED,
    },
    {
      value: hardest ? hardest.veloMaxMph.toFixed(1) : null,
      label: "Hardest pitch, mph",
      basis: hardest
        ? `${pitchName(hardest.pitchType)}, the top of its range.`
        : NOT_TRACKED,
    },
  ];
}

/** "Of his last 200 balls in play." when the window is full; "Of his 87 balls in play on file." otherwise. */
export function battedWindowPhrase(n: number, windowLimit: number): string {
  if (n >= windowLimit) return `his last ${n} balls in play`;
  return n === 1
    ? "his one ball in play on file"
    : `his ${n} balls in play on file`;
}

export function batterFigures(
  balls: readonly BattedBallRow[],
  windowLimit: number,
): Figure[] {
  const speeds = balls
    .map((b) => b.launchSpeedMph)
    .filter((v): v is number => v != null);
  const dists = balls
    .map((b) => b.hitDistanceFt)
    .filter((v): v is number => v != null);
  const phrase = battedWindowPhrase(balls.length, windowLimit);
  return [
    {
      value: speeds.length > 0 ? Math.max(...speeds).toFixed(1) : null,
      label: "Hardest hit, mph",
      basis: speeds.length > 0 ? `Of ${phrase}.` : NOT_TRACKED,
    },
    {
      value:
        dists.length > 0 ? COUNT.format(Math.round(Math.max(...dists))) : null,
      label: "Longest, feet",
      basis: dists.length > 0 ? "Tracked distance, same balls." : NOT_TRACKED,
    },
    {
      value:
        speeds.length > 0
          ? `${Math.round((speeds.filter((v) => v >= 95).length / speeds.length) * 100)}%`
          : null,
      label: "Hit 95 mph or harder",
      basis:
        speeds.length > 0 ? "Of the balls with a tracked speed." : NOT_TRACKED,
    },
  ];
}

// ── Calls ───────────────────────────────────────────────────────────────────

/** How a pitch outcome class reads in a sentence ("a called strike", "in play"). */
const CALLED_PHRASE: Readonly<Record<string, string>> = {
  ball: "a ball",
  called_strike: "a called strike",
  swinging_strike: "a swinging strike",
  foul: "a foul",
  in_play: "in play",
  hit_by_pitch: "a hit-by-pitch",
};
const HAPPENED_PHRASE: Readonly<Record<string, string>> = {
  ...CALLED_PHRASE,
  in_play: "put in play",
};

function humanize(cls: string): string {
  return cls.replace(/_/g, " ");
}

export function calledPhrase(cls: string): string {
  return CALLED_PHRASE[cls] ?? humanize(cls);
}

export function happenedPhrase(cls: string): string {
  return HAPPENED_PHRASE[cls] ?? humanize(cls);
}

// ── Index positions ─────────────────────────────────────────────────────────

/** The primary positions the players dimension stores (P for every pitcher), as the index words them. */
export const POSITIONS: readonly { code: string; word: string }[] = [
  { code: "C", word: "Catcher" },
  { code: "1B", word: "First base" },
  { code: "2B", word: "Second base" },
  { code: "3B", word: "Third base" },
  { code: "SS", word: "Shortstop" },
  { code: "LF", word: "Left field" },
  { code: "CF", word: "Center field" },
  { code: "RF", word: "Right field" },
  { code: "DH", word: "Designated hitter" },
  { code: "P", word: "Pitcher" },
];

// ── Settled calls ───────────────────────────────────────────────────────────

/**
 * Only a settled call (a winner AND an observed outcome) can be graded: an unsettled call has no
 * truth to agree with yet, so it never reaches the account or the register count.
 */
export type SettledCall = {
  requestAt: string;
  called: string;
  prob: number | null;
  happened: string;
  agreed: boolean;
};

export function settledCalls(
  rows: readonly PlayerPredictionRow[],
): SettledCall[] {
  return rows
    .filter((r) => r.winnerClass != null && r.observedOutcome != null)
    .map((r) => ({
      requestAt: r.requestAt,
      called: r.winnerClass ?? "",
      prob: r.winnerProb,
      happened: r.observedOutcome ?? "",
      agreed: r.agreed === true,
    }));
}

// ── Batted-ball words ───────────────────────────────────────────────────────

/** An en dash for "not tracked" - a hyphen would read as a negative launch angle. */
export const DASH = "\u2013";

const EVENT_WORD: Readonly<Record<string, string>> = {
  home_run: "Home run",
  field_out: "Out",
  force_out: "Out",
  grounded_into_double_play: "Double play",
  double_play: "Double play",
  sac_fly: "Sacrifice fly",
  field_error: "Error",
};

export function eventWord(event: string): string {
  if (EVENT_WORD[event]) return EVENT_WORD[event];
  if (!event) return DASH;
  const t = event.replace(/_/g, " ");
  return t.charAt(0).toUpperCase() + t.slice(1);
}
