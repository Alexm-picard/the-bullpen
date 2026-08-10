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
  // ── Ground (dark broadcast field, [191]) ──────────────────────────────────
  /** Deepest ground - the app base. Was chromeDeep in the light-field era. */
  field: "#080F1F",
  /** Elevated ground - nav bar, canvas bars. Was chrome. */
  fieldHi: "#0E1B33",
  /** Panel / card background on the dark field. New value. */
  panel: "#101D38",
  /** Panel borders, dividers on panels. Was chromeEdge. */
  panelEdge: "#26365C",
  /** 1px rules on the dark field. New value between chrome tones. */
  rule: "#1C2A4A",

  // ── Neutral ────────────────────────────────────────────────────────────────
  /** Neutral secondary; also the fallback "team" color (see teamColors). */
  steel: "#8B95A7",

  // ── Accent ─────────────────────────────────────────────────────────────────
  /** Broadcast gold - LIVE states, emphasis fills, marks, location underline. */
  gold: "#F2A900",
  /** Deep gold for text/links on the dark field (higher contrast than gold). */
  goldDeep: "#C98D00",

  // ── Text (on dark ground) ──────────────────────────────────────────────────
  /** High-emphasis text on the dark field - headlines, values. */
  ink: "#F4F6FA",
  /** Body copy and default UI text on the dark field. */
  text: "#C9D1E0",
  /** Labels, captions, secondary metadata. Near AA floor on --panel. */
  textMuted: "#7C8699",

  // ── Live state ─────────────────────────────────────────────────────────────
  /** Live-pulse dot, live badges. */
  live: "#39D98A",

  // ── Heat ramp (batted-ball / pitch-location) ───────────────────────────────
  heat: {
    hi: "#FF5A4E",
    mid: "#F2A900",
    lo: "#3D4F78",
  },

  // ── Paper surface (Model Guide reading page, [191]) ────────────────────────
  /** The Guide's light reading surface - was the app field in the light era. */
  paper: "#F6F7F9",
  /** Body text on paper. */
  paperText: "#272D38",
  /** Muted text on paper. */
  paperMuted: "#5E6878",
  /** Rules on paper. */
  paperRule: "#D9DEE7",

  // ── Legacy bridge (components still importing old names) ───────────────────
  /** @deprecated Use field */
  chrome: "#0E1B33",
  /** @deprecated Use field */
  chromeDeep: "#080F1F",
  /** @deprecated Use panelEdge */
  chromeEdge: "#26365C",
  /** @deprecated Use goldDeep */
  goldInk: "#C98D00",
  /** @deprecated Use ink */
  textOnChrome: "#F4F6FA",
  /** @deprecated Use textMuted */
  textOnChromeMuted: "#7C8699",
  /** @deprecated Use paper */
  fieldSubtle: "#0E1B33",

  // ── Conditional-format diverging ramp (the signature primitive survives) ───
  condFormat: {
    good3: "#39A568",
    good1: "#1F4D3A",
    neutral: "#1C2A4A",
    bad1: "#4D2A28",
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
