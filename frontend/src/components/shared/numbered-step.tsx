/**
 * <NumberedStep> — one row in the "how a prediction gets here" methodology block.
 *
 * Layout: a JetBrains-Mono numeral (01–99) in the accent color, a short title, and a
 * one-sentence description. Numerals are typeset, not rendered as badges — keeps the
 * editorial-data feel.
 */
import { Box, Stack, Text } from "@mantine/core";

import { colors, spacing, typography } from "../../design/tokens";

export type NumberedStepProps = {
  /** 1..N — rendered as `01`, `02`… up to 99. */
  index: number;
  title: string;
  description: string;
};

export function NumberedStep({ index, title, description }: NumberedStepProps) {
  const padded = String(index).padStart(2, "0");
  return (
    <Box style={{ display: "flex", gap: spacing[4], alignItems: "baseline" }}>
      <Text
        component="span"
        style={{
          fontFamily: typography.fonts.data,
          fontSize: typography.scale[5], // 32
          fontWeight: typography.weights.medium,
          color: colors.accent,
          minWidth: 48,
          letterSpacing: "-0.02em",
        }}
      >
        {padded}
      </Text>
      <Stack gap={spacing[1]}>
        <Text
          style={{
            fontFamily: typography.fonts.ui,
            fontSize: typography.scale[3], // 20
            fontWeight: typography.weights.semibold,
            color: colors.textStrong,
            lineHeight: 1.3,
          }}
        >
          {title}
        </Text>
        <Text
          style={{
            fontFamily: typography.fonts.ui,
            fontSize: typography.scale[2], // 16
            color: colors.textMuted,
            lineHeight: typography.lineHeights.body,
          }}
        >
          {description}
        </Text>
      </Stack>
    </Box>
  );
}
