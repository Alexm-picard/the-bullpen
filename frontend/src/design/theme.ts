/**
 * Mantine theme built from `./tokens.ts` — the single source of truth.
 *
 * Mantine's color system requires each named color to be a 10-shade tuple; we derive a brand
 * ramp from the brick-red accent by lightening / darkening in OKLCH-ish steps (hand-tuned,
 * good enough at this scale; a designer would pick these more carefully). Other named colors
 * remain pulled from Mantine's defaults via `primaryColor: 'brand'`.
 */

import { createTheme, type MantineColorsTuple } from "@mantine/core";

import { colors, radii, shadows, spacing, typography } from "./tokens";

// 10-shade ramp derived from the brand accent (#B53D2C). Shades 0..3 are tints (UI surfaces +
// hover states), 4..6 are core (default + filled), 7..9 are shades (text + emphasis on light).
const brand: MantineColorsTuple = [
  "#FBEDEA",
  "#F4D2CC",
  "#E9A89E",
  "#DC7F70",
  "#CD5C49",
  "#C24B37",
  colors.accent, // 6 — the canonical accent
  "#9D3525",
  "#822D1F",
  "#682418",
];

export const theme = createTheme({
  // Default text + UI font.
  fontFamily: typography.fonts.ui,
  // Display font for headings — h1/h2/h3 take Source Serif 4.
  // Sizes pulled from `typography.scale` so the type ramp has a single source.
  headings: {
    fontFamily: typography.fonts.display,
    sizes: {
      h1: {
        fontSize: `${typography.scale[6]}px`, // 48
        lineHeight: String(typography.lineHeights.display),
      },
      h2: {
        fontSize: `${typography.scale[5]}px`, // 32
        lineHeight: String(typography.lineHeights.display),
      },
      h3: {
        fontSize: `${typography.scale[4]}px`, // 24
        lineHeight: String(typography.lineHeights.display),
      },
      h4: {
        fontSize: `${typography.scale[3]}px`, // 20
        lineHeight: String(typography.lineHeights.display),
      },
    },
  },
  fontFamilyMonospace: typography.fonts.data,
  primaryColor: "brand",
  colors: { brand },
  // Mantine spacing maps to the token's [4, 8, 12, 16, 24, 32, 48, 64, 96] scale.
  spacing: {
    xs: `${spacing[0]}px`,
    sm: `${spacing[1]}px`,
    md: `${spacing[3]}px`,
    lg: `${spacing[4]}px`,
    xl: `${spacing[5]}px`,
  },
  radius: {
    xs: `${radii.sm}px`,
    sm: `${radii.sm}px`,
    md: `${radii.md}px`,
    lg: `${radii.lg}px`,
    xl: `${radii.lg}px`,
  },
  shadows: {
    xs: shadows.card,
    sm: shadows.card,
    md: shadows.popover,
    lg: shadows.popover,
    xl: shadows.popover,
  },
  // Editorial-data: warm-paper substrate, never pure white.
  white: colors.bgElevated,
  black: colors.textStrong,
});
