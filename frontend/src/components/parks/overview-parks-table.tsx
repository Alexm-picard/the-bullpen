/**
 * <OverviewParksTable> — the 30-row scouting-report park-factors hero table.
 *
 * Wraps <StatTable> with park-specific column metadata and row id assignment.
 * Each <tr> gets `id="park-row-{abbr}"` so the <ParkSwitcherStrip> can scroll
 * the matching row into view via `document.getElementById(...).scrollIntoView`.
 *
 * 9 columns total: 1 row-label ("PARK") + 8 data columns (TEAM / CLIMATE / HR
 * / BABIP / 3B / WIND BIAS / K / OPS). Numeric factor columns carry the
 * FACTOR_METRIC meta so cellColor tints them via the closer-to-target ramp.
 * TEAM / CLIMATE / WIND BIAS are categorical text — no tint.
 *
 * Format helpers stay local (not exported) — they're only used here.
 */

import { StatTable } from "../shared/stat-table";
import type { StatTableColumn, StatTableRow } from "../shared/stat-table";
import type { ParkRow } from "../../data/parks-fixtures";
import { FACTOR_METRIC } from "../../data/parks-fixtures";

export type OverviewParksTableProps = {
  rows: ParkRow[];
};

function formatFactor(v: unknown): string {
  if (typeof v !== "number" || !Number.isFinite(v)) return String(v ?? "—");
  return v.toFixed(2);
}

function identity(v: unknown): string {
  return String(v ?? "—");
}

const COLUMNS: StatTableColumn[] = [
  { key: "team", label: "Team", format: identity },
  { key: "climate", label: "Climate", format: identity },
  {
    key: "hr",
    label: "HR",
    metricMeta: { ...FACTOR_METRIC, key: "hr" },
    format: formatFactor,
  },
  {
    key: "babip",
    label: "BABIP",
    metricMeta: { ...FACTOR_METRIC, key: "babip" },
    format: formatFactor,
  },
  {
    key: "triples",
    label: "3B",
    metricMeta: { ...FACTOR_METRIC, key: "triples" },
    format: formatFactor,
  },
  { key: "wind", label: "Wind Bias", format: identity },
  {
    key: "k",
    label: "K",
    metricMeta: { ...FACTOR_METRIC, key: "k" },
    format: formatFactor,
  },
  {
    key: "ops",
    label: "OPS",
    metricMeta: { ...FACTOR_METRIC, key: "ops" },
    format: formatFactor,
  },
];

export function OverviewParksTable({ rows }: OverviewParksTableProps) {
  const tableRows: StatTableRow[] = rows.map((r) => ({
    id: `park-row-${r.id}`,
    label: r.parkName,
    values: {
      team: r.team,
      climate: r.climate,
      hr: r.hr,
      babip: r.babip,
      triples: r.triples,
      wind: r.wind,
      k: r.k,
      ops: r.ops,
    },
  }));

  return <StatTable columns={COLUMNS} rows={tableRows} />;
}
