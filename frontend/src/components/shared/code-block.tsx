/**
 * <CodeBlock> — a dark-surface code block used on /home to show the real curl + JSON
 * response.
 *
 * Renders monospace text on a near-black background with subtle padding. Optional
 * label (e.g. "$ curl …") sits above the block in muted accent.
 *
 * No syntax highlighting — the tech-product feel comes from typography + restraint,
 * not from rainbow tokens.
 */
import { Box, Stack, Text } from "@mantine/core";

import { colors, radii, spacing, typography } from "../../design/tokens";

export type CodeBlockProps = {
  label?: string;
  /** Pre-formatted code body. Newlines preserved verbatim. */
  code: string;
  /** Optional accent on the label (e.g. method=POST). */
  accent?: string;
};

export function CodeBlock({ label, code, accent }: CodeBlockProps) {
  return (
    <Stack gap={spacing[1]}>
      {label ? (
        <Text
          style={{
            fontFamily: typography.fonts.data,
            fontSize: typography.scale[0], // 12
            color: accent ?? colors.textMuted,
            letterSpacing: "0.08em",
            textTransform: "uppercase",
            fontWeight: typography.weights.semibold,
          }}
        >
          {label}
        </Text>
      ) : null}
      <Box
        component="pre"
        style={{
          margin: 0,
          backgroundColor: colors.textStrong,
          color: colors.bgBase,
          padding: spacing[4],
          borderRadius: radii.md,
          fontFamily: typography.fonts.data,
          fontSize: typography.scale[1], // 14
          lineHeight: 1.55,
          overflowX: "auto",
          // Keep tabular figures so JSON column-aligns.
          fontFeatureSettings: '"tnum" 1',
        }}
      >
        <code style={{ fontFamily: "inherit" }}>{code}</code>
      </Box>
    </Stack>
  );
}
