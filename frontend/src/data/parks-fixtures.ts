/**
 * Fixture data for the Park Factors appendix (/parks, Stage 3c, decision [133]).
 *
 * Stage 3c replaces the editorial-data /parks Park Explorer (the slider-driven
 * fly-ball heatmap) with a scouting-report appendix: 30-row OVERVIEW StatTable
 * of league-wide park factors, a horizontal-scroll PARK SWITCHER strip of 30
 * mini heatmaps, and a SPOTLIGHT block on Coors Field with a generic field SVG,
 * a 12×12 landing-density heatmap, and a 5-block factor strip with key reads.
 *
 * Fixture-only — no live API in v1. Real-data wiring is out of scope; the shape
 * mirrors what a future GET /v1/parks/factors endpoint would return.
 *
 * FACTOR_METRIC:
 *   Park factors are conventionally expressed as ratios where 1.00 is league
 *   average (a park that neither helps nor hurts the metric). 1.18 HR = 18 %
 *   more home runs than average; 0.88 HR = 12 % fewer. The `closer-to-target`
 *   direction in cellColor tints both extremes red and keeps the neutral
 *   midpoint cream — readers see _how extreme_ a park plays, not _which way_
 *   the extreme falls. For the FACTOR_METRIC reference {0.80 .. 1.25}, the
 *   half-range is 0.25; a value with |Δ| > 0.16 (i.e. ≤ 0.84 or ≥ 1.16) lands
 *   in bad1/bad3 territory ("clearly distinctive").
 *
 * makeGrid:
 *   The 12×12 spotlight grid imports {@link makeGrid} from matchup-fixtures
 *   to keep the helper canonical. Mini-thumbs use their own 6×6
 *   {@link makeMiniGrid} helper because the size is different and the helper
 *   is trivial; duplicating it locally is cheaper than parameterizing the
 *   shared helper.
 */

import { makeGrid } from "./matchup-fixtures";
import type { MetricMeta } from "../design/cellColor";

// ── Types ────────────────────────────────────────────────────────────────────

export type ClimateKind = "ALTITUDE" | "DOME" | "COASTAL" | "TEMPERATE";

export type WindBias = "LF→RF" | "RF→LF" | "IN" | "OUT" | "NEUTRAL";

export type ParkRow = {
  /** Canonical 3-letter abbreviation (also the team abbr). */
  id: string;
  /** Full park name, uppercased for the row-label column. */
  parkName: string;
  /** Team 3-letter abbreviation. */
  team: string;
  /** Climate / venue kind. */
  climate: ClimateKind;
  /** HR factor (1.00 = league avg). */
  hr: number;
  /** BABIP factor. */
  babip: number;
  /** 3B factor. */
  triples: number;
  /** Wind-bias category. */
  wind: WindBias;
  /** K factor. */
  k: number;
  /** OPS factor. */
  ops: number;
};

export type ParkThumbnailDatum = {
  /** Park id (matches ParkRow.id). */
  id: string;
  /** 6×6 normalized [0,1] grid for the mini heatmap. */
  grid: number[][];
};

export type ParkSpotlightFactor = {
  /** Display key, e.g. "HR". */
  key: string;
  /** Display label, e.g. "HR FACTOR". */
  label: string;
  /** Raw factor value (used for cellColor + figure). */
  value: number;
  /** Caption format, e.g. "+18% vs lg". */
  caption: string;
};

export type ParkSpotlightWindBlock = {
  key: "WIND";
  label: "WIND BIAS";
  /** Display string like "LF → RF". */
  display: string;
  caption: string;
};

export type ParkSpotlightDatum = {
  /** Park id (matches ParkRow.id). */
  id: string;
  /** Full park name. */
  parkName: string;
  /** Numeric blocks + wind block, in display order. */
  factors: (ParkSpotlightFactor | ParkSpotlightWindBlock)[];
  /** 12×12 normalized landing-density grid. */
  landingGrid: number[][];
  /** 1–4 paragraphs of editorial prose. */
  keyReads: string[];
};

// ── Metric meta ──────────────────────────────────────────────────────────────

