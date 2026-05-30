/**
 * <ParkListRow> — the 48px-tall row used in /parks list mode.
 *
 * Reads the same data as <ParkTile> but lays it out horizontally: rank /
 * name+id / fence+altitude meta / P(HR) value, with a 2px ProbBarThin drawn
 * directly under columns 2–4 (rank stays in its own gutter). Keyboard handlers
 * match the tile so list and grid feel identical to keyboard-only users.
 *
 * Memoized — list mode renders all 30 rows on every slider tick, same churn
 * pressure as the grid.
 */
import { Box, Text } from "@mantine/core";
import { memo, useState } from "react";

import { colors, spacing, typography } from "../../design/tokens";
import { ProbBarThin } from "../shared/prob-bar-thin";

export type ParkListRowProps = {
  parkId: string;
  name: string;
  rank: number;
  probHr: number | null;
  shortFenceFt: number;
  altitudeM: number | null;
  onSelect: (parkId: string) => void;
};

function ParkListRowInner({
  parkId,
  name,
  rank,
  probHr,
  shortFenceFt,
  altitudeM,
  onSelect,
}: ParkListRowProps) {
  const [hover, setHover] = useState(false);
  const [focused, setFocused] = useState(false);
  const active = hover || focused;
  const isLeader = rank === 1;

  const rankColor = isLeader ? colors.scarlet : colors.textMuted;
  const altDisplay = altitudeM == null ? "—" : `${Math.round(altitudeM)} m`;
  const probDisplay = probHr == null ? "—" : `${(probHr * 100).toFixed(1)}%`;
  const probAria =
    probHr == null
      ? `${name} home run probability unknown`
      : `${name} home run probability ${(probHr * 100).toFixed(1)} percent`;

  return (
    <Box
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
      style={{
        display: "block",
        cursor: "pointer",
        backgroundColor: active ? colors.bgSubtle : "transparent",
        borderBottom: `1px solid ${colors.bgEmphasis}`,
        transition: "background-color 150ms cubic-bezier(0.4, 0, 0.2, 1)",
      }}
    >
      <div
        style={{
          display: "grid",
          gridTemplateColumns: "40px 1fr 200px 80px",
          alignItems: "center",
          height: 48,
          paddingLeft: spacing[3],
          paddingRight: spacing[3],
          columnGap: spacing[3],
        }}
      >
        {/* Rank — JBM mono, accent on rank 1 */}
        <Text
          component="span"
          style={{
            fontFamily: typography.fonts.mono,
            fontSize: typography.scale[1], // 14
            fontWeight: typography.weights.semibold,
            color: rankColor,
            letterSpacing: "0.02em",
            fontVariantNumeric: "tabular-nums",
            textAlign: "left",
          }}
        >
          {rank}
        </Text>

        {/* Name cell: id JBM + name Inter medium */}
        <div
          style={{
            display: "flex",
            alignItems: "baseline",
            gap: spacing[3],
            minWidth: 0,
          }}
        >
          <Text
            component="span"
            style={{
              fontFamily: typography.fonts.mono,
              fontSize: typography.scale[0], // 12
              color: colors.textMuted,
              letterSpacing: "0.06em",
              flex: "0 0 auto",
            }}
          >
            {parkId}
          </Text>
          <Text
            component="span"
            style={{
              fontFamily: typography.fonts.body,
              fontSize: typography.scale[2], // 16
              fontWeight: typography.weights.medium,
              color: colors.textDefault,
              whiteSpace: "nowrap",
              overflow: "hidden",
              textOverflow: "ellipsis",
              minWidth: 0,
            }}
          >
            {name}
          </Text>
        </div>

        {/* Meta: fence + altitude, JBM right-aligned */}
        <Text
          component="span"
          style={{
            fontFamily: typography.fonts.mono,
            fontSize: typography.scale[1], // 14
            color: colors.textMuted,
            textAlign: "right",
            fontVariantNumeric: "tabular-nums",
          }}
        >
          {`${shortFenceFt} ft · ${altDisplay}`}
        </Text>

        {/* P(HR) — JBM semibold scale[2] */}
        <Text
          component="span"
          style={{
            fontFamily: typography.fonts.mono,
            fontSize: typography.scale[2], // 16
            fontWeight: typography.weights.semibold,
            color: probHr == null ? colors.textMuted : colors.textStrong,
            textAlign: "right",
            fontVariantNumeric: "tabular-nums",
          }}
        >
          {probDisplay}
        </Text>
      </div>

      {/* Thin bar spanning columns 2–4 (under name through P(HR)) */}
      <div
        style={{
          display: "grid",
          gridTemplateColumns: "40px 1fr",
          paddingLeft: spacing[3],
          paddingRight: spacing[3],
          columnGap: spacing[3],
          paddingBottom: spacing[1],
        }}
      >
        <div />
        <ProbBarThin value={probHr ?? 0} ariaLabel={probAria} height={2} />
      </div>
    </Box>
  );
}

export const ParkListRow = memo(ParkListRowInner);
