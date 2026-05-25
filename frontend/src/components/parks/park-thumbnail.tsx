/**
 * Park thumbnail (leaf 4c.2) — a stadium SVG outline with a single-color HR-probability
 * tint overlay and the park name + numeric P(HR) below. Deliberately minimal styling per the
 * leaf body — polish is 4c.4.
 *
 * Tint mapping: opacity ∝ probHr, fill = viridis[4] (the high-intensity stop). The leaf's
 * known edge case ("pure-tint clash with editorial tones") accepts this for v1.
 */
import { Group, Paper, Stack, Text } from "@mantine/core";

import { colors } from "../../design/tokens";

import { StadiumSvg } from "./stadium-svg";

export type ParkThumbnailProps = {
  parkId: string;
  name: string;
  probHr: number | null;
  isLoading?: boolean;
  size?: number;
};

const VIZ_TINT = colors.viz.viridis[4];

export function ParkThumbnail({
  parkId,
  name,
  probHr,
  isLoading = false,
  size = 160,
}: ParkThumbnailProps) {
  const opacity = clamp01(probHr ?? 0);
  return (
    <Paper p="xs" radius="md" withBorder shadow="0">
      <Stack gap={4} align="center">
        <StadiumSvg
          parkId={parkId}
          size={size}
          ariaLabel={`${name} — P(HR) ${opacity.toFixed(2)}`}
        >
          {probHr != null && !isLoading ? (
            <rect
              x={0}
              y={0}
              width={500}
              height={500}
              fill={VIZ_TINT}
              opacity={opacity}
              pointerEvents="none"
            />
          ) : null}
        </StadiumSvg>
        <Group gap={6} justify="space-between" w="100%">
          <Text size="sm" fw={500}>
            {parkId}
          </Text>
          <Text
            size="sm"
            ff="monospace"
            c={probHr == null ? "dimmed" : undefined}
          >
            {isLoading || probHr == null ? "—" : probHr.toFixed(3)}
          </Text>
        </Group>
        <Text size="xs" c="dimmed" lineClamp={1} w="100%" ta="left">
          {name}
        </Text>
      </Stack>
    </Paper>
  );
}

/** Clamp a number into [0, 1]; used for opacity which must stay valid. */
function clamp01(x: number): number {
  if (Number.isNaN(x)) return 0;
  if (x < 0) return 0;
  if (x > 1) return 1;
  return x;
}
