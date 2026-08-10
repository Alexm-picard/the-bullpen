import { useMemo } from "react";

import type { StatTablePalette } from "../shared/stat-table";
import type { KeyNotesPalette } from "../scouting/key-notes";
import { colors, radii, typography } from "../../design/broadcast";
import { rampFrom } from "../../design/cellColor";
import { useTheme } from "../../design/use-theme";

export function useStatTablePalette(): StatTablePalette {
  const { theme } = useTheme();
  return useMemo(
    () => ({
      border: colors.rule,
      surface: colors.panel,
      headerBg: colors.chrome,
      headerText: colors.textOnChrome,
      headerSortInactive: colors.steel,
      headerFontStyle: "italic" as const,
      labelBg: colors.fieldSubtle,
      labelText: colors.ink,
      valueText: colors.ink,
      mutedText: colors.textMuted,
      displayFont: typography.fonts.display,
      bodyFont: typography.fonts.body,
      monoFont: typography.fonts.mono,
      ramp: rampFrom(
        theme === "dark" ? colors.condFormat : colors.condFormatLight,
      ),
    }),
    [theme],
  );
}

export function useKeyNotesPalette(): KeyNotesPalette {
  return {
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
}
