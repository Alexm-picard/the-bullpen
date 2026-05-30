/**
 * Mantine theme built from `./tokens.ts` — the single source of truth.
 *
 * Scouting-report / broadcast-graphics identity per decision [133].
 * Supersedes the editorial-data theme (2026-05-25).
 *
 * Two custom Mantine color ramps:
 *   - `scarlet`: 10-shade ramp around #C8102E — the primary accent (CTAs, key
 *     marks, active state). `primaryColor: "scarlet"`.
 *   - `navy`: 10-shade ramp around #142A4C — broadcast chrome (header rows,
 *     lower-third bars). Accessed directly in components, not as primaryColor.
 *
 * Headings use Saira Condensed (the condensed display face) at weight 700 so
 * Mantine <Title> components automatically render in the broadcast voice.
 * Body text uses IBM Plex Sans. Monospace uses IBM Plex Mono.
 */

import { createTheme, type MantineColorsTuple } from "@mantine/core";

import { colors, radii, shadows, spacing, typography } from "./tokens";

// 10-shade ramp from #C8102E scarlet.
// [0..3] tints (surfaces, hover), [4..6] core, [7..9] shades (emphasis on light).
const scarlet: MantineColorsTuple = [
  "#FCEAED",
  "#F8C5CC",
  "#F29AA4",
  "#EA6E7B",
  "#E04A5A",
  "#D62B40",
  colors.scarlet, // 6 — canonical scarlet (#C8102E)
  "#A60D26",
  "#840A1E",
  "#620717",
];

// 10-shade ramp from #142A4C navy.
// Used for table header rows, lower-third chrome, headline-on-light contexts.
const navy: MantineColorsTuple = [
  "#E8EDF5",
  "#C5D0E6",
  "#9DAFD3",
  "#748DBF",
  "#4F70AD",
  "#35589C",
  "#1F4289",
  colors.navy, // 7 — canonical navy (#142A4C)
  "#0D1B33",
  "#060E1A",
];

export const theme = createTheme({
  // Body and UI font.
  fontFamily: typography.fonts.body,
  // Mantine headings use the condensed display face for the broadcast voice.
  headings: {
    fontFamily: typography.fonts.display,
    fontWeight: "700",
    sizes: {
      h1: {
        fontSize: `${typography.scale[7]}px`, // 64
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
  fontFamilyMonospace: typography.fonts.mono,
  primaryColor: "scarlet",
  colors: { scarlet, navy },
  // Spacing maps to the 8-point grid: [4, 8, 12, 16, 24, 32, 48, 64, 96].
  spacing: {
    xs: `${spacing[0]}px`, // 4
    sm: `${spacing[1]}px`, // 8
    md: `${spacing[3]}px`, // 16
    lg: `${spacing[4]}px`, // 24
    xl: `${spacing[5]}px`, // 32
  },
  // Tighter radii — scouting packets read as printed reports, not SaaS cards.
  radius: {
    xs: `${radii.sm}px`, // 2
    sm: `${radii.sm}px`, // 2
    md: `${radii.md}px`, // 4
    lg: `${radii.lg}px`, // 6
    xl: `${radii.lg}px`, // 6
  },
  shadows: {
    xs: shadows.card,
    sm: shadows.card,
    md: shadows.popover,
    lg: shadows.popover,
    xl: shadows.popover,
  },
  // Report-sheet identity: white cards on cream base.
  white: colors.bgSheet,
  black: colors.textStrong,
});
