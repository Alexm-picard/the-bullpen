/**
 * Recent-predictions table for the player profile (leaf 4b.2).
 *
 * Renders one row per prediction in JetBrains Mono (data) so the numeric
 * columns align. Outcome + agreement columns show an em-dash until truth-joining
 * to `pitches` lands in a later leaf — kept in the schema so the UI doesn't have
 * to reshape later.
 *
 * Empty / loading / error states are handled inline; skeleton uses 5 fake rows.
 */
import { Badge, Skeleton, Stack, Table, Text } from "@mantine/core";

import type { PlayerPredictionRow } from "../../api/players";

import { formatTimestamp } from "./format-timestamp";

const SKELETON_ROWS = 5;

export type PredictionHistoryTableProps = {
  rows: PlayerPredictionRow[] | undefined;
  isLoading: boolean;
  isError: boolean;
  errorMessage?: string;
};

export function PredictionHistoryTable({
  rows,
  isLoading,
  isError,
  errorMessage,
}: PredictionHistoryTableProps) {
  if (isError) {
    return (
      <Text c="red" size="sm">
        Could not load predictions{errorMessage ? `: ${errorMessage}` : ""}.
      </Text>
    );
  }

  if (isLoading) {
    return (
      <Stack gap={4}>
        {Array.from({ length: SKELETON_ROWS }).map((_, i) => (
          <Skeleton key={i} height={28} radius="sm" />
        ))}
      </Stack>
    );
  }

  if (!rows || rows.length === 0) {
    return (
      <Text c="dimmed" size="sm">
        No recent predictions for this player.
      </Text>
    );
  }

  return (
    <Table
      striped
      highlightOnHover
      withTableBorder
      withColumnBorders
      verticalSpacing="xs"
      horizontalSpacing="sm"
    >
      <Table.Thead>
        <Table.Tr>
          <Table.Th>When</Table.Th>
          <Table.Th>Model</Table.Th>
          <Table.Th>Role</Table.Th>
          <Table.Th>Winner</Table.Th>
          <Table.Th>p(winner)</Table.Th>
          <Table.Th>Outcome</Table.Th>
          <Table.Th>Agreed</Table.Th>
        </Table.Tr>
      </Table.Thead>
      <Table.Tbody>
        {rows.map((row, i) => (
          <Table.Tr key={`${row.requestAt}-${i}`}>
            <Table.Td>
              <Text size="sm" ff="monospace">
                {formatTimestamp(row.requestAt)}
              </Text>
            </Table.Td>
            <Table.Td>
              <Text size="sm" ff="monospace">
                {row.modelName}@{row.modelVersion}
              </Text>
            </Table.Td>
            <Table.Td>
              <Badge size="sm" variant="light">
                {row.role}
              </Badge>
            </Table.Td>
            <Table.Td>{row.winnerClass ?? "—"}</Table.Td>
            <Table.Td>
              <Text size="sm" ff="monospace">
                {row.winnerProb == null ? "—" : row.winnerProb.toFixed(3)}
              </Text>
            </Table.Td>
            <Table.Td>{row.observedOutcome ?? "—"}</Table.Td>
            <Table.Td>
              {row.agreed == null ? "—" : row.agreed ? "✓" : "✗"}
            </Table.Td>
          </Table.Tr>
        ))}
      </Table.Tbody>
    </Table>
  );
}
