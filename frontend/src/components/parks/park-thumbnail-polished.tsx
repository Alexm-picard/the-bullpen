/**
 * Polished park thumbnail (leaf 4c.4) — replaces the 4c.2 basic thumbnail.
 *
 * Visual upgrades:
 *   - Viridis-mapped fill tint (not single-color), opacity scaled by P(HR).
 *   - Landing-zone dot positioned via a simple deterministic physics
 *     approximation from the canonical launch input.
 *   - Park name in display serif (Source Serif 4 via Mantine theme.headings).
 *   - P(HR) in JetBrains Mono with `tabular-nums` so column rows line up.
 *   - 1px border (decision [107]: borders, not shadows).
 *   - Hover state via Mantine `useHover` — emits onHover up so the page can
 *     render a detail panel keyed to the hovered park.
 *
 * Memoized: `React.memo` keyed on (parkId, probHr, isLoading, landingDistanceFt,
 * sprayAngleDeg). The "30 tiles re-render < 16 ms" target relies on this.
 */
import { Paper, Stack, Text, Title } from "@mantine/core";
import { useHover } from "@mantine/hooks";
import { memo, useEffect } from "react";

import { colors } from "../../design/tokens";

import { viridis } from "./_viridis";
import { StadiumSvg } from "./stadium-svg";

export type ParkThumbnailPolishedProps = {
  parkId: string;
  name: string;
  probHr: number | null;
  isLoading?: boolean;
  /** Deterministic landing-zone estimate (ft from home plate along the spray vector). */
  landingDistanceFt: number;
  /** Spray angle in degrees; + = LF (3B side), 0 = CF, - = RF. */
  sprayAngleDeg: number;
  /** Pixel size (square). */
  size?: number;
  /** Called when the mouse enters/leaves the tile so the page can show a detail panel. */
  onHoverChange?: (parkId: string | null) => void;
};

function ParkThumbnailPolishedInner({
  parkId,
  name,
  probHr,
  isLoading = false,
  landingDistanceFt,
  sprayAngleDeg,
  size = 160,
  onHoverChange,
}: ParkThumbnailPolishedProps) {
  const { hovered, ref } = useHover<HTMLDivElement>();

  useEffect(() => {
    if (!onHoverChange) return;
    onHoverChange(hovered ? parkId : null);
  }, [hovered, parkId, onHoverChange]);

  const p = clamp01(probHr ?? 0);
  const tint = probHr != null && !isLoading ? viridis(p) : null;
  const opacity = probHr != null ? 0.25 + 0.55 * p : 0;

  // Land at (250 - d·sin(spray), 480 - d·cos(spray)) in viewBox 0-500.
  const sprayRad = (sprayAngleDeg * Math.PI) / 180;
  const landX = 250 - landingDistanceFt * Math.sin(sprayRad);
  const landY = 480 - landingDistanceFt * Math.cos(sprayRad);

  return (
    <Paper
      ref={ref}
      p="xs"
      radius="md"
      withBorder
      shadow="0"
      style={{
        transition: "transform 150ms ease",
        transform: hovered ? "translateY(-2px)" : "translateY(0)",
        outline: hovered ? `1px solid ${colors.accent}` : "none",
      }}
    >
      <Stack gap={6} align="stretch">
        <StadiumSvg
          parkId={parkId}
          size={size}
          ariaLabel={`${name} — P(HR) ${p.toFixed(3)}`}
        >
          {tint != null ? (
            <rect
              x={0}
              y={0}
              width={500}
              height={500}
              fill={tint}
              opacity={opacity}
              pointerEvents="none"
            />
          ) : null}
          {probHr != null && !isLoading ? (
            <circle
              cx={landX}
              cy={landY}
              r={7}
              fill={colors.accent}
              stroke={colors.textStrong}
              strokeWidth={1.5}
              pointerEvents="none"
            />
          ) : null}
        </StadiumSvg>
        <Stack gap={0}>
          <Title order={5} style={{ margin: 0, lineHeight: 1.2 }}>
            {parkId}
          </Title>
          <Text size="xs" c="dimmed" lineClamp={1}>
            {name}
          </Text>
        </Stack>
        <Text
          size="sm"
          ff="monospace"
          style={{
            fontVariantNumeric: "tabular-nums",
            color: probHr == null ? colors.textMuted : colors.textStrong,
          }}
        >
          P(HR) {isLoading || probHr == null ? "—" : probHr.toFixed(3)}
        </Text>
      </Stack>
    </Paper>
  );
}

export const ParkThumbnailPolished = memo(ParkThumbnailPolishedInner);

function clamp01(x: number): number {
  if (Number.isNaN(x)) return 0;
  if (x < 0) return 0;
  if (x > 1) return 1;
  return x;
}
