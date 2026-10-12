/**
 * Mantine theme in [195]'s editorial terms. editorial.css is the source of
 * truth; this file only points Mantine at it (replaces the [160] broadcast
 * theme built from the retired broadcast.ts: gold primary, condensed headings).
 *
 * Fonts and surfaces are var(--ed-*) references, so they follow the [192]
 * paper / night toggle with no TS copy. Mantine's color ramps must be literal
 * colors (it derives hover and alpha variants from them), so the two ramps
 * below restate editorial.css hex values; keep them in step.
 *
 * Mantine is a thin layer here. What it still renders, and what this theme
 * styles: AppShell + Burger + Drawer (the shell, App.tsx), the route Loader,
 * Slider (/parks) and Autocomplete (/players, both re-skinned by .pk-* /
 * .pl-* classes), and VisuallyHidden. There is no admin or login page ([214]).
 */

import {
  createTheme,
  type CSSVariablesResolver,
  type MantineColorsTuple,
} from "@mantine/core";

import { motion } from "./motion";

// Neutral ink ramp, paper end to ink end, built from the editorial palette.
// Light scheme uses shade 8 (= --ed-ink, paper edition); dark uses shade 1
// (= --ed-ink, night edition). autoContrast picks the opposite end for text.
const ink: MantineColorsTuple = [
  "#eceee6", // 0 paper --ed-ground
  "#e6e9e0", // 1 night --ed-ink
  "#dcdfd5", // 2 paper --ed-track
  "#c4c8bb", // 3 paper --ed-rule
  "#959a8d", // 4 night --ed-ink3
  "#62666f", // 5 paper --ed-ink3
  "#4a4f59", // 6 paper --ed-ink2
  "#30343b", // 7
  "#16181d", // 8 paper --ed-ink
  "#0b0c0f", // 9
];

// Mantine's dark-scheme surface ramp ([0] text ... [7] body), set to the night
// edition so dark-scheme controls sit on the same grey-green ground.
const dark: MantineColorsTuple = [
  "#e6e9e0", // 0 text: night --ed-ink
  "#b0b4a8", // 1 night --ed-ink2
  "#959a8d", // 2 dimmed: night --ed-ink3
  "#6b6f64", // 3
  "#34382f", // 4 borders: night --ed-rule
  "#2a2d26", // 5
  "#22251f", // 6 inputs, dropdowns: night --ed-track
  "#141612", // 7 body: night --ed-ground
  "#0f110d", // 8
  "#0a0b09", // 9
];

export const theme = createTheme({
  fontFamily: "var(--ed-sans)",
  fontFamilyMonospace: "var(--ed-mono)",
  // Headings speak the editorial serif, on the .ed-h1 / .ed-h2 / .ed-h3 scale.
  headings: {
    fontFamily: "var(--ed-serif)",
    fontWeight: "700",
    sizes: {
      h1: { fontSize: "4rem", lineHeight: "1.02" },
      h2: { fontSize: "1.625rem", lineHeight: "1.1" },
      h3: { fontSize: "1.375rem", lineHeight: "1.15" },
      h4: { fontSize: "1.125rem", lineHeight: "1.2" },
    },
  },
  primaryColor: "ink",
  primaryShade: { light: 8, dark: 1 },
  // Filled ink controls get ground-colored text in both editions.
  autoContrast: true,
  colors: { ink, dark },
  // Deliberately false: Mantine's reduce-motion path sets every Transition
  // duration to 0. src/design/motion.css instead turns slides and pops into
  // in-place opacity fades under prefers-reduced-motion (gentler, not zero).
  respectReducedMotion: false,
  components: {
    // D4 (AA contrast): Mantine's default InputWrapper description gray (#868e96) sits near 3:1
    // on the ground - retune to editorial ink2 (7.0:1 paper, 8.6:1 night). Applied at the theme
    // so every input description inherits it.
    InputWrapper: {
      styles: {
        description: { color: "var(--ed-ink2)" },
      },
    },
    // Motion defaults for Mantine's own controls (craft pass 2026-10): the
    // burger snaps as press feedback before the drawer it summons settles.
    Burger: {
      defaultProps: {
        transitionDuration: motion.durationsMs.press,
        transitionTimingFunction: motion.easing.out,
      },
    },
  },
  // Spacing on the 8-point grid.
  spacing: {
    xs: "4px",
    sm: "8px",
    md: "16px",
    lg: "24px",
    xl: "32px",
  },
  // Editorial rules are square: controls sit at 0, larger surfaces at the 2px
  // the bar tracks use.
  defaultRadius: "sm",
  radius: {
    xs: "0",
    sm: "0",
    md: "2px",
    lg: "2px",
    xl: "2px",
  },
  // The one editorial elevation: the search dropdown's soft ink shadow.
  shadows: {
    xs: "none",
    sm: "none",
    md: "0 10px 24px -12px color-mix(in srgb, var(--ed-ink) 35%, transparent)",
    lg: "0 10px 24px -12px color-mix(in srgb, var(--ed-ink) 35%, transparent)",
    xl: "0 10px 24px -12px color-mix(in srgb, var(--ed-ink) 35%, transparent)",
  },
  // Literal, not var(): autoContrast puts `black` on a light fill in BOTH schemes (night's
  // primary shade is light), where var(--ed-ink) would resolve to night's light ink and vanish.
  // Mantine's light scheme only shows in the paper edition (ThemeProvider keeps them in step).
  white: "#eceee6", // paper --ed-ground
  black: "#16181d", // paper --ed-ink
});

/**
 * D4 (AA contrast) in editorial terms: Mantine's `c="dimmed"` text defaults to its gray-6
 * (#868e96, about 2.9:1 on the paper ground). Point it at editorial ink3 (4.9:1 paper, 6.3:1
 * night). Passed to MantineProvider in main.tsx (a provider prop, not a theme key).
 */
export const cssVariablesResolver: CSSVariablesResolver = () => ({
  variables: {},
  light: { "--mantine-color-dimmed": "var(--ed-ink3)" },
  dark: { "--mantine-color-dimmed": "var(--ed-ink3)" },
});
