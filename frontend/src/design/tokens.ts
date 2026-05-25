/**
 * Editorial-data design tokens — single source of truth (leaf 4a, redesigned post-spec
 * approval 2026-05-25 for the home-page tech-product polish refresh).
 *
 * **No hex codes outside this file.** Components that need a color reach for `tokens.colors.*`
 * or a Tailwind utility that derives from this module. The `npm run lint:hex-codes` script
 * fails CI on any non-allowlisted `src/**` file containing a `#[0-9a-fA-F]{3,8}` literal.
 *
 * Both Tailwind 4 (via the `@theme` block in `index.css`) AND Mantine (via `theme.ts`)
 * consume these values; there's no second source for any color / spacing / size.
 *
 * Redesign notes (2026-05-25):
 *  - Accent shifted #B53D2C → #D7373F. Sharper, more chromatic anchor; reads as
 *    "stitching red" with more punch without crossing into "danger".
 *  - Substrate shifted #FAFAF7 → #FBFBFA. Slightly cooler off-white; gives the new
 *    accent a cleaner contrast field without losing the warm-paper feel.
 *  - Source Serif 4 dropped from `typography.fonts.display`. Display now maps to Inter
 *    at a heavier weight + tighter tracking. Reasoning: tech-product narrative reads
 *    cleaner when h1/h2 share Inter's geometry with body. This reverses an aspect of
 *    decision [102] (editorial-data identity included a display serif) — flagged for
 *    `/decide` follow-up; not silently locked here.
 *  - Typography scale grew to 9 entries to add a 56px tier for the hero h1.
 *  - Spacing scale grew to 10 entries to add a 2px micro-tier (slider rail interiors).
 *  - Radii tightened: sm 4→3, md 8→6, lg 12→10. Sharper rectangles read more
 *    "data-tool", less "rounded marketing".
 */

export const colors = {
  // Surfaces: warm-paper neutrals, cooler base than v1.
  bgBase: "#FBFBFA",
  bgElevated: "#FFFFFF",
  bgSubtle: "#F2F1ED",
  bgEmphasis: "#E8E6E0",

  // Text: 4-level grayscale ramp on the warm-paper substrate.
  textStrong: "#161513",
  textDefault: "#2D2B27",
  textMuted: "#6B6862",
  textSubtle: "#9A968F",

  // The single brand accent — used sparingly for CTAs + the registry-spine spine motif.
  // Stitching red — a sharper, more chromatic anchor than v1's brick.
  accent: "#D7373F",

  // Semantic chart palettes (decision [108]).
  viz: {
    // Viridis 5-stop — colorblind-safe, perceptually uniform, used for heatmaps + sequential.
    viridis: ["#440154", "#3B528B", "#21908C", "#5DC863", "#FDE725"],
    // Categorical 5-stop — for class breakdowns (5-class pitch outcome) and stand splits.
    // The accent leads at slot 0 so the spine motif and category-0 read identically.
    categorical: ["#D7373F", "#3B5BA9", "#5A7D3A", "#C28A2A", "#7C5B9E"],
  },

  // Status colors — drift PAGE / NOTICE / LOGGED + queue states. Each one earned its hex
  // through the design pass — no off-the-shelf "danger" red because we want the WARN to read
  // distinct from the brand accent (which IS red-adjacent).
  status: {
    success: "#3F7A3F", // queue SUCCEEDED, healthy
    warning: "#C28A2A", // drift NOTICE
    danger: "#9E2A2B", // drift PAGE / queue FAILED — distinct from accent
    info: "#3B5BA9", // generic informational
  },
} as const;

export const typography = {
  fonts: {
    // System-font Inter fallback; Mantine inherits this for default text.
    ui: '"Inter", system-ui, -apple-system, "Segoe UI", sans-serif',
    // Numbers + IDs + metrics. Monospace alignment matters for the Ops dashboard.
    data: '"JetBrains Mono", ui-monospace, "SF Mono", Menlo, Consolas, monospace',
    // Display headlines on Home + About + Park Explorer. Inter (heavy weight) replaces
    // Source Serif 4 — see file header for rationale.
    display: '"Inter", system-ui, -apple-system, "Segoe UI", sans-serif',
  },
  // 9-entry scale adds the 56px tier between 48 and 64 for the home-hero h1.
  scale: [12, 14, 16, 20, 24, 32, 40, 56, 72] as const,
  lineHeights: {
    body: 1.5,
    display: 1.1,
  },
  weights: {
    regular: 400,
    medium: 500,
    semibold: 600,
    bold: 700,
  },
} as const;

// 10-entry spacing scale adds a 2px micro-tier for slider rail interiors + 1px gaps.
export const spacing = [2, 4, 8, 12, 16, 24, 32, 48, 64, 96] as const;

export const motion = {
  durationsMs: { fast: 150, base: 200, slow: 300 },
  easing: "cubic-bezier(0.4, 0, 0.2, 1)",
} as const;

export const layouts = {
  /** Long-form editorial content (About, methodology). ~70-char measure at 16px, tightened
   *  from 720 to 680 to read tighter against the new bgBase. */
  editorialMaxWidth: 680,
  /** Analytical dashboards (Ops, Park Explorer, Game Live). */
  analyticalMaxWidth: 1200,
  /** Left rail width on Ops dashboard + Park Explorer. */
  analyticalSidebar: 280,
} as const;

// Tightened from v1 — sharper rectangles read more "data-tool" than v1's 4/8/12.
export const radii = {
  sm: 3,
  md: 6,
  lg: 10,
  pill: 9999,
} as const;

export const shadows = {
  /** Card-on-paper shadow — barely-there, no Material-style elevation. */
  card: "0 1px 2px rgba(22, 21, 19, 0.04), 0 1px 1px rgba(22, 21, 19, 0.03)",
  /** Modal / popover. */
  popover:
    "0 6px 16px rgba(22, 21, 19, 0.10), 0 3px 6px rgba(22, 21, 19, 0.06)",
} as const;
