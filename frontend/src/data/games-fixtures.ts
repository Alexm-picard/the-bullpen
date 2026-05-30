/**
 * Fixture data for the Live Game page (/games) — Stage 3d.
 *
 * /games is the in-game live update version of the Matchup Report. v1 is
 * fixture-only — no API calls (the api/games.ts polling hooks are reserved
 * for the per-game-detail /games/:id leaf, not this slate-style page). The
 * shapes below extend api/games.ts where helpful (LivePitchRow) so the
 * eventual page swap from fixture → live data is a localised change.
 *
 * One game is fully populated: NYY @ DET, bottom 5th, 4–2 NYY, runners on
 * first and second, 1 out. Skubal on the mound, Judge at the plate. Twenty
 * recent pitches drawn from a realistic Skubal arsenal (FF / SL / CB / CH).
 * Predicted-vs-observed agreement runs ~78% of the time (16 ✓, 4 ✗) which
 * matches the model agreement chip shown in the GameStateStrip.
 *
 * Other games are pulled from home-fixtures.ts so the slate stays consistent
 * across /home and /games (the user shouldn't see different game lists on
 * different pages in v1).
 *
 * All numbers are plausible MLB-range but clearly fictionalised for the
 * redesign demo. The agreement-by-inning table values were chosen to give
 * the AGREED% column a realistic spread (.68 to .83) so the cellColor heat
 * map actually has signal — flat values would render as a neutral cream
 * stripe and waste the conditional-formatting primitive.
 */

import type { LivePitchRow } from "../api/games";
import type { MetricMeta } from "../design/cellColor";

import { TONIGHT_MATCHUPS } from "./home-fixtures";

// ── Types ────────────────────────────────────────────────────────────────────

export type LiveGameContext = {
  /** Issue date string for the eyebrow, e.g. "Wed · May 30, 2026". */
  issueDate: string;
  /** Two-line nameplate: line 1 "LIVE GAME", line 2 "{away} @ {home}". */
  awayTeam: string;
  homeTeam: string;
  awayScore: number;
  homeScore: number;
  /** Half-inning state, free-form, e.g. "BOT 5TH". */
  halfInning: string;
  /** Display string for the byline, e.g. "AARON JUDGE". */
  batterName: string;
  /** Display string for the byline, e.g. "TARIK SKUBAL". */
  pitcherName: string;
  /** Mono context line tail — "ISSUED 8:42 PM ET". */
  issuedAt: string;
  /** Model fleet label for the context line. */
  modelLabel: string;
  /** Build SHA + date for the footer. */
  buildSha: string;
  buildDate: string;
};

export type GameStateCell = {
  /** Saira uppercase label (e.g. "INNING"). */
  label: string;
  /** Mono value (e.g. "B5"). */
  value: string;
  /**
   * Optional emphasis for the value rendering. "scarlet-fill" wraps the value
   * in a scarlet pill; "scarlet-outline" gives an outline-only pill; "default"
   * is plain text. Used on the AGREEMENT cell to communicate model state at
   * a glance.
   */
  emphasis?: "default" | "scarlet-fill" | "scarlet-outline" | "silver-outline";
};

export type NowBattingHalf = {
  /** "BATTER" or "PITCHER". */
  role: "BATTER" | "PITCHER";
  /** Jersey number without #. */
  jersey: string;
  team: string;
  /** Full display name, e.g. "Aaron Judge". */
  name: string;
  position: string;
  hand: string;
  age: number;
  /** Single "this game" line, body-voice, e.g. "1-3, BB, HR in 4th". */
  thisGame: string;
};

export type NowBattingPairData = {
  batter: NowBattingHalf;
  pitcher: NowBattingHalf;
};

export type InningAgreementRow = {
  /** Inning number, displayed as the row label. */
  inning: number;
  pitches: number;
  /** 0..1 agreement rate. */
  agreed: number;
  inPlay: number;
  ks: number;
  swings: number;
};

export type OtherGameChip = {
  id: string;
  /** Visiting team. */
  away: string;
  /** Home team. */
  home: string;
  /** Free-form state string, e.g. "TOP 8TH · 2–1". */
  state: string;
  /** href to navigate to (e.g. /games/{id}). */
  href: string;
};

// ── Metric meta — agreement rate ────────────────────────────────────────────

/**
 * Agreement-rate metric for the AGREEMENT BY INNING table. Higher = better,
 * with the wide neutral band of cellColor's defaults keeping average innings
 * (≈ 70%) on the cream stop. Only sub-60% and 85%+ cross into the saturated
 * stops.
 */
export const AGREEMENT_METRIC: MetricMeta = {
  key: "agreed",
  direction: "higher-is-better",
  reference: { min: 0.4, p25: 0.6, median: 0.7, p75: 0.8, max: 0.95 },
};

