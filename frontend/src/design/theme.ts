/**
 * Mantine theme built from `./broadcast.ts` — the single source of truth
 * (decision [160], broadcast-package identity; supersedes the [133]
 * scouting-packet theme whose tokens.ts mirror was deleted in the cleanup PR).
 *
 * Two custom Mantine color ramps:
 *   - `gold`: 10-shade ramp around #F2A900 — the broadcast accent.
 *     `primaryColor: "gold"` (anchors, focus, CTAs).
 *   - `chrome`: 10-shade ramp around #0E1B33 — the telecast chrome navy.
 *
 * Headings render in Barlow Condensed so Mantine <Title> speaks the broadcast
 * voice automatically; body is Inter; mono is JetBrains Mono.
 */

import { createTheme, type MantineColorsTuple } from "@mantine/core";

import { colors, radii, shadows, spacing, typography } from "./broadcast";

// 10-shade ramp around #F2A900 broadcast gold.
// [0..3] tints, [4..6] core (6 = canonical), [7..9] ink shades for text-on-light.
const gold: MantineColorsTuple = [
  "#FFF6E0",
  "#FCE5B0",
  "#F9D37E",
  "#F6C14C",
  "#F4B526",
  "#F3AC0F",
  colors.gold, // 6 — canonical gold (#F2A900)
  "#C68A00",
  "#9A6B00", // 8 — goldInk (text-safe on light)
  "#6E4C00",
];

// 10-shade ramp around #0E1B33 chrome navy.
const chrome: MantineColorsTuple = [
  "#E7EBF3",
  "#C3CDE1",
  "#9AAACB",
  "#7187B2",
  "#4D6797",
  "#304C7C",
  "#1C3258",
  colors.chrome, // 7 — canonical chrome (#0E1B33)
  "#080F1F", // 8 — chromeDeep
  "#040810",
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
  primaryColor: "gold",
  colors: { gold, chrome },
  // Spacing maps to the 8-point grid: [4, 8, 12, 16, 24, 32, 48, 64, 96].
  spacing: {
    xs: `${spacing[0]}px`, // 4
    sm: `${spacing[1]}px`, // 8
    md: `${spacing[3]}px`, // 16
    lg: `${spacing[4]}px`, // 24
    xl: `${spacing[5]}px`, // 32
  },
  // Broadcast graphics are SHARP — everything sits on the 2px radius.
  radius: {
    xs: `${radii.sm}px`,
    sm: `${radii.sm}px`,
    md: `${radii.sm}px`,
    lg: `${radii.sm}px`,
    xl: `${radii.sm}px`,
  },
  shadows: {
    xs: "none",
    sm: "none",
    md: shadows.popover,
    lg: shadows.popover,
    xl: shadows.popover,
  },
  // Broadcast identity: white panels on the cool field; ink text.
  white: colors.panel,
  black: colors.ink,
});
