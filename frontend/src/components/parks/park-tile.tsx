/**
 * <ParkTile> — one 180×180 tile in the grid view.
 *
 * Anatomy (top → bottom inside the tile):
 *   - Rank chip (top-left corner). Rank 1 gets the brand accent treatment;
 *     ranks 2–30 are muted bgEmphasis chips. The rank-1 chromatic moment is
 *     the one place on the page where accent reads as celebration rather than
 *     interaction affordance.
 *   - Park id (top-right corner) in JetBrains Mono, muted.
 *   - Stadium SVG centered, with the landing-zone dot overlaid in accent.
 *   - Park name (one line, truncated) under the SVG.
 *   - Thin probability bar at the very bottom of the tile.
 *
 * Click / Enter opens the detail modal — onSelect fires with the parkId.
 *
 * Memoized — the page re-renders 30 of these on every slider tick; isolating
 * the per-tile render keeps the drag responsive.
 */
import { Box, Paper, Text } from "@mantine/core";
import { memo, useState } from "react";

import { colors, radii, spacing, typography } from "../../design/tokens";
import { ProbBarThin } from "../shared/prob-bar-thin";

import { StadiumSvg } from "./stadium-svg";

export type ParkTileProps = {
  parkId: string;
  name: string;
  rank: number;
  probHr: number | null;
  isLoading?: boolean;
  /** Deterministic landing-zone estimate (ft from home plate). */
  landingDistanceFt: number;
  /** Spray angle in degrees; + = LF (3B side), 0 = CF, - = RF. */
  sprayAngleDeg: number;
  /** Tile pixel size (square, default 180). */
  size?: number;
  /** Fires when the tile is clicked or activated via keyboard. */
  onSelect: (parkId: string) => void;
};

function ParkTileInner({
  parkId,
  name,
  rank,
  probHr,
  isLoading = false,
  landingDistanceFt,
  sprayAngleDeg,
  size = 180,
  onSelect,
}: ParkTileProps) {
  const [hover, setHover] = useState(false);
  const [focused, setFocused] = useState(false);
  const active = hover || focused;
  const isLeader = rank === 1;

  // SVG width 500 = the field viewBox, but the inner stadium is centered at
  // (250, 480) with CF straight up. Landing-zone dot sits at the same coords.
  const sprayRad = (sprayAngleDeg * Math.PI) / 180;
  const landX = 250 - landingDistanceFt * Math.sin(sprayRad);
  const landY = 480 - landingDistanceFt * Math.cos(sprayRad);

  const svgSize = size - 56; // leave room for name + bar + rank/id chips

  return (
    <Paper
      role="button"
      tabIndex={0}
      aria-label={`Rank ${rank}: ${name} — P(HR) ${probHr == null ? "unknown" : probHr.toFixed(3)}. Press Enter for park detail.`}
      onMouseEnter={() => setHover(true)}
      onMouseLeave={() => setHover(false)}
      onFocus={() => setFocused(true)}
      onBlur={() => setFocused(false)}
      onClick={() => onSelect(parkId)}
      onKeyDown={(e) => {
        if (e.key === "Enter" || e.key === " ") {
          e.preventDefault();
          onSelect(parkId);
        }
      }}
      radius="md"
      style={{
        width: size,
        height: size,
        padding: spacing[3],
        backgroundColor: colors.bgSheet,
        border: `1px solid ${active ? colors.scarlet : colors.bgEmphasis}`,
        position: "relative",
        display: "flex",
        flexDirection: "column",
        gap: spacing[2],
        cursor: "pointer",
        transition:
          "border-color 150ms cubic-bezier(0.4, 0, 0.2, 1), transform 150ms cubic-bezier(0.4, 0, 0.2, 1)",
        transform: active ? "translateY(-1px)" : "translateY(0)",
      }}
    >
      {/* Top row: rank chip + park id */}
      <div
        style={{
          display: "flex",
          alignItems: "center",
          justifyContent: "space-between",
        }}
      >
        <Box
          style={{
            display: "inline-flex",
            alignItems: "center",
            justifyContent: "center",
            minWidth: 22,
            height: 18,
            padding: `0 ${spacing[1]}px`,
            borderRadius: radii.sm,
            backgroundColor: isLeader ? colors.scarlet : colors.bgEmphasis,
            color: isLeader ? colors.bgSheet : colors.textMuted,
            fontFamily: typography.fonts.mono,
            fontSize: typography.scale[0], // 12
            fontWeight: typography.weights.semibold,
            letterSpacing: "0.02em",
            lineHeight: 1,
          }}
        >
          {rank}
        </Box>
        <Text
          component="span"
          style={{
            fontFamily: typography.fonts.mono,
            fontSize: typography.scale[0], // 12
            color: colors.textMuted,
            letterSpacing: "0.06em",
          }}
        >
          {parkId}
        </Text>
      </div>

      {/* Stadium SVG centered */}
      <div
        style={{
          flex: 1,
          display: "flex",
          alignItems: "center",
          justifyContent: "center",
          minHeight: 0,
        }}
      >
        <StadiumSvg
          parkId={parkId}
          size={svgSize}
          ariaLabel={`${name} field outline, rank ${rank}`}
          color={active ? colors.textStrong : colors.textDefault}
        >
          {probHr != null && !isLoading ? (
            <circle
              cx={landX}
              cy={landY}
              r={9}
              fill={colors.scarlet}
              stroke={colors.bgSheet}
              strokeWidth={2}
              pointerEvents="none"
            />
          ) : null}
        </StadiumSvg>
      </div>

      {/* Bottom: park name + thin bar */}
      <div
        style={{
          display: "flex",
          flexDirection: "column",
          gap: spacing[1],
        }}
      >
        <Text
          style={{
            fontFamily: typography.fonts.body,
            fontSize: typography.scale[0], // 12
            color: colors.textDefault,
            fontWeight: typography.weights.medium,
            whiteSpace: "nowrap",
            overflow: "hidden",
            textOverflow: "ellipsis",
            lineHeight: 1.2,
          }}
        >
          {name}
        </Text>
        <ProbBarThin
          value={probHr ?? 0}
          ariaLabel={`${name} home run probability ${probHr == null ? "unknown" : (probHr * 100).toFixed(1) + " percent"}`}
        />
      </div>
    </Paper>
  );
}

export const ParkTile = memo(ParkTileInner);
