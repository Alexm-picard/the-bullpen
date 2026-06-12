/**
 * Fixture data for the Tonight's Slate cover-sheet (/home) — Stage 3a.
 *
 * The home page is a fixture-only design surface in v1: no API calls. The
 * shape of each record matches what the eventual REST contract is likely to
 * deliver so the page swap from fixture → live data later is a localised
 * change inside the page, not a rewrite of the components.
 *
 * Reuses {@link PLAYERS} from `matchup-fixtures.ts` for the featured-matchup
 * card so the Judge/Skubal records stay deduplicated.
 *
 * EDGE metric:
 *   Signed expected-run differential vs the game's market line. Positive =
 *   home team favored over what the market implies. Range -2.5..+2.5, with a
 *   midpoint of 0. Treated as `higher-is-better` for cellColor: large positive
 *   reads as a strong green home pick, large negative as a strong red away
 *   pick, near-zero as neutral. That matches viewer intuition ("the model
 *   likes the home side") even though the underlying metric is symmetric.
 */

import type { MetricMeta } from "../design/cellColor";

// ── Types ────────────────────────────────────────────────────────────────────

export type Hand = "L" | "R";

export type TonightStarter = {
  /** Last name only — the slate table is compressed. */
  name: string;
  /** Throwing hand. */
  hand: Hand;
};

export type TonightMatchup = {
  /** Game id slug. */
  id: string;
  /** Away team abbreviation (e.g. "NYY"). */
  away: string;
  /** Home team abbreviation (e.g. "DET"). */
  home: string;
  /** First-pitch local time, ET-normalized. */
  timeEt: string;
  /** Away-side probable starter. */
  awayStarter: TonightStarter;
  /** Home-side probable starter. */
  homeStarter: TonightStarter;
  /** Signed expected-run differential. Positive = home favored over market. */
  edge: number;
  /** ≤ 50-char editorial summary of the model's read on this game. */
  topRead: string;
  /** Player slug the OPEN link routes to (/players/{batterId}). */
  batterId: string;
};

export type ModelChipState = "LIVE" | "SHADOW" | "OK";

export type ModelChip = {
  id: string;
  /** Saira display label on the top line. */
  label: string;
  /** Mono detail line below. */
  detail: string;
  /** State badge rendered on the right of the chip. */
  state: ModelChipState;
  /** Route the chip links to. */
  href: string;
};

// ── EDGE metric meta ─────────────────────────────────────────────────────────

/**
 * Symmetric reference around 0 for the EDGE column. `higher-is-better` so
 * positive (home-favored) reads green, negative reads red. The ramp's wide
 * neutral band (cellColor.ts §thresholds) keeps single-decimal-point edges
 * from looking decisive — only ≥ |1.0| crosses into the saturated stops.
 */
export const EDGE_METRIC: MetricMeta = {
  key: "edge",
  direction: "higher-is-better",
  reference: { min: -2.5, p25: -0.7, median: 0, p75: 0.7, max: 2.5 },
};

// ── Matchups (8 games, intentionally a lived-in slate) ───────────────────────

