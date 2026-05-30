/**
 * <DestinationCard> — one card in the "What's working today" 3-card grid on /home.
 *
 * A card is a `<Link>` to an internal route with: an eyebrow label, a short title, a
 * descriptive paragraph, and a "stat" line in JetBrains Mono (e.g. "5 parks · ECE 0.0036").
 *
 * The whole card is the clickable target — keyboard focus uses the underlying anchor.
 * Hover state nudges the accent border in via Mantine's `data-hover` pseudo.
 */
import { Box, Stack, Text } from "@mantine/core";
import { Link } from "react-router-dom";

import { colors, radii, spacing, typography } from "../../design/tokens";

export type DestinationCardProps = {
  to: string;
  eyebrow: string;
  title: string;
  description: string;
  /** Optional monospace stat shown at the bottom of the card. */
  stat?: string;
};

export function DestinationCard({
  to,
  eyebrow,
  title,
  description,
  stat,
}: DestinationCardProps) {
  return (
    <Box
      component={Link}
      to={to}
      style={{
        display: "block",
        textDecoration: "none",
        backgroundColor: colors.bgSheet,
        border: `1px solid ${colors.bgEmphasis}`,
        borderRadius: radii.md,
        padding: spacing[5], // 24
        transition: "border-color 150ms cubic-bezier(0.4, 0, 0.2, 1)",
        color: colors.textDefault,
        height: "100%",
      }}
      onMouseEnter={(e) => {
        (e.currentTarget as HTMLAnchorElement).style.borderColor =
          colors.scarlet;
      }}
      onMouseLeave={(e) => {
        (e.currentTarget as HTMLAnchorElement).style.borderColor =
          colors.bgEmphasis;
      }}
    >
      <Stack gap={spacing[2]} style={{ height: "100%" }}>
        <Text
          component="span"
          style={{
            fontFamily: typography.fonts.mono,
            fontSize: typography.scale[0], // 12
            fontWeight: typography.weights.semibold,
            letterSpacing: "0.12em",
            textTransform: "uppercase",
            color: colors.scarlet,
          }}
        >
          {eyebrow}
        </Text>
        <Text
          style={{
            fontFamily: typography.fonts.body,
            fontSize: typography.scale[4], // 24
            fontWeight: typography.weights.semibold,
            color: colors.textStrong,
            lineHeight: 1.25,
            letterSpacing: "-0.01em",
          }}
        >
          {title}
        </Text>
        <Text
          style={{
            fontFamily: typography.fonts.body,
            fontSize: typography.scale[2], // 16
            color: colors.textMuted,
            lineHeight: typography.lineHeights.body,
            flex: 1,
          }}
        >
          {description}
        </Text>
        {stat ? (
          <Text
            style={{
              fontFamily: typography.fonts.mono,
              fontSize: typography.scale[1], // 14
              color: colors.textDefault,
              marginTop: spacing[2],
              borderTop: `1px solid ${colors.bgEmphasis}`,
              paddingTop: spacing[3],
            }}
          >
            {stat}
          </Text>
        ) : null}
      </Stack>
    </Box>
  );
}
