/**
 * Single pitch card (leaf 4d.2).
 *
 * Three columns:
 *   - Count + inning in JetBrains Mono tabular figures
 *   - Description + pitch type + velocity
 *   - Probability bar + winner vs observed marker
 *
 * Agreement / disagreement uses **both color AND glyph** (✓ in muted text,
 * ✗ in brick-red accent) per the leaf's known-edge-case for color-blind users.
 * Disagreement also adds a 2px brick-red rule on the right edge of the card.
 *
 * "n/a" placeholder shown when the pitch has no logged prediction.
 */
import { Group, Paper, Stack, Text } from "@mantine/core";
import { memo } from "react";

import type { LivePitchRow } from "../../api/games";
import { colors } from "../../design/tokens";

import { ProbabilityBar } from "./probability-bar";

export type PitchCardProps = {
  pitch: LivePitchRow;
};

function PitchCardInner({ pitch }: PitchCardProps) {
  const agreed = computeAgreement(pitch.predictedWinner, pitch.description);
  const showRule = agreed === "disagree";

  return (
    <Paper
      withBorder
      shadow="0"
      radius="md"
      p="xs"
      style={{
        position: "relative",
        borderRight: showRule ? `2px solid ${colors.scarlet}` : undefined,
      }}
    >
      <Group justify="space-between" align="flex-start" wrap="nowrap">
        <Stack gap={0} style={{ minWidth: 64 }}>
          <Text size="xs" c="dimmed" tt="uppercase">
            Inning {pitch.inning}
          </Text>
          <Text ff="monospace" style={{ fontVariantNumeric: "tabular-nums" }}>
            {pitch.balls}-{pitch.strikes}
          </Text>
          <Text size="xs" c="dimmed">
            {pitch.outs} {pitch.outs === 1 ? "out" : "outs"}
          </Text>
        </Stack>

        <Stack gap={0} style={{ flex: 1, minWidth: 120 }}>
          <Text size="sm" fw={500}>
            {pitch.description}
          </Text>
          <Text size="xs" c="dimmed" ff="monospace">
            {pitch.pitchType || "—"} ·{" "}
            {pitch.releaseSpeedMph == null
              ? "—"
              : `${pitch.releaseSpeedMph.toFixed(1)} mph`}
          </Text>
          <Text size="xs" c="dimmed">
            pitcher {pitch.pitcherId} → batter {pitch.batterId}
          </Text>
        </Stack>

        <Stack gap={2} align="flex-end">
          <ProbabilityBar predicted={pitch.predictedClasses} width={140} />
          <AgreementMarker agreed={agreed} winner={pitch.predictedWinner} />
        </Stack>
      </Group>
    </Paper>
  );
}

export const PitchCard = memo(PitchCardInner);

type Agreement = "agree" | "disagree" | "unknown";

function computeAgreement(
  predictedWinner: string | null,
  observedDescription: string,
): Agreement {
  if (predictedWinner == null) return "unknown";
  return predictedWinner === observedDescription ? "agree" : "disagree";
}

function AgreementMarker({
  agreed,
  winner,
}: {
  agreed: Agreement;
  winner: string | null;
}) {
  if (agreed === "unknown" || winner == null) {
    return (
      <Text size="xs" c="dimmed">
        n/a
      </Text>
    );
  }
  if (agreed === "agree") {
    return (
      <Text size="xs" c="dimmed" ff="monospace">
        ✓ predicted {winner}
      </Text>
    );
  }
  return (
    <Text size="xs" ff="monospace" style={{ color: colors.scarlet }}>
      ✗ predicted {winner}
    </Text>
  );
}
