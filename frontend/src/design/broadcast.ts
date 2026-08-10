/**
 * BROADCAST-PACKAGE design tokens (decision [160], dark-field per [191]/ADR-0017).
 *
 * [191] DARK-FIELD: the chrome palette is promoted to ground. The previous
 * light-field values survive as `paper` for the Model Guide page's reading
 * surface. This is a single committed look, NOT a dark-mode toggle (the rejected
 * "dark mode v1" was a user-facing switch; this uses the broadcast-dark values
 * the token layer already carried).
 *
 * The governing rule ([101]): broadcast energy in the FRAME, analytical restraint
 * in the CELLS. On the dark field, data tables use --panel backgrounds with
 * --text color; chrome appears in the nav, scorebug, and panel headers.
 *
 * **No hex codes outside src/design/.** Same `npm run lint:hex-codes` discipline.
 *
 * Values mirror tokens.css exactly. If you change one, change both.
 */

export const colors = {
  // ── Theme-switching ground ([192]) ─────────────────────────────────────────
  // These return CSS var() references so every component responds to the
  // light/dark toggle without individual changes. The actual values live in
  // tokens.css :root[data-theme] blocks.
  field: "var(--bp-field)",
  fieldHi: "var(--bp-field-hi)",
  panel: "var(--bp-panel)",
  panelEdge: "var(--bp-panel-edge)",
  rule: "var(--bp-rule)",
  ink: "var(--bp-ink)",
  text: "var(--bp-text)",
  textMuted: "var(--bp-muted)",
  goldInk: "var(--bp-gold-ink)",

  // ── Constant chrome (broadcast identity, same in both themes) ──────────────
  chrome: "#0E1B33",
  chromeDeep: "#080F1F",
  chromeEdge: "#26365C",
  textOnChrome: "#F4F6FA",
  textOnChromeMuted: "#9DA9BF",

  // ── Neutral ────────────────────────────────────────────────────────────────
  steel: "#8B95A7",

  // ── Accent (same in both themes) ───────────────────────────────────────────
  gold: "#F2A900",
  goldDeep: "#C98D00",

  // ── Live state ─────────────────────────────────────────────────────────────
  live: "#39D98A",

  // ── Heat ramp ──────────────────────────────────────────────────────────────
  heat: {
    hi: "#FF5A4E",
    mid: "#F2A900",
    lo: "#3D4F78",
  },

  // ── Paper surface (Model Guide, always light) ──────────────────────────────
  paper: "#F6F7F9",
  paperText: "#272D38",
  paperMuted: "#5E6878",
  paperRule: "#D9DEE7",

  // ── Legacy aliases ─────────────────────────────────────────────────────────
  fieldSubtle: "var(--bp-field-hi)",

  // ── Conditional-format (CSS var for direct backgrounds, static for cellColor) ──
  // Components using these as direct backgrounds get theme-switching via CSS vars.
  // cellColor (which needs RGB interpolation) uses condFormatHex instead.
  condFormat: {
    good3: "var(--bp-cond-good3)",
    good1: "var(--bp-cond-good1)",
    neutral: "var(--bp-cond-neutral)",
    bad1: "var(--bp-cond-bad1)",
    bad3: "var(--bp-cond-bad3)",
  },
  // Static hex for cellColor's RGB interpolation (dark-field values).
  condFormatDark: {
    good3: "#39A568",
    good1: "#1F4D3A",
    neutral: "#1C2A4A",
    bad1: "#4D2A28",
    bad3: "#E05A4C",
  },
  condFormatLight: {
    good3: "#39A568",
    good1: "#BFE3C6",
    neutral: "#EBEEF2",
    bad1: "#F6C9C2",
    bad3: "#E05A4C",
  },

  // ── Sequential ramps (D3 / SVG) ────────────────────────────────────────────
  heatWarm: ["#FFF6E0", "#FFD37E", "#F2A900", "#C3491F"] as const,
  spray: ["#EAF3E7", "#9CCB8E", "#4F9E55", "#1F5E32"] as const,

  // ── Colorblind-safe diverging alt (unchanged) ──────────────────────────────
  condFormatColorblind: {
    bad: "#B53D2C",
    good: "#2A8C8C",
  },

  // ── Data visualisation palettes ────────────────────────────────────────────
  viz: {
    viridis: ["#440154", "#3B528B", "#21908C", "#5DC863", "#FDE725"] as const,
    categorical: [
      "#0E1B33",
      "#F2A900",
      "#2A8C8C",
      "#5E6770",
      "#8B5E9E",
    ] as const,
  },
} as const;

/** Team-color data tokens (edge bars and fills, never text). */
export const teamColors: Record<string, string> = {
  AZ: "#A71930",
  ATH: "#003831",
  ATL: "#CE1141",
  BAL: "#DF4601",
  BOS: "#E0655F",
  CHC: "#0E3386",
  CIN: "#C6011F",
  CLE: "#00385D",
  COL: "#333366",
  CWS: "#27251F",
  DET: "#0C2340",
  HOU: "#002D62",
  KC: "#004687",
  LAA: "#BA0021",
  LAD: "#5B9BD5",
  MIA: "#00A3E0",
  MIL: "#FFC52F",
  MIN: "#002B5C",
  NYM: "#002D72",
  NYY: "#7BA2D6",
  PHI: "#E81828",
  PIT: "#FDB827",
  SD: "#2F241D",
  SEA: "#2FBFA8",
  SF: "#FD5A1E",
  STL: "#C41E3A",
  TB: "#8FBCE6",
  TEX: "#003278",
  TOR: "#134A8E",
  WSH: "#AB0003",
};

export function teamColor(abbrev: string): string {
  return teamColors[abbrev] ?? colors.steel;
}

export const typography = {
  fonts: {
    display: '"Barlow Condensed", "Arial Narrow", sans-serif',
    body: '"Inter", system-ui, -apple-system, "Segoe UI", sans-serif',
    mono: '"JetBrains Mono", ui-monospace, "SF Mono", Menlo, Consolas, monospace',
  },
  scale: [12, 14, 16, 20, 24, 32, 48, 64] as const,
  lineHeights: {
    body: 1.5,
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

export const spacing = [4, 8, 12, 16, 24, 32, 48, 64, 96] as const;

export const motion = {
  durationsMs: { fast: 150, base: 200, slow: 300 },
  easing: "cubic-bezier(0.4, 0, 0.2, 1)",
} as const;

export const cuts = {
  panelCorner:
    "polygon(0 0, calc(100% - 14px) 0, 100% 14px, 100% 100%, 0 100%)",
  lowerThirdEdge: "polygon(0 0, 100% 0, calc(100% - 16px) 100%, 0 100%)",
  wedge: "polygon(10px 0, 100% 0, calc(100% - 10px) 100%, 0 100%)",
  /** Angled chip - gold tag with slanted right edge. */
  chip: "polygon(0 0, 100% 0, calc(100% - 7px) 100%, 0 100%)",
} as const;

export const layouts = {
  editorialMaxWidth: 680,
  analyticalMaxWidth: 1200,
  broadcastMaxWidth: 1140,
  analyticalSidebar: 280,
} as const;

export const radii = {
  none: 0,
  sm: 2,
  pill: 9999,
} as const;

export const shadows = {
  popover: "0 6px 16px rgba(8, 15, 31, 0.14), 0 3px 6px rgba(8, 15, 31, 0.08)",
  /** Deep shadow for canvas/card elevation on the dark field. */
  canvas: "0 24px 60px rgba(0, 0, 0, 0.45)",
} as const;
