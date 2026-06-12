/**
 * BROADCAST-PACKAGE design tokens (decision [160]) - the v2 identity.
 *
 * The scouting-report metaphor stays; the rendering moves from "printed advance
 * packet" to a telecast graphics package: a LIGHT analytical field under DARK
 * navy broadcast chrome (masthead, scorebug, lower-thirds), one broadcast-gold
 * accent, and per-team color confined to edge bars and fills (never text).
 *
 * The governing rule (resolves [101]'s restraint warning): broadcast energy in
 * the FRAME, analytical restraint in the CELLS. Telecast chrome never appears
 * inside data tables.
 *
 * MIGRATION NOTE: this module lives BESIDE `tokens.ts` ([133] identity) while
 * screens migrate one PR at a time; components import from exactly one of the
 * two. When the last screen lands on broadcast, `tokens.ts` is deleted.
 *
 * **No hex codes outside src/design/.** Same `npm run lint:hex-codes`
 * discipline as v1.
 */

export const colors = {
  // ── The analytical field (light) ───────────────────────────────────────────
  /** Cool off-white app base - the field the data lives on. NOT warm cream. */
  field: "#F6F7F9",
  /** White panels / cards sitting on the field. */
  panel: "#FFFFFF",
  /** Alternating rows, quiet section backgrounds. */
  fieldSubtle: "#EDF0F4",
  /** 1px rules and dividers - borders over shadows, as ever. */
  rule: "#D9DEE7",

  // ── Broadcast chrome (dark) ────────────────────────────────────────────────
  /** Primary chrome - masthead, scorebug, lower-third bars. Deep telecast navy. */
  chrome: "#0E1B33",
  /** Deepest chrome - footer, scorebug team wells. */
  chromeDeep: "#080F1F",
  /** Hairlines + dividers ON chrome. */
  chromeEdge: "#26365C",
  /** Neutral secondary; also the fallback "team" color (see teamColors). */
  steel: "#8B95A7",

  // ── Accent ─────────────────────────────────────────────────────────────────
  /** Broadcast gold - LIVE states, emphasis FILLS, marks on chrome. */
  gold: "#F2A900",
  /** Darkened gold for TEXT/links on the light field (contrast-safe). */
  goldInk: "#9A6B00",

  // ── Text ───────────────────────────────────────────────────────────────────
  /** Near-black ink - headlines, high-emphasis. */
  ink: "#10141B",
  /** Body copy and default UI text. */
  text: "#272D38",
  /** Labels, captions, secondary metadata. */
  textMuted: "#5E6878",
  /** Text on chrome surfaces. */
  textOnChrome: "#F4F6FA",
  /** Muted text on chrome surfaces. */
  textOnChromeMuted: "#9DA9BF",

  // ── Conditional-format diverging ramp (the signature primitive survives) ───
  // Hues carried over from v1 so cellColor() reads identically; only the
  // neutral retunes from warm cream to the cool field tone.
  condFormat: {
    good3: "#2E8B57",
    good1: "#BFE3C6",
    neutral: "#EBEEF2",
    bad1: "#F6C9C2",
    bad3: "#D8483A",
  },

  // ── Sequential ramps (D3 / SVG) ────────────────────────────────────────────
  /** Pitch-location KDE: warm yellow -> gold -> ember. Re-anchored on gold. */
  heatWarm: ["#FFF6E0", "#FFD37E", "#F2A900", "#C3491F"] as const,
  /** Batted-ball density: green monochrome (unchanged). */
  spray: ["#EAF3E7", "#9CCB8E", "#4F9E55", "#1F5E32"] as const,

  // ── Colorblind-safe diverging alt (unchanged) ──────────────────────────────
  condFormatColorblind: {
    bad: "#B53D2C",
    good: "#2A8C8C",
  },

  // ── Data visualisation palettes ────────────────────────────────────────────
  viz: {
    /** Viridis 5-stop (decision [106]) - length MUST stay 5. */
    viridis: ["#440154", "#3B528B", "#21908C", "#5DC863", "#FDE725"] as const,
    /** Categorical 5-stop: chrome leads, gold second (scarlet retired). */
    categorical: [
      "#0E1B33",
      "#F2A900",
      "#2A8C8C",
      "#5E6770",
      "#8B5E9E",
    ] as const,
  },
} as const;

export const typography = {
  fonts: {
    /** Display voice - condensed broadcast insert type. ITALIC is the speed
     *  read for live states; heavy weights (600-800), uppercase for labels. */
    display: '"Barlow Condensed", "Arial Narrow", sans-serif',
    /** Body copy, UI text, long-form. Tabular figures globally via tnum. */
    body: '"Inter", system-ui, -apple-system, "Segoe UI", sans-serif',
    /** Stat figures, data tables, scorebug numerals. Tabular by construction. */
    mono: '"JetBrains Mono", ui-monospace, "SF Mono", Menlo, Consolas, monospace',
  },
  // 1.25 modular scale, 16px base (unchanged): 12, 14, 16, 20, 24, 32, 48, 64.
  scale: [12, 14, 16, 20, 24, 32, 48, 64] as const,
  lineHeights: {
    body: 1.5,
    /** Condensed display tightens further than v1. */
    display: 1.05,
  },
  weights: {
    regular: 400,
    medium: 500,
    semibold: 600,
    bold: 700,
    heavy: 800,
  },
} as const;

// 8-point grid (unchanged): 4, 8, 12, 16, 24, 32, 48, 64, 96.
export const spacing = [4, 8, 12, 16, 24, 32, 48, 64, 96] as const;

// [112] holds: functional only, 150-300ms. The ticker is the one continuous
// animation and it dies entirely under prefers-reduced-motion (broadcast.css).
export const motion = {
  durationsMs: { fast: 150, base: 200, slow: 300 },
  easing: "cubic-bezier(0.4, 0, 0.2, 1)",
} as const;

/** Diagonal-cut clip-paths - the broadcast panel/lower-third edge language. */
export const cuts = {
  /** Top-right corner cut for panels. */
  panelCorner:
    "polygon(0 0, calc(100% - 14px) 0, 100% 14px, 100% 100%, 0 100%)",
  /** Slanted right edge for lower-third bars. */
  lowerThirdEdge: "polygon(0 0, 100% 0, calc(100% - 16px) 100%, 0 100%)",
  /** Slanted both ends - scorebug center wedge. */
  wedge: "polygon(10px 0, 100% 0, calc(100% - 10px) 100%, 0 100%)",
} as const;

export const layouts = {
  /** Long-form editorial content (About, methodology). */
  editorialMaxWidth: 680,
  /** Analytical dashboards. */
  analyticalMaxWidth: 1200,
  /** Broadcast page column - slightly wider than the old report sheet. */
  broadcastMaxWidth: 1140,
  /** Left rail width (Ops, Park Explorer). */
  analyticalSidebar: 280,
} as const;

// Broadcast graphics are SHARP: square panels, tiny radii only where Mantine
// needs one, pill reserved for dots/chips.
export const radii = {
  none: 0,
  sm: 2,
  pill: 9999,
} as const;

export const shadows = {
  /** Panels prefer 1px rules; this exists for Mantine popover compatibility. */
  popover: "0 6px 16px rgba(8, 15, 31, 0.14), 0 3px 6px rgba(8, 15, 31, 0.08)",
} as const;