export const TONIGHT_MATCHUPS: TonightMatchup[] = [
  {
    id: "nyy-det-2026-05-30",
    away: "NYY",
    home: "DET",
    timeEt: "7:10 PM ET",
    awayStarter: { name: "Cole", hand: "R" },
    homeStarter: { name: "Skubal", hand: "L" },
    edge: 0.7,
    topRead: "Skubal slider whiff vs RHB",
    batterId: "judge_aaron",
  },
  {
    id: "laa-hou-2026-05-30",
    away: "LAA",
    home: "HOU",
    timeEt: "8:10 PM ET",
    awayStarter: { name: "Detmers", hand: "L" },
    homeStarter: { name: "Valdez", hand: "L" },
    edge: -1.2,
    topRead: "Trout xwOBA vs LHV climbing",
    batterId: "trout_mike",
  },
  {
    id: "nym-phi-2026-05-30",
    away: "NYM",
    home: "PHI",
    timeEt: "7:05 PM ET",
    awayStarter: { name: "Megill", hand: "R" },
    homeStarter: { name: "Wheeler", hand: "R" },
    edge: 0.3,
    topRead: "Soto pull-side authority",
    batterId: "soto_juan",
  },
  {
    id: "lad-sf-2026-05-30",
    away: "LAD",
    home: "SF",
    timeEt: "10:15 PM ET",
    awayStarter: { name: "Glasnow", hand: "R" },
    homeStarter: { name: "Webb", hand: "R" },
    edge: 1.4,
    topRead: "Ohtani vs sinkers low-arm-side",
    batterId: "ohtani_shohei",
  },
  {
    id: "tor-bal-2026-05-30",
    away: "TOR",
    home: "BAL",
    timeEt: "7:05 PM ET",
    awayStarter: { name: "Bassitt", hand: "R" },
    homeStarter: { name: "Burnes", hand: "R" },
    edge: -0.5,
    topRead: "Bichette swing-and-miss on cutters",
    batterId: "bichette_bo",
  },
  {
    id: "atl-mia-2026-05-30",
    away: "ATL",
    home: "MIA",
    timeEt: "6:40 PM ET",
    awayStarter: { name: "Strider", hand: "R" },
    homeStarter: { name: "Alcantara", hand: "R" },
    edge: 0.9,
    topRead: "Strider K-rate vs LHB anomaly",
    batterId: "judge_aaron",
  },
  {
    id: "pit-mil-2026-05-30",
    away: "PIT",
    home: "MIL",
    timeEt: "7:40 PM ET",
    awayStarter: { name: "Skenes", hand: "R" },
    homeStarter: { name: "Peralta", hand: "R" },
    edge: 0.2,
    topRead: "Skenes splinker vs LHB",
    batterId: "soto_juan",
  },
  {
    id: "bos-cle-2026-05-30",
    away: "BOS",
    home: "CLE",
    timeEt: "6:40 PM ET",
    awayStarter: { name: "Crochet", hand: "L" },
    homeStarter: { name: "Bibee", hand: "R" },
    edge: -0.8,
    topRead: "Crochet whiff regression watch",
    batterId: "trout_mike",
  },
];

// ── Model fleet chips (4) ────────────────────────────────────────────────────

export const MODEL_CHIPS: ModelChip[] = [
  {
    id: "pitch-outcome-live",
    label: "pitch_outcome_pre",
    detail: "v3.2",
    state: "LIVE",
    href: "/ops",
  },
  {
    id: "batted-ball-live",
    label: "batted_ball",
    detail: "v1.4",
    state: "LIVE",
    href: "/ops",
  },
  {
    id: "pitch-outcome-shadow",
    label: "pitch_outcome_pre",
    detail: "v3.3 cand.",
    state: "SHADOW",
    href: "/ops",
  },
  {
    id: "drift",
    label: "drift monitor",
    detail: "0 / 12 alerts",
    state: "OK",
    href: "/ops",
  },
];

// ── Featured matchup (Judge vs Skubal) ───────────────────────────────────────

/**
 * Key reads for the FEATURED MATCHUP card. Two numbered paragraphs, slate-pick
 * flavored (the Matchup Report's KeyNotes are model-eval flavored — different
 * voice, different audience moment).
 */
export const FEATURED_KEY_READS: [string, string] = [
  "Skubal's slider lives down-and-arm-side at 41% whiff, but Judge's 2-strike approach has tightened — only 6 strikeouts in his last 32 PA. The first-pitch four-seamer up is the at-bat decider.",
  "Edge model gives Detroit +0.7 runs with Skubal on the bump, but Judge's pull-side xwOBA against LHP velocity (.687 SLG vs LHP, n=184) is the homer-risk corridor. Watch the hung slider middle-in.",
];

/** Free-form context line shown in the featured card header strip. */
export const FEATURED_CONTEXT =
  "NYY @ DET · 7:10 PM ET · Comerica Park · Wed May 30, 2026";

// ── Issue meta (top masthead) ────────────────────────────────────────────────

export const ISSUE_META = {
  issueDate: "Wed · May 30, 2026",
  /** ET timestamp shown in the mono context line. */
  issuedAt: "19:05 ET",
  firstPitchWindow: "18:40 ET - 22:15 ET",
  buildSha: "b1b62ec",
  buildDate: "2026.05.30",
};
