/**
 * Visual ground-truth for the design tokens (leaf 4a acceptance criterion).
 *
 * Renders one of every primitive — Inter body, JetBrains Mono number, Source Serif 4
 * display headline, the brick-red accent, and the surface ramp. Page-level leaves later
 * reference this in code review as "does my new component look consistent with this."
 *
 * Intentionally uses `tokens.colors.*` directly (not Tailwind classes) so the visual is
 * unambiguous about what the canonical values are.
 */

import { Box, Stack, Text, Title } from "@mantine/core";

import { colors, typography } from "../design/tokens";

export function TokenSampleCard() {
  return (
    <Box
      style={{
        backgroundColor: colors.bgElevated,
        color: colors.textDefault,
        border: `1px solid ${colors.bgEmphasis}`,
        borderRadius: 8,
        padding: 32,
        maxWidth: 720,
        fontFamily: typography.fonts.ui,
      }}
    >
      <Stack gap={24}>
        <Title order={1} style={{ color: colors.textStrong }}>
          The Bullpen — design tokens
        </Title>

        <Text size="md" style={{ color: colors.textDefault }}>
          UI body copy in Inter. Used everywhere the user reads prose, labels,
          table cells, and tooltip text. The warm-paper substrate (
          <span style={{ color: colors.accent, fontWeight: 600 }}>
            brick-red accent
          </span>
          ) shows here as the only chromatic note in body text.
        </Text>

        <Box
          style={{
            fontFamily: typography.fonts.data,
            fontSize: typography.scale[4], // 24
            color: colors.textStrong,
          }}
        >
          0.187 <span style={{ color: colors.textMuted }}>Brier</span>
          {"  "}|{"  "}
          0.0036 <span style={{ color: colors.textMuted }}>ECE</span>
          {"  "}|{"  "}
          15,234 <span style={{ color: colors.textMuted }}>preds</span>
        </Box>

        <Box style={{ display: "flex", gap: 12 }}>
          {[
            colors.bgBase,
            colors.bgSubtle,
            colors.bgEmphasis,
            colors.bgElevated,
          ].map((c) => (
            <Box
              key={c}
              style={{
                width: 64,
                height: 64,
                backgroundColor: c,
                border: `1px solid ${colors.bgEmphasis}`,
                borderRadius: 4,
              }}
              title={c}
            />
          ))}
        </Box>

        <Box style={{ display: "flex", gap: 8 }}>
          {colors.viz.categorical.map((c) => (
            <Box
              key={c}
              style={{
                width: 48,
                height: 24,
                backgroundColor: c,
                borderRadius: 4,
              }}
              title={c}
            />
          ))}
        </Box>

        <Text
          size="sm"
          style={{ fontFamily: typography.fonts.data, color: colors.textMuted }}
        >
          tokens.ts is the single source of truth — no hex codes outside
          src/design/**.
        </Text>
      </Stack>
    </Box>
  );
}
