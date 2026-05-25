/**
 * <LeagueLeaderStrip> — the rank-1 highlight block above the parks grid.
 *
 * One-line answer to "where would this batted ball most likely be a home run?"
 * Shows the leading park's name, abbreviation, fence depth signature, and
 * P(HR) value at large size. Updates live as the rail values change (parent
 * passes the recomputed leader).
 *
 * Composition:
 *   - Eyebrow ("Most-likely HR")
 *   - Park name (big, display) + park id chip
 *   - Short fence signature line (LF/CF/RF in mono)
 *   - P(HR) at 40px in JetBrains Mono with the thin accent bar below
 *
 * If `leader` is null (loading or empty input), renders a muted skeleton row.
 *
 * Memoized — re-renders only when the leader's identity or probability changes.
 */
import { Box, Stack, Text, Title } from "@mantine/core";
import { memo } from "react";

import { colors, radii, spacing, typography } from "../../design/tokens";
import { ProbBarThin } from "../shared/prob-bar-thin";

export type LeagueLeader = {
  parkId: string;
  name: string;
  probHr: number;
  shortFenceFt: number;
  centerFenceFt: number | null;
  deepestFenceFt: number;
};

export type LeagueLeaderStripProps = {
  leader: LeagueLeader | null;
  isLoading?: boolean;
};

function LeagueLeaderStripInner({
  leader,
  isLoading = false,
}: LeagueLeaderStripProps) {
  return (
    <Box
      role="region"
      aria-label="Most-likely home run park for the current launch parameters"
      style={{
        backgroundColor: colors.bgElevated,
        border: `1px solid ${colors.bgEmphasis}`,
        borderRadius: radii.md,
        padding: spacing[5],
        marginTop: spacing[5],
      }}
    >
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
          Most-likely HR · rank 1 of 30
        </Text>

        <div
          style={{
            display: "flex",
            flexWrap: "wrap",
            justifyContent: "space-between",
            alignItems: "flex-end",
            gap: spacing[4],
          }}
        >
          <Stack gap={spacing[1]} style={{ minWidth: 0, flex: "1 1 auto" }}>
            <div
              style={{
                display: "flex",
                alignItems: "baseline",
                gap: spacing[3],
              }}
            >
              <Title
                order={2}
                style={{
                  margin: 0,
                  fontSize: typography.scale[5], // 32
                  fontWeight: typography.weights.bold,
                  color: leader ? colors.textStrong : colors.textMuted,
                  lineHeight: 1.1,
                  letterSpacing: "-0.02em",
                }}
              >
                {leader ? leader.name : "—"}
              </Title>
              {leader ? (
                <Text
                  component="span"
                  style={{
                    fontFamily: typography.fonts.data,
                    fontSize: typography.scale[1], // 14
                    color: colors.textMuted,
                    letterSpacing: "0.06em",
                    textTransform: "uppercase",
                  }}
                >
                  {leader.parkId}
                </Text>
              ) : null}
            </div>
            <Text
              style={{
                fontFamily: typography.fonts.data,
                fontSize: typography.scale[1], // 14
                color: colors.textMuted,
                fontVariantNumeric: "tabular-nums",
              }}
            >
              {leader
                ? `LF ${leader.shortFenceFt} · CF ${leader.centerFenceFt ?? "—"} · RF ${leader.deepestFenceFt} ft`
                : isLoading
                  ? "computing leader…"
                  : "no realistic HR scenario at these inputs"}
            </Text>
          </Stack>

          <Stack
            gap={spacing[2]}
            style={{ minWidth: 200, flex: "0 0 auto" }}
            align="flex-end"
          >
            <Text
              style={{
                fontFamily: typography.fonts.data,
                fontSize: typography.scale[6], // 40
                fontWeight: typography.weights.medium,
                color: leader ? colors.textStrong : colors.textMuted,
                lineHeight: 1,
                letterSpacing: "-0.02em",
                fontVariantNumeric: "tabular-nums",
              }}
            >
              {leader ? `${(leader.probHr * 100).toFixed(1)}%` : "—"}
            </Text>
            <Box style={{ width: 200 }}>
              <ProbBarThin
                value={leader?.probHr ?? 0}
                ariaLabel={
                  leader
                    ? `Leading park home run probability ${(leader.probHr * 100).toFixed(1)} percent`
                    : "No leader yet"
                }
              />
            </Box>
            <Text
              component="span"
              style={{
                fontFamily: typography.fonts.ui,
                fontSize: typography.scale[0], // 12
                color: colors.textMuted,
                letterSpacing: "0.04em",
                textTransform: "uppercase",
                fontWeight: typography.weights.medium,
              }}
            >
              P(home run)
            </Text>
          </Stack>
        </div>
      </Stack>
    </Box>
  );
}

export const LeagueLeaderStrip = memo(LeagueLeaderStripInner);
