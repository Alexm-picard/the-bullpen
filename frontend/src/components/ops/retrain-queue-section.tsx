/**
 * Ops dashboard — Retrain queue section (leaf 4e.4).
 *
 * Read-only table of queued + recently-finished triggers. Polls every 30 s so
 * the operator can watch a manual retrain progress without a refresh.
 */
import { Badge, Stack, Table, Text } from "@mantine/core";

import { useRetrainQueue, type RetrainingTrigger } from "../../api/ops";
import { colors } from "../../design/tokens";

export function RetrainQueueSection() {
  const { data, isLoading, isError, error } = useRetrainQueue();

  if (isLoading) {
    return (
      <Text c="dimmed" size="sm">
        Loading queue…
      </Text>
    );
  }
  if (isError) {
    return (
      <Text c="red" size="sm">
        Could not load queue
        {error instanceof Error ? `: ${error.message}` : ""}.
      </Text>
    );
  }
  if (!data || data.length === 0) {
    return (
      <Text c="dimmed" size="sm">
        Queue empty. Triggers arrive from the drift / manual / scheduled paths
        (Phase 3d.2).
      </Text>
    );
  }

  return (
    <Stack gap="md">
      <Table>
        <Table.Thead>
          <Table.Tr>
            <Table.Th>Trigger</Table.Th>
            <Table.Th>Model</Table.Th>
            <Table.Th>Type</Table.Th>
            <Table.Th>Status</Table.Th>
            <Table.Th>Enqueued</Table.Th>
            <Table.Th>Finished</Table.Th>
            <Table.Th>Result</Table.Th>
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {data.map((t) => (
            <QueueRow key={t.id} t={t} />
          ))}
        </Table.Tbody>
      </Table>
    </Stack>
  );
}

function QueueRow({ t }: { t: RetrainingTrigger }) {
  return (
    <Table.Tr>
      <Table.Td ff="monospace">{t.triggerId.slice(0, 8)}…</Table.Td>
      <Table.Td>{t.modelName}</Table.Td>
      <Table.Td>
        <Badge size="sm" variant="light">
          {t.triggerType}
        </Badge>
      </Table.Td>
      <Table.Td>
        <StatusBadge status={t.status} />
      </Table.Td>
      <Table.Td ff="monospace">{formatShort(t.enqueuedAt)}</Table.Td>
      <Table.Td ff="monospace">
        {t.finishedAt ? formatShort(t.finishedAt) : "—"}
      </Table.Td>
      <Table.Td>
        {t.errorMessage ? (
          <Text size="xs" style={{ color: colors.scarlet }}>
            {t.errorMessage.slice(0, 60)}
            {t.errorMessage.length > 60 ? "…" : ""}
          </Text>
        ) : t.producedVersionId ? (
          <Text size="xs" ff="monospace">
            → v{t.producedVersionId}
          </Text>
        ) : (
          <Text size="xs" c="dimmed">
            —
          </Text>
        )}
      </Table.Td>
    </Table.Tr>
  );
}

function StatusBadge({ status }: { status: string }) {
  const color =
    status === "FAILED"
      ? "red"
      : status === "SUCCEEDED"
        ? "green"
        : status === "RUNNING"
          ? "blue"
          : "gray";
  return (
    <Badge size="sm" color={color} variant="light">
      {status}
    </Badge>
  );
}

function formatShort(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return d.toISOString().slice(0, 16).replace("T", " ");
}