/**
 * Park-factor metric. closer-to-target with target 1.00.
 *
 * Reference distribution is symmetric around 1.00 with the empirical spread
 * we see across the 30 MLB parks: ~0.80 to ~1.25 for the most extreme single
 * factor across HR / BABIP / 3B / K / OPS. Closer-to-target inversion means
 * the neutral cream stop is the visual peak (1.00 = average) and both tails
 * tint red proportionally to distance.
 */
export const FACTOR_METRIC: MetricMeta = {
  key: "parkFactor",
  direction: "closer-to-target",
  reference: { min: 0.8, p25: 0.93, median: 1.0, p75: 1.07, max: 1.25 },
};

// ── Meta ─────────────────────────────────────────────────────────────────────

export const PARKS_META = {
  issueDate: "Wed · May 30, 2026",
  edition: "2026.05.30",
  issuedAt: "19:05 ET",
  dataWindow: "DATA WINDOW 2023 — 2025",
  modelTag: "MODEL park_factor_v2",
  sampleN: 437_210,
  methodologyLine:
    "3-yr rolling window · park-and-batter-mix adjusted · 2023–2025 · n=437,210 · sample-size-weighted shrinkage to 1.00",
  buildSha: "b1b62ec",
  buildDate: "2026.05.30",
} as const;

// ── 30 park rows ─────────────────────────────────────────────────────────────

/**
 * Every MLB park with realistic conditionally-formatted factor values.
 *
 * The factor anchors (COL/BOS/NYY/SD/HOU/TB/MIA/TEX/ARI/TOR) match published
 * Statcast / Baseball-Savant park-factor norms for 2023–2025. The remaining
 * 20 parks cluster around 1.00 ± 0.06 with WIND + CLIMATE matched to real
 * venue geography. A handful (CHC at Wrigley, CIN at Great American, KC,
 * SEA, SF) land at least one factor in the visibly-tinted extreme range so
 * the overview table's cellColor heatmap is not entirely cream.
 */