// ── The featured live game (Judge vs Skubal) ────────────────────────────────

export const LIVE_GAME_CONTEXT: LiveGameContext = {
  issueDate: "Wed · May 30, 2026",
  awayTeam: "NYY",
  homeTeam: "DET",
  awayScore: 4,
  homeScore: 2,
  halfInning: "BOT 5TH",
  batterName: "Aaron Judge",
  pitcherName: "Tarik Skubal",
  issuedAt: "8:42 PM ET",
  modelLabel: "pitch_outcome_pre v3.2 LIVE",
  buildSha: "stage3d",
  buildDate: "2026.05.30",
};

export const GAME_STATE_CELLS: GameStateCell[] = [
  { label: "Inning", value: "B5" },
  { label: "Score", value: "NYY 4 — DET 2" },
  { label: "Count", value: "2–1, 1 OUT" },
  { label: "Runners", value: "1B · 2B" },
  { label: "Model Agr", value: "78% · 142/182", emphasis: "scarlet-fill" },
];

// ── Pitch log — 20 recent pitches in Skubal's arsenal ───────────────────────

const SKUBAL_ID = 669373;
const JUDGE_ID = 592450;

/** All 5 outcome classes the model emits, kept in registry order. */
const ALL_CLASSES = [
  "ball",
  "called_strike",
  "swinging_strike",
  "foul",
  "in_play",
] as const;

type OutcomeClass = (typeof ALL_CLASSES)[number];

/**
 * Build a 5-class distribution skewed toward a chosen winner. Probabilities
 * sum to 1.0; the non-winner mass is spread uniformly across the remaining
 * four classes (with a small bias toward foul/ball to feel realistic — both
 * are the most-common "nothing happens" outcomes).
 */
function dist(winner: OutcomeClass, winnerP: number): Record<string, number> {
  const others = ALL_CLASSES.filter((c) => c !== winner);
  const remaining = 1 - winnerP;
  // Bias 50% of the remaining mass to ball+foul, 50% to the rest.
  const biased: Record<string, number> = {};
  for (const c of others) {
    if (c === "ball" || c === "foul") {
      biased[c] = remaining * 0.3;
    } else {
      biased[c] = remaining * 0.13;
    }
  }
  // Normalize the others so they sum to exactly `remaining`.
  const othersSum = Object.values(biased).reduce((a, b) => a + b, 0);
  for (const c of others) biased[c] = (biased[c] / othersSum) * remaining;

  return { ...biased, [winner]: winnerP };
}

/**
 * Compose a LivePitchRow with the most-recent-first ordering convention
 * (cursor descends). Most fields match LivePitchRow exactly; we only fill
 * the ones the PitchCard actually reads.
 */
function pitch(
  cursor: number,
  inning: number,
  balls: number,
  strikes: number,
  outs: number,
  description: string,
  pitchType: string,
  velo: number,
  predictedWinner: OutcomeClass,
  predictedP: number,
): LivePitchRow {
  return {
    gameId: 778899,
    atBatIndex: 24 - Math.floor(cursor / 5),
    pitchNumber: (cursor % 7) + 1,
    cursor,
    ingestedAt: "2026-05-30T20:42:00Z",
    pitcherId: SKUBAL_ID,
    batterId: JUDGE_ID,
    description,
    pitchType,
    releaseSpeedMph: velo,
    plateXIn: null,
    plateZIn: null,
    balls,
    strikes,
    outs,
    inning,
    homeScore: 2,
    awayScore: 4,
    predictedClasses: dist(predictedWinner, predictedP),
    predictedWinner,
  };
}

/**
 * Twenty recent pitches, most-recent first. ~78% predicted == observed (16
 * agreements, 4 disagreements) so the model agreement chip checks out.
 *
 * Inning distribution: B5 ×7, T5 ×4, B4 ×5, T4 ×4 — gives the per-inning
 * agreement table enough sample-size variance to look credible.
 */
