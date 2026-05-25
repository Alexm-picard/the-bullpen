/**
 * Ops dashboard — Drift section (leaf 4e.2).
 *
 * Lists the most recent drift metrics for the selected model and renders a tiny
 * inline sparkline per (metric_type, feature_or_segment) tuple. The sparkline
 * is pure SVG (same approach as ReliabilityDiagram) — no chart library.
 */
import { Group, Select, Stack, Table, Text } from "@mantine/core";
import { useMemo, useState } from "react";

import { useAllModelNames, useDrift, type DriftMetric } from "../../api/ops";
import { colors } from "../../design/tokens";

type SparklineKey = string; // `${metric_type}|${feature_or_segment}`

export function DriftSection() {
  const names = useAllModelNames();
  const [selected, setSelected] = useState<string | null>(null);
  const modelName = selected ?? names.data?.[0] ?? null;
  const drift = useDrift(modelName);

  const grouped = useMemo(() => groupByMetric(drift.data ?? []), [drift.data]);

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
          w={320}
          searchable
          disabled={(names.data?.length ?? 0) === 0}
        />
      </Group>
      {modelName == null ? (
        <Text c="dimmed" size="sm">
          No models — drift metrics light up when the worker computes them.
        </Text>
      ) : drift.isLoading ? (
        <Text c="dimmed" size="sm">
          Loading drift…
        </Text>
      ) : drift.isError ? (
        <Text c="red" size="sm">
          Could not load drift
          {drift.error instanceof Error ? `: ${drift.error.message}` : ""}.
        </Text>
      ) : grouped.size === 0 ? (
        <Text c="dimmed" size="sm">
          No drift metrics recorded for {modelName} yet. The drift batch job
          populates these on a schedule (Phase 3c.1+).
        </Text>
      ) : (
        <Table>
          <Table.Thead>
            <Table.Tr>
              <Table.Th>Metric</Table.Th>
              <Table.Th>Feature / segment</Table.Th>
              <Table.Th>Latest value</Table.Th>
              <Table.Th>Trend</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {[...grouped.entries()].map(([key, rows]) => {
              const [metricType, segment] = key.split("|");
              const latest = rows[0];
              return (
                <Table.Tr key={key}>
                  <Table.Td>{metricType}</Table.Td>
                  <Table.Td ff="monospace">{segment}</Table.Td>
                  <Table.Td ff="monospace">
                    {latest.metricValue.toFixed(4)}
                  </Table.Td>
                  <Table.Td>
                    <Sparkline rows={rows} />
                  </Table.Td>
                </Table.Tr>
              );
            })}
          </Table.Tbody>
        </Table>
      )}
    </Stack>
  );
}

function groupByMetric(rows: DriftMetric[]): Map<SparklineKey, DriftMetric[]> {
  const map = new Map<SparklineKey, DriftMetric[]>();
  for (const r of rows) {
    const k = `${r.metricType}|${r.featureOrSegment}`;
    const list = map.get(k) ?? [];
    list.push(r);
    map.set(k, list);
  }
  return map;
}

function Sparkline({ rows }: { rows: DriftMetric[] }) {
  const width = 120;
  const height = 24;
  if (rows.length === 0) return null;
  const oldestFirst = [...rows].sort(
    (a, b) => Date.parse(a.computedAt) - Date.parse(b.computedAt),
  );
  const values = oldestFirst.map((r) => r.metricValue);
  const min = Math.min(...values);
  const max = Math.max(...values);
  const span = max - min || 1;
  const points = values
    .map((v, i) => {
      const x = (i / Math.max(values.length - 1, 1)) * width;
      const y = height - ((v - min) / span) * height;
      return `${x.toFixed(1)},${y.toFixed(1)}`;
    })
    .join(" ");
  return (
    <svg width={width} height={height} role="img" aria-label="Drift trend">
      <polyline
        fill="none"
        stroke={colors.accent}
        strokeWidth={1.5}
        points={points}
      />
    </svg>
  );
}
