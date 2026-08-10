/**
 * <LatencyDetailTable> — the full per-percentile latency StatTable that sits
 * below the model fleet table.
 *
 * Companion to the L3 locked pick: the fleet table carries only p99 inline,
 * keeping it scannable; this table gives the operator the full distribution
 * (p50 / p95 / p99 / p99.9) when they want to look at it. Every cell uses
 * the LATENCY_METRIC cellColor tint.
 *
 * Wraps <StatTable>. Row label = "modelName vVersion", columns = the 4
 * percentile values, formatted as "12 ms".
 */

import type { LatencyRow } from "../../data/ops-fixtures";
import { LATENCY_METRIC } from "../../data/ops-fixtures";
import { StatTable } from "../shared/stat-table";
import type { StatTableColumn, StatTableRow } from "../shared/stat-table";

export type LatencyDetailTableProps = {
  rows: LatencyRow[];
  caption?: string;
};

const formatMs = (v: unknown): string => `${Number(v)} ms`;

function latencyColumns(): StatTableColumn[] {
  return [
    { key: "p50", label: "p50", metricMeta: LATENCY_METRIC, format: formatMs },
    { key: "p95", label: "p95", metricMeta: LATENCY_METRIC, format: formatMs },
    { key: "p99", label: "p99", metricMeta: LATENCY_METRIC, format: formatMs },
    {
      key: "p999",
      label: "p99.9",
      metricMeta: LATENCY_METRIC,
      format: formatMs,
    },
  ];
}

function rowsFor(rows: LatencyRow[]): StatTableRow[] {
  return rows.map((r) => ({
    label: r.label,
    values: { p50: r.p50, p95: r.p95, p99: r.p99, p999: r.p999 },
  }));
}

export function LatencyDetailTable({ rows, caption }: LatencyDetailTableProps) {
  return (
    <StatTable
      columns={latencyColumns()}
      rows={rowsFor(rows)}
      caption={caption}
    />
  );
}
