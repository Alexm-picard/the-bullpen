/**
 * Mantine theme built from `./tokens.ts` — the single source of truth.
 *
 * Mantine's color system requires each named color to be a 10-shade tuple; we derive a brand
 * ramp from the stitching-red accent by lightening / darkening in OKLCH-ish steps (hand-tuned,
 * good enough at this scale; a designer would pick these more carefully). Other named colors
 * remain pulled from Mantine's defaults via `primaryColor: 'brand'`.
 *
 * Redesigned 2026-05-25 alongside tokens.ts:
 *  - brand ramp regenerated around #D7373F
 *  - headings.fontFamily moves from Source Serif 4 to the ui font (Inter); display weight
 *    handled at component level via fw=700 + tighter letterSpacing
 *  - spacing/radius maps regenerated from the 10-entry / tightened scales
 */

import { createTheme, type MantineColorsTuple } from "@mantine/core";

import { colors, radii, shadows, spacing, typography } from "./tokens";

// 10-shade ramp derived from the brand accent (#D7373F). Shades 0..3 are tints (UI surfaces +
// hover states), 4..6 are core (default + filled), 7..9 are shades (text + emphasis on light).
const brand: MantineColorsTuple = [
  "#FCEDEE",
  "#F8D1D4",
  "#F2A6AB",
  "#E97982",
  "#E2535E",
  "#DC424C",
  colors.accent, // 6 — the canonical accent (#D7373F)
  "#B62D34",
  "#94232A",
  "#741B20",
];

export const theme = createTheme({
  // Default text + UI font.
  fontFamily: typography.fonts.ui,
  // Display headings now share the ui font (Inter); weight + tracking carry the display feel.
  // Sizes pulled from `typography.scale` so the type ramp has a single source.
  headings: {
    fontFamily: typography.fonts.ui,
    fontWeight: "700",
    sizes: {
      h1: {
        fontSize: `${typography.scale[7]}px`, // 56
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
  // Mantine spacing maps to the token's [2, 4, 8, 12, 16, 24, 32, 48, 64, 96] scale.
  // xs/sm/md/lg/xl map to a useful subset; numeric `gap` props can index directly.
  spacing: {
    xs: `${spacing[1]}px`, // 4
    sm: `${spacing[2]}px`, // 8
    md: `${spacing[4]}px`, // 16
    lg: `${spacing[5]}px`, // 24
    xl: `${spacing[6]}px`, // 32
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
