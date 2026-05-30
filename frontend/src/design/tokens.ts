/**
 * Scouting-report / broadcast-graphics design tokens — single source of truth.
 * Supersedes the editorial-data (tech-product) tokens per decision [133].
 *
 * Visual identity: printed MLB advance-scouting packet crossed with a broadcast
 * lower-third. Dense conditionally-formatted stat sheets, bold condensed athletic
 * type, and a navy / scarlet / cream team-graphics palette.
 *
 * **No hex codes outside this file.** Components that need a color reach for
 * `tokens.colors.*` or a Tailwind utility that derives from this module. The
 * `npm run lint:hex-codes` script fails CI on any non-allowlisted `src/**` file
 * containing a `#[0-9a-fA-F]{3,8}` literal.
 *
 * Both Tailwind 4 (via the `@theme` block in `tokens.css`) AND Mantine (via
 * `theme.ts`) consume these values; there's no second source for any
 * color / spacing / size.
 *
 * Token-rename log vs. the 2026-05-25 editorial-data tokens:
 *   - `bgElevated` → `bgSheet` (report-sheet vocabulary)
 *   - `typography.fonts.ui` → `typography.fonts.body` (IBM Plex Sans)
 *   - `typography.fonts.data` → `typography.fonts.mono` (IBM Plex Mono)
 *   - `typography.fonts.display` → `typography.fonts.display` (Saira Condensed, new font)
 *   - `colors.accent` removed — replaced by `colors.scarlet` (the team-graphics accent)
 *   - `colors.textSubtle` removed — aliased to `colors.textMuted` at call sites
 *   - `colors.status.*` removed — orphan pages are being rebuilt in Stage 2+
 *   - New: `navy`, `navyDeep`, `silver`, `textOnNavy` (broadcast chrome)
 *   - New: `condFormat.*` (5-stop diverging ramp — the signature primitive)
 *   - New: `heatWarm`, `spray` (sequential ramps for D3 visualisations)
 *   - New: `condFormatColorblind` (brick↔teal toggle, a11y)
 *   - New: `viz.categorical` updated to navy/scarlet/teal/gold/slate
 *   - `viz.viridis` preserved at length 5 — consumed by ReliabilityDiagram
 *   - New: `layouts.reportSheetMaxWidth: 1100`
 */

export const colors = {
  // ── Backgrounds (printed-sheet palette) ───────────────────────────────────
  /** Warm cream base — the "paper" the report lives on. NOT pure white. */
  bgBase: "#F7F4EC",
  /** White report sheet / cards sitting on the cream base. */
  bgSheet: "#FFFFFF",
  /** Alternating rows, section backgrounds — slightly darker cream. */
  bgSubtle: "#EFEBE0",
  /** 1-px borders and dividers. Prefer borders over shadows per §8. */
  bgEmphasis: "#E0DBCD",

  // ── Broadcast chrome ──────────────────────────────────────────────────────
  /** Table header rows, lower-third bars, headlines on light. */
  navy: "#142A4C",
  /** Darkest chrome — footer, deepest containers. */
  navyDeep: "#0D1B33",
  /** Row-label column, secondary lower-third bars. */
  silver: "#C9CDD4",
  /** Primary accent — chevron bars, key marks, active state, links. */
  scarlet: "#C8102E",

  // ── Text ──────────────────────────────────────────────────────────────────
  /** Warm near-black — strong headlines, high-emphasis labels. */
  textStrong: "#14171C",
  /** Body copy and default UI text. */
  textDefault: "#2B2F36",
  /** Labels, captions, secondary metadata. */
  textMuted: "#6A6F78",
  /** Cream text intended for use on navy chrome backgrounds. */
  textOnNavy: "#F7F4EC",

  // ── Conditional-format diverging ramp ─────────────────────────────────────
  // THE signature visual. Maps metric percentile → cell background tint via
  // cellColor(value, metricMeta). The value text always renders on top (a11y
  // rule: color is never the sole carrier of meaning).
  condFormat: {
    good3: "#2E8B57", // strong green — clearly favorable
    good1: "#BFE3C6", // pale green
    neutral: "#EDEAE0", // cream-gray — league-average / no read
    bad1: "#F6C9C2", // pale red
    bad3: "#D8483A", // strong red — clearly unfavorable
  },

  // ── Sequential ramps (D3 / SVG — NOT Tailwind utilities) ──────────────────
  /** Pitch-location KDE: warm yellow → orange → scarlet. 4-stop array. */
  heatWarm: ["#FFF7E6", "#FFD37E", "#F08A24", "#C8102E"] as const,
  /** Batted-ball density: green monochrome. 4-stop array. */
  spray: ["#EAF3E7", "#9CCB8E", "#4F9E55", "#1F5E32"] as const,

  // ── Colorblind-safe diverging alt ─────────────────────────────────────────
  // Toggle for deuteranopia / protanopia — replaces the red↔green ramp.
  condFormatColorblind: {
    bad: "#B53D2C", // brick
    good: "#2A8C8C", // teal
  },

  // ── Data visualisation palettes ───────────────────────────────────────────
  viz: {
    /** Viridis 5-stop — colorblind-safe, perceptually uniform. Used by
     *  ReliabilityDiagram and any future sequential chart. Length MUST stay 5. */
    viridis: ["#440154", "#3B528B", "#21908C", "#5DC863", "#FDE725"] as const,
    /** Categorical 5-stop — multi-series, non-heat. Navy leads at slot 0 so
     *  the broadcast chrome and category-0 read identically. Length MUST stay 5. */
    categorical: [
      "#142A4C",
      "#C8102E",
      "#2A8C8C",
      "#C8910C",
      "#5E6770",
    ] as const,
  },
} as const;