export const PARK_ROWS: ParkRow[] = [
  // Anchors
  {
    id: "COL",
    parkName: "COORS FIELD",
    team: "COL",
    climate: "ALTITUDE",
    hr: 1.18,
    babip: 1.12,
    triples: 1.42,
    wind: "LF→RF",
    k: 0.97,
    ops: 1.13,
  },
  {
    id: "BOS",
    parkName: "FENWAY PARK",
    team: "BOS",
    climate: "COASTAL",
    hr: 1.04,
    babip: 1.06,
    triples: 1.11,
    wind: "LF→RF",
    k: 0.98,
    ops: 1.04,
  },
  {
    id: "NYY",
    parkName: "YANKEE STADIUM",
    team: "NYY",
    climate: "COASTAL",
    hr: 1.16,
    babip: 0.99,
    triples: 0.84,
    wind: "OUT",
    k: 1.01,
    ops: 1.05,
  },
  {
    id: "SD",
    parkName: "PETCO PARK",
    team: "SD",
    climate: "COASTAL",
    hr: 0.88,
    babip: 0.96,
    triples: 0.91,
    wind: "IN",
    k: 1.02,
    ops: 0.94,
  },
  {
    id: "HOU",
    parkName: "MINUTE MAID PARK",
    team: "HOU",
    climate: "DOME",
    hr: 1.06,
    babip: 1.02,
    triples: 0.92,
    wind: "NEUTRAL",
    k: 0.99,
    ops: 1.03,
  },
  {
    id: "TB",
    parkName: "TROPICANA FIELD",
    team: "TB",
    climate: "DOME",
    hr: 0.95,
    babip: 0.97,
    triples: 0.88,
    wind: "NEUTRAL",
    k: 1.03,
    ops: 0.96,
  },
  {
    id: "MIA",
    parkName: "LOANDEPOT PARK",
    team: "MIA",
    climate: "DOME",
    hr: 0.92,
    babip: 0.94,
    triples: 1.05,
    wind: "NEUTRAL",
    k: 1.02,
    ops: 0.95,
  },
  {
    id: "TEX",
    parkName: "GLOBE LIFE FIELD",
    team: "TEX",
    climate: "DOME",
    hr: 1.02,
    babip: 1.0,
    triples: 0.96,
    wind: "NEUTRAL",
    k: 1.01,
    ops: 1.01,
  },
  {
    id: "ARI",
    parkName: "CHASE FIELD",
    team: "ARI",
    climate: "DOME",
    hr: 1.04,
    babip: 1.04,
    triples: 1.08,
    wind: "NEUTRAL",
    k: 0.99,
    ops: 1.03,
  },
  {
    id: "TOR",
    parkName: "ROGERS CENTRE",
    team: "TOR",
    climate: "DOME",
    hr: 1.05,
    babip: 1.0,
    triples: 0.94,
    wind: "NEUTRAL",
    k: 1.0,
    ops: 1.02,
  },
  // The other 20 — clustered around 1.00 ± 0.06 with a few visibly-extreme
  // signature reads so cellColor tinting shows up on the overview.
  {
    id: "CHC",
    parkName: "WRIGLEY FIELD",
    team: "CHC",
    climate: "TEMPERATE",
    hr: 1.05,
    babip: 1.03,
    triples: 1.18,
    wind: "OUT",
    k: 0.99,
    ops: 1.03,
  },
  {
    id: "CIN",
    parkName: "GREAT AMERICAN BALL PARK",
    team: "CIN",
    climate: "TEMPERATE",
    hr: 1.14,
    babip: 1.01,
    triples: 0.9,
    wind: "OUT",
    k: 0.99,
    ops: 1.05,
  },
  {
    id: "KC",
    parkName: "KAUFFMAN STADIUM",
    team: "KC",
    climate: "TEMPERATE",
    hr: 0.94,
    babip: 1.05,
    triples: 1.24,
    wind: "NEUTRAL",
    k: 1.0,
    ops: 0.99,
  },
  {
    id: "SEA",
    parkName: "T-MOBILE PARK",
    team: "SEA",
    climate: "COASTAL",
    hr: 0.9,
    babip: 0.95,
    triples: 0.93,
    wind: "IN",
    k: 1.05,
    ops: 0.93,
  },
  {
    id: "SF",
    parkName: "ORACLE PARK",
    team: "SF",
    climate: "COASTAL",
    hr: 0.86,
    babip: 0.96,
    triples: 1.16,
    wind: "RF→LF",
    k: 1.04,
    ops: 0.93,
  },
  {
    id: "ATL",
    parkName: "TRUIST PARK",
    team: "ATL",
    climate: "TEMPERATE",
    hr: 1.02,
    babip: 1.01,
    triples: 0.98,
    wind: "NEUTRAL",
    k: 1.0,
    ops: 1.01,
  },
  {
    id: "BAL",
    parkName: "ORIOLE PARK AT CAMDEN YARDS",
    team: "BAL",
    climate: "COASTAL",
    hr: 1.03,
    babip: 1.0,
    triples: 0.95,
    wind: "NEUTRAL",
    k: 1.0,
    ops: 1.01,
  },
  {
    id: "CWS",
    parkName: "GUARANTEED RATE FIELD",
    team: "CWS",
    climate: "TEMPERATE",
    hr: 1.07,
    babip: 1.01,
    triples: 0.92,
    wind: "OUT",
    k: 1.0,
    ops: 1.02,
  },
  {
    id: "CLE",
    parkName: "PROGRESSIVE FIELD",
    team: "CLE",
    climate: "TEMPERATE",
    hr: 0.99,
    babip: 1.0,
    triples: 0.98,
    wind: "NEUTRAL",
    k: 1.02,
    ops: 0.99,
  },
  {
    id: "DET",
    parkName: "COMERICA PARK",
    team: "DET",
    climate: "TEMPERATE",
    hr: 0.95,
    babip: 1.02,
    triples: 1.17,
    wind: "IN",
    k: 1.01,
    ops: 0.97,
  },
  {
    id: "LAA",
    parkName: "ANGEL STADIUM",
    team: "LAA",
    climate: "COASTAL",
    hr: 0.99,
    babip: 0.98,
    triples: 0.96,
    wind: "NEUTRAL",
    k: 1.01,
    ops: 0.98,
  },
  {
    id: "LAD",
    parkName: "DODGER STADIUM",
    team: "LAD",
    climate: "TEMPERATE",
    hr: 1.04,
    babip: 0.97,
    triples: 0.9,
    wind: "NEUTRAL",
    k: 1.02,
    ops: 1.0,
  },
  {
    id: "MIL",
    parkName: "AMERICAN FAMILY FIELD",
    team: "MIL",
    climate: "DOME",
    hr: 1.04,
    babip: 1.0,
    triples: 0.92,
    wind: "NEUTRAL",
    k: 1.0,
    ops: 1.01,
  },
  {
    id: "MIN",
    parkName: "TARGET FIELD",
    team: "MIN",
    climate: "TEMPERATE",
    hr: 1.0,
    babip: 1.02,
    triples: 1.04,
    wind: "NEUTRAL",
    k: 1.0,
    ops: 1.0,
  },
  {
    id: "NYM",
    parkName: "CITI FIELD",
    team: "NYM",
    climate: "COASTAL",
    hr: 0.96,
    babip: 0.99,
    triples: 1.0,
    wind: "OUT",
    k: 1.01,
    ops: 0.98,
  },
  {
    id: "OAK",
    parkName: "OAKLAND COLISEUM",
    team: "OAK",
    climate: "COASTAL",
    hr: 0.93,
    babip: 0.97,
    triples: 1.02,
    wind: "IN",
    k: 1.03,
    ops: 0.95,
  },
  {
    id: "PHI",
    parkName: "CITIZENS BANK PARK",
    team: "PHI",
    climate: "TEMPERATE",
    hr: 1.08,
    babip: 1.0,
    triples: 0.93,
    wind: "OUT",
    k: 0.99,
    ops: 1.03,
  },
  {
    id: "PIT",
    parkName: "PNC PARK",
    team: "PIT",
    climate: "TEMPERATE",
    hr: 0.93,
    babip: 1.01,
    triples: 1.06,
    wind: "NEUTRAL",
    k: 1.01,
    ops: 0.97,
  },
  {
    id: "STL",
    parkName: "BUSCH STADIUM",
    team: "STL",
    climate: "TEMPERATE",
    hr: 0.97,
    babip: 1.0,
    triples: 0.98,
    wind: "NEUTRAL",
    k: 1.01,
    ops: 0.99,
  },
  {
    id: "WSH",
    parkName: "NATIONALS PARK",
    team: "WSH",
    climate: "TEMPERATE",
    hr: 1.01,
    babip: 1.0,
    triples: 0.97,
    wind: "NEUTRAL",
    k: 1.0,
    ops: 1.0,
  },
];

