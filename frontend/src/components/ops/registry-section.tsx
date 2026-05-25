/**
 * Ops dashboard — Registry section (leaf 4e.1).
 *
 * Two columns: model-name dropdown on the left, registry rows for the chosen
 * model on the right with an expandable detail row per version (Mantine
 * `Accordion` so multiple versions can be opened side-by-side for diffing).
 */
import {
  Accordion,
  Code,
  Group,
  Select,
  Stack,
  Table,
  Text,
} from "@mantine/core";
import { useMemo, useState } from "react";

import {
  useAllModelNames,
  useRegistryRows,
  type ModelVersion,
} from "../../api/ops";

export function RegistrySection() {
  const names = useAllModelNames();
  const [selected, setSelected] = useState<string | null>(null);

  const firstAvailable = names.data?.[0] ?? null;
  const modelName = selected ?? firstAvailable;
  const rows = useRegistryRows(modelName);

  return (
    <Stack gap="md">
      <Group align="flex-end">
        <Select
          label="Model"
          data={names.data ?? []}
          value={modelName}
          onChange={setSelected}
          placeholder={
            names.isLoading ? "Loading…" : "No models registered yet"
          }
          searchable
          maxDropdownHeight={300}
          w={320}
          disabled={(names.data?.length ?? 0) === 0}
        />
        {names.isError ? (
          <Text c="red" size="sm">
            Could not load model list.
          </Text>
        ) : null}
      </Group>

      {modelName == null ? (
        <Text c="dimmed" size="sm">
          No models registered yet. The dashboard becomes useful as soon as the
          first model is registered (Phase 2a.8 onward).
        </Text>
      ) : rows.isLoading ? (
        <Text c="dimmed" size="sm">
          Loading versions…
        </Text>
      ) : rows.isError ? (
        <Text c="red" size="sm">
          Could not load registry rows
          {rows.error instanceof Error ? `: ${rows.error.message}` : ""}.
        </Text>
      ) : (rows.data?.length ?? 0) === 0 ? (
        <Text c="dimmed" size="sm">
          No versions for this model yet.
        </Text>
      ) : (
        <RegistryTable rows={rows.data ?? []} />
      )}
    </Stack>
  );
}

function RegistryTable({ rows }: { rows: ModelVersion[] }) {
  return (
    <Accordion variant="contained" multiple>
      {rows.map((mv) => (
        <Accordion.Item key={mv.id} value={String(mv.id)}>
          <Accordion.Control>
            <Group justify="space-between" wrap="nowrap">
              <Text ff="monospace">{mv.version}</Text>
              <Text size="xs" c="dimmed">
                {mv.stage}
              </Text>
              <Text size="xs" c="dimmed">
                trained {formatDate(mv.trainedAt)}
              </Text>
              <Text size="xs" c="dimmed">
                {mv.promotedAt
                  ? `promoted ${formatDate(mv.promotedAt)}`
                  : "not promoted"}
              </Text>
            </Group>
          </Accordion.Control>
          <Accordion.Panel>
            <RegistryDetail mv={mv} />
          </Accordion.Panel>
        </Accordion.Item>
      ))}
    </Accordion>
  );
}

function RegistryDetail({ mv }: { mv: ModelVersion }) {
  const evalMetrics = useMemo(() => safeJson(mv.evalMetrics), [mv.evalMetrics]);
  return (
    <Stack gap="xs">
      <Table>
        <Table.Tbody>
          {[
            ["model_name", mv.modelName],
            ["version", mv.version],
            ["stage", mv.stage],
            ["training_data_window", mv.trainingDataWindow],
            ["training_data_hash", short(mv.trainingDataHash)],
            ["feature_schema_hash", short(mv.featureSchemaHash)],
            ["created_by", mv.createdBy ?? "—"],
            ["notes", mv.notes ?? "—"],
            ["created_at", formatDate(mv.createdAt)],
            ["updated_at", formatDate(mv.updatedAt)],
          ].map(([k, v]) => (
            <Table.Tr key={k}>
              <Table.Td>
                <Text size="sm" c="dimmed">
                  {k}
                </Text>
              </Table.Td>
              <Table.Td>
                <Text size="sm" ff="monospace">
                  {v}
                </Text>
              </Table.Td>
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>
      <Text size="xs" c="dimmed" tt="uppercase">
        eval_metrics
      </Text>
      {evalMetrics == null ? (
        <Text size="sm" c="dimmed">
          (none)
        </Text>
      ) : (
        <Code block>{JSON.stringify(evalMetrics, null, 2)}</Code>
      )}
    </Stack>
  );
}

function short(hash: string): string {
  if (!hash) return "—";
  if (hash.length <= 12) return hash;
  return `${hash.slice(0, 8)}…${hash.slice(-4)}`;
}

function formatDate(iso: string | null): string {
  if (!iso) return "—";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return d.toISOString().replace("T", " ").slice(0, 16) + " UTC";
}

function safeJson(text: string): unknown {
  if (!text) return null;
  try {
    return JSON.parse(text);
  } catch {
    return text; // surface raw string so the user sees what's there
  }
}
