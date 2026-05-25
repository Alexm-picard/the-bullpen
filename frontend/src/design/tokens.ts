/**
 * Editorial-data design tokens — single source of truth (leaf 4a).
 *
 * **No hex codes outside this file.** Components that need a color reach for `tokens.colors.*`
 * or a Tailwind utility that derives from this module. The `npm run lint:hex-codes` script
 * fails CI on any non-allowlisted `src/**` file containing a `#[0-9a-fA-F]{3,8}` literal.
 *
 * Both Tailwind 4 (via the `@theme` block in `index.css`) AND Mantine (via `theme.ts`)
 * consume these values; there's no second source for any color / spacing / size.
 */

export const colors = {
  // Surfaces: warm-paper neutrals (decision [102] editorial-data identity).
  bgBase: "#FAFAF7",
  bgElevated: "#FFFFFF",
  bgSubtle: "#F2F1ED",
  bgEmphasis: "#E8E6E0",

  // Text: 4-level grayscale ramp on the warm-paper substrate.
  textStrong: "#161513",
  textDefault: "#2D2B27",
  textMuted: "#6B6862",
  textSubtle: "#9A968F",

  // The single brand accent — used sparingly for CTAs + the registry-spine spine motif.
  // Brick red, evokes baseball stitching without being a literal red.
  accent: "#B53D2C",

  // Semantic chart palettes (decision [108]).
  viz: {
    // Viridis 5-stop — colorblind-safe, perceptually uniform, used for heatmaps + sequential.
    viridis: ["#440154", "#3B528B", "#21908C", "#5DC863", "#FDE725"],
    // Categorical 5-stop — for class breakdowns (5-class pitch outcome) and stand splits.
    // Earthy palette that doesn't fight the warm-paper background.
    categorical: ["#3B5BA9", "#B53D2C", "#5A7D3A", "#C28A2A", "#7C5B9E"],
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
    // Editorial headlines on About + Park Explorer. ≥48px to read as "magazine display."
    display:
      '"Source Serif 4", "Source Serif Pro", Georgia, "Times New Roman", serif',
  },
  scale: [12, 14, 16, 20, 24, 32, 48, 64] as const,
  lineHeights: {
    body: 1.5,
    display: 1.2,
  },
  weights: {
    regular: 400,
    medium: 500,
    semibold: 600,
    bold: 700,
  },
} as const;

export const spacing = [4, 8, 12, 16, 24, 32, 48, 64, 96] as const;

export const motion = {
  durationsMs: { fast: 150, base: 200, slow: 300 },
  easing: "cubic-bezier(0.4, 0, 0.2, 1)",
} as const;

export const layouts = {
  /** Long-form editorial content (About, methodology). 70-character measure at 16px. */
  editorialMaxWidth: 720,
  /** Analytical dashboards (Ops, Park Explorer, Game Live). */
  analyticalMaxWidth: 1200,
  /** Left rail width on Ops dashboard + Park Explorer. */
  analyticalSidebar: 280,
} as const;

export const radii = {
  sm: 4,
  md: 8,
  lg: 12,
  pill: 9999,
} as const;

export const shadows = {
  /** Card-on-paper shadow — barely-there, no Material-style elevation. */
  card: "0 1px 2px rgba(22, 21, 19, 0.04), 0 1px 1px rgba(22, 21, 19, 0.03)",
  /** Modal / popover. */
  popover:
    "0 6px 16px rgba(22, 21, 19, 0.10), 0 3px 6px rgba(22, 21, 19, 0.06)",
} as const;