export const typography = {
  fonts: {
    /** Display, titles, section heads, labels. Bold condensed athletic voice.
     *  Use heavy weights (600–800), uppercase for labels. */
    display: '"Saira Condensed", "Arial Narrow", sans-serif',
    /** Body copy, UI text, long-form (About). Tabular figures always on via
     *  `font-feature-settings: 'tnum' 1` (set globally in global.css). */
    body: '"IBM Plex Sans", system-ui, -apple-system, "Segoe UI", sans-serif',
    /** Stat figures, data tables, grades. Tabular by construction. */
    mono: '"IBM Plex Mono", ui-monospace, "SF Mono", Menlo, Consolas, monospace',
  },
  // 1.25 modular scale, 16px base: 12, 14, 16, 20, 24, 32, 48, 64.
  scale: [12, 14, 16, 20, 24, 32, 48, 64] as const,
  lineHeights: {
    /** Standard body text. */
    body: 1.5,
    /** Tighter leading for condensed display faces. */
    display: 1.1,
  },
  weights: {
    regular: 400,
    medium: 500,
    semibold: 600,
    bold: 700,
    heavy: 800,
  },
} as const;

// 8-point grid: 4, 8, 12, 16, 24, 32, 48, 64, 96.
export const spacing = [4, 8, 12, 16, 24, 32, 48, 64, 96] as const;

export const motion = {
  durationsMs: { fast: 150, base: 200, slow: 300 },
  easing: "cubic-bezier(0.4, 0, 0.2, 1)",
} as const;

export const layouts = {
  /** Long-form editorial content (About, methodology). */
  editorialMaxWidth: 680,
  /** Analytical dashboards (Ops, Park Explorer, Game Live). */
  analyticalMaxWidth: 1200,
  /** Report sheets (Matchup, Game, About) — bordered sheet with bold title block. */
  reportSheetMaxWidth: 1100,
  /** Left rail width on Ops dashboard + Park Explorer. */
  analyticalSidebar: 280,
} as const;

// Scouting-report identity: tighter radii read like a printed report, not a SaaS app.
export const radii = {
  sm: 2,
  md: 4,
  lg: 6,
  pill: 9999,
} as const;

export const shadows = {
  /** Card shadow — the scouting-report identity prefers 1-px borders over shadows.
   *  Keep here for Mantine compatibility; usage drops significantly vs. v1. */
  card: "0 1px 2px rgba(20, 23, 28, 0.04), 0 1px 1px rgba(20, 23, 28, 0.03)",
  /** Modal / popover. */
  popover:
    "0 6px 16px rgba(20, 23, 28, 0.10), 0 3px 6px rgba(20, 23, 28, 0.06)",
} as const;
