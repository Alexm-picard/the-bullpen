/**
 * Ops dashboard — A/B routing section (leaf 4e.3).
 *
 * Read-only table of every model's current routing row: mode, champion vs
 * challenger version ids, traffic split. A small bar visualises the split.
 */
import { Badge, Box, Group, Stack, Table, Text } from "@mantine/core";

import { useRouting, type RoutingConfig } from "../../api/ops";
import { colors } from "../../design/tokens";

export function RoutingSection() {
  const { data, isLoading, isError, error } = useRouting();

  if (isLoading) {
    return (
      <Text c="dimmed" size="sm">
        Loading routing…
      </Text>
    );
  }
  if (isError) {
    return (
      <Text c="red" size="sm">
        Could not load routing
        {error instanceof Error ? `: ${error.message}` : ""}.
      </Text>
    );
  }
  if (!data || data.length === 0) {
    return (
      <Text c="dimmed" size="sm">
        No routing configured yet. Rows appear as soon as the first model
        promotes to CHAMPION (Phase 3b.3).
      </Text>
    );
  }

  return (
    <Stack gap="md">
      <Table>
        <Table.Thead>
          <Table.Tr>
            <Table.Th>Model</Table.Th>
            <Table.Th>Mode</Table.Th>
            <Table.Th>Champion</Table.Th>
            <Table.Th>Challenger</Table.Th>
            <Table.Th>Split</Table.Th>
            <Table.Th>Updated</Table.Th>
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {data.map((row) => (
            <RoutingRow key={row.id} row={row} />
          ))}
        </Table.Tbody>
      </Table>
    </Stack>
  );
}

function RoutingRow({ row }: { row: RoutingConfig }) {
  return (
    <Table.Tr>
      <Table.Td>{row.modelName}</Table.Td>
      <Table.Td>
        <Badge size="sm" variant="light">
          {row.mode}
        </Badge>
      </Table.Td>
      <Table.Td ff="monospace">v{row.championVersionId}</Table.Td>
      <Table.Td ff="monospace">
        {row.challengerVersionId == null ? "—" : `v${row.challengerVersionId}`}
      </Table.Td>
      <Table.Td>
        <Group gap="xs" align="center" wrap="nowrap">
          <Text size="sm" ff="monospace">
            {(100 - row.challengerTrafficPct).toFixed(0)}%
          </Text>
          <SplitBar challengerPct={row.challengerTrafficPct} />
          <Text size="sm" ff="monospace">
            {row.challengerTrafficPct.toFixed(0)}%
          </Text>
        </Group>
      </Table.Td>
      <Table.Td>
        <Text size="xs" c="dimmed" ff="monospace">
          {new Date(row.updatedAt).toISOString().slice(0, 16).replace("T", " ")}
        </Text>
      </Table.Td>
    </Table.Tr>
  );
}

function SplitBar({ challengerPct }: { challengerPct: number }) {
  const championPct = 100 - challengerPct;
  return (
    <Box
      style={{
        width: 100,
        height: 8,
        display: "flex",
        borderRadius: 4,
        overflow: "hidden",
        border: `1px solid ${colors.bgEmphasis}`,
      }}
    >
      <Box
        style={{
          width: `${championPct}%`,
          backgroundColor: colors.viz.categorical[0],
        }}
      />
      <Box
        style={{
          width: `${challengerPct}%`,
          backgroundColor: colors.viz.categorical[1],
        }}
      />
    </Box>
  );
}
