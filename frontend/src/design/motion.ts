/**
 * Motion tokens for the places a transition must be composed in TS (Mantine
 * transitionProps / defaultProps): the nav drawer, the burger, the theme's
 * SegmentedControl. Everything else reads the same values from CSS as
 * `var(--duration-*)` / `var(--ease-*)`, declared in editorial.css :root.
 * Mirror: if you change a value here, change it there (motion.test.ts checks).
 *
 * `easing.standard` is the [112] default and stays the curve for color
 * cross-fades and value morphs. The strong curves beside it come from the
 * design-engineering craft pass (2026-10): `out` for entrances, exits and press
 * feedback, `inOut` for elements moving between on-screen positions, `drawer`
 * for sheets. Every duration sits inside [112]'s 150-300ms band; `press` is the
 * one exception below it (feedback must land before the finger lifts).
 *
 * Moved here from the retired broadcast.ts (decision [212] follow-up); values
 * are unchanged.
 */
export const motion = {
  durationsMs: { press: 160, fast: 150, base: 200, enter: 240, slow: 300 },
  easing: {
    standard: "cubic-bezier(0.4, 0, 0.2, 1)",
    out: "cubic-bezier(0.23, 1, 0.32, 1)",
    inOut: "cubic-bezier(0.77, 0, 0.175, 1)",
    drawer: "cubic-bezier(0.32, 0.72, 0, 1)",
    color: "ease",
  },
} as const;
