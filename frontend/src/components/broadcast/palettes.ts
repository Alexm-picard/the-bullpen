/**
 * Broadcast-identity palettes for the SHARED, identity-parameterized
 * components (decision [160]): StatTable + KeyNotes. Built once here so every
 * migrated screen (players, parks, ops, about) passes the same objects;
 * legacy screens keep each component's built-in default.
 */

import type { KeyNotesPalette } from "../scouting/key-notes";
import type { StatTablePalette } from "../shared/stat-table";
import { colors, radii, typography } from "../../design/broadcast";
import { rampFrom } from "../../design/cellColor";

export const broadcastStatTablePalette: StatTablePalette = {
  border: colors.rule,
  surface: colors.panel,
  headerBg: colors.chrome,
  headerText: colors.textOnChrome,
  headerSortInactive: colors.steel,
  headerFontStyle: "italic",
  labelBg: colors.fieldSubtle,
  labelText: colors.ink,
  valueText: colors.ink,
  mutedText: colors.textMuted,
  displayFont: typography.fonts.display,
  bodyFont: typography.fonts.body,
  monoFont: typography.fonts.mono,
  ramp: rampFrom(colors.condFormat),
};

export const broadcastKeyNotesPalette: KeyNotesPalette = {
  surface: colors.panel,
  border: colors.rule,
  headerBg: colors.chrome,
  headerText: colors.textOnChrome,
  headerFontStyle: "italic",
  noteText: colors.text,
  numberAccent: colors.goldInk,
  displayFont: typography.fonts.display,
  bodyFont: typography.fonts.body,
  monoFont: typography.fonts.mono,
  radius: radii.none,
};