// ── Mini-thumbnail grids ─────────────────────────────────────────────────────

/**
 * Compact 6×6 Gaussian grid for park-switcher thumbs. Normalised so the max
 * cell value is 1.0. We use a local helper (rather than the shared 12×12
 * makeGrid) because the smaller grid keeps the SVG cheap and the per-thumb
 * peak placements are intentional design choices, not data.
 */
function makeMiniGrid(
  peakRow: number,
  peakCol: number,
  sigma: number,
): number[][] {
  const size = 6;
  const grid: number[][] = [];
  let max = 0;
  for (let r = 0; r < size; r++) {
    const row: number[] = [];
    for (let c = 0; c < size; c++) {
      const dr = r - peakRow;
      const dc = c - peakCol;
      const v = Math.exp(-(dr * dr + dc * dc) / (2 * sigma * sigma));
      row.push(v);
      if (v > max) max = v;
    }
    grid.push(row);
  }
  if (max > 0) {
    for (let r = 0; r < size; r++) {
      for (let c = 0; c < size; c++) {
        grid[r][c] = grid[r][c] / max;
      }
    }
  }
  return grid;
}

/**
 * One {@link ParkThumbnailDatum} per row in {@link PARK_ROWS}. The peak
 * (row, col, sigma) for each thumb is hand-picked so each one reads as a
 * distinct landing-density signature even at 80×80 px. Order matches
 * {@link PARK_ROWS} so the switcher and the overview table line up.
 */
