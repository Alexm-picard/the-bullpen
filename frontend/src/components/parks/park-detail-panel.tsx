/**
 * Park detail panel (leaf 4c.4). Shown when a thumbnail is hovered.
 *
 * The leaf body asked for a 5-class probability bar (out / 1B / 2B / 3B / HR).
 * The toy batted-ball model only emits P(HR) — the 5-class breakdown lives with
 * the pitch-outcome head, not batted balls. So instead this panel renders:
 *   - park name (display serif)
 *   - altitude
 *   - short / center / deepest fence depths
 *   - the P(HR) bar (single class) at large size with the Viridis-mapped fill
 * The 5-class breakdown will be wired in when the 2c.5 30-park MLP lands; the
 * panel shape stays the same — same Paper, same caption row, more bars.
 */
import { Box, Paper, Stack, Text, Title } from "@mantine/core";

import { colors } from "../../design/tokens";

import { viridis } from "./_viridis";

export type ParkDetail = {
  parkId: string;
  name: string;
  altitudeM: number | null;
  shortFenceFt: number;
  centerFenceFt: number | null;
  deepestFenceFt: number;
  probHr: number | null;
};

export type ParkDetailPanelProps = {
  detail: ParkDetail | null;
};

export function ParkDetailPanel({ detail }: ParkDetailPanelProps) {
  if (!detail) {
    return (
      <Paper p="md" radius="md" withBorder shadow="0">
        <Stack gap={4}>
          <Text c="dimmed" size="sm">
            Hover a park for detail.
          </Text>
        </Stack>
      </Paper>
    );
  }

  const p = detail.probHr ?? 0;
  const tint = viridis(Math.max(0, Math.min(1, p)));

  return (
    <Paper p="md" radius="md" withBorder shadow="0">
      <Stack gap={6}>
        <Title order={3} style={{ margin: 0 }}>
          {detail.name}
        </Title>
        <Text size="sm" c="dimmed">
          {detail.parkId}
          {detail.altitudeM != null
            ? ` · ${Math.round(detail.altitudeM)} m altitude`
            : ""}{" "}
          · LF/CF/RF fences {detail.shortFenceFt}/{detail.centerFenceFt ?? "—"}/
          {detail.deepestFenceFt} ft
        </Text>

        <Stack gap={2} style={{ marginTop: 8 }}>
          <Text size="xs" c="dimmed" tt="uppercase">
            P(HR) at this park
          </Text>
          <Box
            style={{
              position: "relative",
              height: 16,
              width: "100%",
              backgroundColor: colors.bgSubtle,
              borderRadius: 4,
              overflow: "hidden",
            }}
          >
            <Box
              style={{
                position: "absolute",
                left: 0,
                top: 0,
                bottom: 0,
                width: `${(p * 100).toFixed(1)}%`,
                backgroundColor: tint,
              }}
            />
          </Box>
          <Text
            size="sm"
            ff="monospace"
            style={{
              fontVariantNumeric: "tabular-nums",
              color: colors.textStrong,
            }}
          >
            {detail.probHr == null ? "—" : detail.probHr.toFixed(3)}
          </Text>
        </Stack>

        <Text size="xs" c="dimmed">
          5-class breakdown (out / 1B / 2B / 3B / HR) lights up when the 30-park
          MLP from Phase 2c.5 lands.
        </Text>
      </Stack>
    </Paper>
  );
}
