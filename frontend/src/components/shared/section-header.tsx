/**
 * <SectionHeader> — the consistent header block at the top of each /home section.
 *
 * Renders: eyebrow (uppercase, mono, accent), then a large h2 title, optional muted
 * lede paragraph below. Used by all four sections of the home page so spacing reads
 * consistent at first paint.
 */
import { Stack, Text, Title } from "@mantine/core";

import { colors, spacing, typography } from "../../design/tokens";

export type SectionHeaderProps = {
  eyebrow: string;
  title: string;
  lede?: string;
};

export function SectionHeader({ eyebrow, title, lede }: SectionHeaderProps) {
  return (
    <Stack gap={spacing[2]}>
      <Text
        component="span"
        style={{
          fontFamily: typography.fonts.data,
          fontSize: typography.scale[0], // 12
          fontWeight: typography.weights.semibold,
          letterSpacing: "0.12em",
          textTransform: "uppercase",
          color: colors.accent,
        }}
      >
        {eyebrow}
      </Text>
      <Title
        order={2}
        style={{
          fontFamily: typography.fonts.display,
          fontSize: typography.scale[5], // 32
          fontWeight: typography.weights.bold,
          color: colors.textStrong,
          letterSpacing: "-0.02em",
          lineHeight: 1.15,
          margin: 0,
        }}
      >
        {title}
      </Title>
      {lede ? (
        <Text
          style={{
            fontFamily: typography.fonts.ui,
            fontSize: typography.scale[3], // 20
            color: colors.textMuted,
            lineHeight: 1.45,
            maxWidth: 640,
          }}
        >
          {lede}
        </Text>
      ) : null}
    </Stack>
  );
}
