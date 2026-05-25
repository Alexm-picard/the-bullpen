/**
 * Pitch feed (leaf 4d.2) — reverse-chronological list of pitch cards.
 *
 * The "newest at top with a 200ms fade entrance" is handled by a CSS animation
 * keyed on `cursor` so each pitch only animates the first time it enters. We
 * don't pull in Framer Motion — a single keyframes-in-CSS handles the
 * subtle-fade requirement without a dep.
 */
import { Stack, Text } from "@mantine/core";
import { useMemo } from "react";

import type { LivePitchRow } from "../../api/games";

import { PitchCard } from "./pitch-card";
import classes from "./pitch-feed.module.css";

export type PitchFeedProps = {
  pitches: LivePitchRow[];
  /** Max rows to render in the feed. */
  limit?: number;
};

export function PitchFeed({ pitches, limit = 50 }: PitchFeedProps) {
  const newestFirst = useMemo(
    () => [...pitches].sort((a, b) => b.cursor - a.cursor).slice(0, limit),
    [pitches, limit],
  );

  if (newestFirst.length === 0) {
    return (
      <Text c="dimmed" size="sm">
        No pitches yet.
      </Text>
    );
  }

  return (
    <Stack gap="xs">
      {newestFirst.map((pitch) => (
        <div
          key={`${pitch.gameId}-${pitch.cursor}`}
          className={classes.pitchEntry}
        >
          <PitchCard pitch={pitch} />
        </div>
      ))}
    </Stack>
  );
}