export const LIVE_PITCHES: LivePitchRow[] = [
  pitch(220, 5, 2, 1, 1, "foul", "FF", 97.2, "foul", 0.34),
  pitch(219, 5, 1, 1, 1, "called_strike", "SL", 88.1, "ball", 0.42),
  pitch(218, 5, 1, 0, 1, "ball", "CH", 86.9, "ball", 0.38),
  pitch(217, 5, 0, 0, 1, "ball", "FF", 96.8, "called_strike", 0.36),
  pitch(
    216,
    5,
    0,
    2,
    0,
    "swinging_strike",
    "SL",
    88.4,
    "swinging_strike",
    0.48,
  ),
  pitch(215, 5, 0, 1, 0, "foul", "FF", 97.0, "foul", 0.32),
  pitch(214, 5, 0, 0, 0, "called_strike", "FF", 97.4, "called_strike", 0.41),
  pitch(213, 4, 3, 2, 2, "in_play", "FF", 96.6, "in_play", 0.28),
  pitch(212, 4, 3, 1, 2, "foul", "SL", 87.9, "foul", 0.31),
  pitch(211, 4, 2, 1, 2, "ball", "CB", 81.2, "ball", 0.39),
  pitch(210, 4, 1, 1, 2, "called_strike", "FF", 97.1, "called_strike", 0.44),
  pitch(209, 4, 1, 0, 2, "ball", "CH", 87.2, "ball", 0.37),
  pitch(
    208,
    4,
    0,
    2,
    1,
    "swinging_strike",
    "SL",
    88.6,
    "swinging_strike",
    0.46,
  ),
  pitch(207, 4, 0, 1, 1, "foul", "FF", 96.9, "foul", 0.33),
  pitch(206, 4, 0, 0, 1, "called_strike", "FF", 97.3, "called_strike", 0.43),
  pitch(205, 4, 1, 2, 0, "in_play", "CH", 86.8, "swinging_strike", 0.39),
  pitch(204, 4, 1, 1, 0, "foul", "FF", 97.0, "foul", 0.34),
  pitch(203, 4, 1, 0, 0, "ball", "SL", 88.2, "ball", 0.41),
  pitch(202, 4, 0, 0, 0, "called_strike", "FF", 97.5, "called_strike", 0.45),
  pitch(
    201,
    3,
    2,
    2,
    2,
    "swinging_strike",
    "SL",
    88.8,
    "swinging_strike",
    0.51,
  ),
];

// ── Now-batting pair (compact ID block) ─────────────────────────────────────

export const NOW_BATTING: NowBattingPairData = {
  batter: {
    role: "BATTER",
    jersey: "99",
    team: "NYY",
    name: "Aaron Judge",
    position: "RF",
    hand: "R/R",
    age: 33,
    thisGame: "1-3, BB, HR in 4th (442 ft, LF)",
  },
  pitcher: {
    role: "PITCHER",
    jersey: "29",
    team: "DET",
    name: "Tarik Skubal",
    position: "SP",
    hand: "L/L",
    age: 28,
    thisGame: "4.1 IP · 87 pitches · 6 K · 2 ER",
  },
};

// ── Agreement by inning ─────────────────────────────────────────────────────

export const AGREEMENT_BY_INNING: InningAgreementRow[] = [
  { inning: 1, pitches: 38, agreed: 0.82, inPlay: 7, ks: 2, swings: 18 },
  { inning: 2, pitches: 31, agreed: 0.68, inPlay: 5, ks: 3, swings: 14 },
  { inning: 3, pitches: 42, agreed: 0.79, inPlay: 8, ks: 1, swings: 19 },
  { inning: 4, pitches: 29, agreed: 0.76, inPlay: 6, ks: 2, swings: 13 },
  { inning: 5, pitches: 42, agreed: 0.83, inPlay: 9, ks: 2, swings: 21 },
];

// ── Other-games switcher — pulled from the home slate, minus this game ──────

/**
 * The remaining live games from tonight's slate, plus a synthetic state line
 * so each chip carries a "what inning is it" payload. In v1 the chip points
 * to /games/{id} but the per-game-detail leaf still uses the api/games.ts
 * polling stack — that route is unchanged by this stage.
 */
export const OTHER_GAMES: OtherGameChip[] = [
  {
    away: "LAA",
    home: "HOU",
    state: "TOP 8TH · 2–1",
    id: "laa-hou-2026-05-30",
  },
  {
    away: "NYM",
    home: "PHI",
    state: "BOT 6TH · 3–3",
    id: "nym-phi-2026-05-30",
  },
  {
    away: "TOR",
    home: "BAL",
    state: "TOP 7TH · 1–4",
    id: "tor-bal-2026-05-30",
  },
  {
    away: "ATL",
    home: "MIA",
    state: "BOT 4TH · 5–2",
    id: "atl-mia-2026-05-30",
  },
  {
    away: "PIT",
    home: "MIL",
    state: "TOP 5TH · 0–0",
    id: "pit-mil-2026-05-30",
  },
  {
    away: "BOS",
    home: "CLE",
    state: "BOT 3RD · 2–1",
    id: "bos-cle-2026-05-30",
  },
].map((g) => ({ ...g, href: `/games/${g.id}` }));

// Sanity assertion — the other-games list should be a subset of the home slate.
// In dev this is a soft consistency check; in prod (build) it's a noop.
if (import.meta.env?.DEV) {
  const slateIds = new Set(TONIGHT_MATCHUPS.map((m) => m.id));
  for (const g of OTHER_GAMES) {
    if (!slateIds.has(g.id)) {
      console.warn(
        `[games-fixtures] other-game id ${g.id} not in TONIGHT_MATCHUPS slate`,
      );
    }
  }
}