export const PARK_THUMBNAILS: ParkThumbnailDatum[] = [
  { id: "COL", grid: makeMiniGrid(2, 1, 1.8) }, // Rockpile-pull LF
  { id: "BOS", grid: makeMiniGrid(3, 0, 1.6) }, // Monster LF
  { id: "NYY", grid: makeMiniGrid(2, 5, 1.4) }, // Short porch RF
  { id: "SD", grid: makeMiniGrid(3, 3, 2.4) }, // dispersed
  { id: "HOU", grid: makeMiniGrid(2, 4, 1.6) }, // Crawford boxes LF
  { id: "TB", grid: makeMiniGrid(3, 3, 1.8) },
  { id: "MIA", grid: makeMiniGrid(3, 2, 1.8) },
  { id: "TEX", grid: makeMiniGrid(2, 3, 1.7) },
  { id: "ARI", grid: makeMiniGrid(2, 2, 1.7) },
  { id: "TOR", grid: makeMiniGrid(2, 3, 1.7) },
  { id: "CHC", grid: makeMiniGrid(3, 4, 1.5) }, // Wrigley wind-out RC
  { id: "CIN", grid: makeMiniGrid(2, 4, 1.5) }, // GABP RC bandbox
  { id: "KC", grid: makeMiniGrid(4, 2, 2.0) }, // big alleys → spread
  { id: "SEA", grid: makeMiniGrid(3, 1, 1.7) },
  { id: "SF", grid: makeMiniGrid(3, 0, 1.8) }, // RF→LF wind
  { id: "ATL", grid: makeMiniGrid(2, 3, 1.7) },
  { id: "BAL", grid: makeMiniGrid(2, 4, 1.6) },
  { id: "CWS", grid: makeMiniGrid(2, 2, 1.6) },
  { id: "CLE", grid: makeMiniGrid(3, 3, 1.9) },
  { id: "DET", grid: makeMiniGrid(4, 3, 2.0) }, // deep alleys
  { id: "LAA", grid: makeMiniGrid(3, 3, 1.8) },
  { id: "LAD", grid: makeMiniGrid(2, 4, 1.7) },
  { id: "MIL", grid: makeMiniGrid(2, 2, 1.7) },
  { id: "MIN", grid: makeMiniGrid(3, 2, 1.9) },
  { id: "NYM", grid: makeMiniGrid(3, 4, 1.7) },
  { id: "OAK", grid: makeMiniGrid(4, 2, 2.0) }, // foul territory
  { id: "PHI", grid: makeMiniGrid(2, 5, 1.5) },
  { id: "PIT", grid: makeMiniGrid(4, 3, 1.9) }, // big LF
  { id: "STL", grid: makeMiniGrid(3, 3, 1.8) },
  { id: "WSH", grid: makeMiniGrid(2, 3, 1.7) },
];

// ── Coors spotlight ──────────────────────────────────────────────────────────

/**
 * The /parks SPOTLIGHT block currently profiles Coors Field. The numeric
 * factors mirror the overview row; the wind block is editorial (LF→RF with
 * a prevailing 11 mph reading derived from Denver's typical summer pattern);
 * the 12×12 landing grid uses makeGrid(4, 4, 2.8) — peak in the LF-CF
 * triangle, which matches the Rockpile/triples-machine pattern.
 *
 * keyReads paragraphs are written to read as scouting prose, not analyst
 * footnotes. They reference real factors and avoid model-output filler.
 */
export const COORS_SPOTLIGHT: ParkSpotlightDatum = {
  id: "COL",
  parkName: "COORS FIELD",
  factors: [
    {
      key: "HR",
      label: "HR FACTOR",
      value: 1.18,
      caption: "+18% vs lg",
    },
    {
      key: "BABIP",
      label: "BABIP FACTOR",
      value: 1.12,
      caption: "+12% vs lg",
    },
    {
      key: "3B",
      label: "3B FACTOR",
      value: 1.42,
      caption: "+42% vs lg",
    },
    {
      key: "WIND",
      label: "WIND BIAS",
      display: "LF → RF",
      caption: "prevailing 11 mph",
    },
    {
      key: "OPS",
      label: "OPS FACTOR",
      value: 1.13,
      caption: "+13% vs lg",
    },
  ],
  landingGrid: makeGrid(4, 4, 2.8),
  keyReads: [
    "Coors plays as the league's most distinctive hitter park — 1.18 HR factor with elevation responsible for ~70% of the gap, but the LF gap (Rockpile) drives the league-leading 1.42 triples factor by carrying balls past defenders rather than over them.",
    "Despite the slugging surface, K factor lands at 0.97 — the thin air rewards two-strike contact more than it punishes it, a cross-current that distinguishes Coors from comparable bandbox profiles like Great American.",
  ],
};
