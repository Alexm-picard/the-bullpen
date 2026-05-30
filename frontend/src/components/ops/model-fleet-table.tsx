/**
 * <ModelFleetTable> — the hero table of the /ops page.
 *
 * Wraps <StatTable> with the registry-fleet column config. Eight columns
 * (state, traffic, predictions·24h, psi·max, ece·Δ, p99·ms, registered) plus
 * the row-label slot (model name + version). cellColor tints land only on
 * psi·max / ece·Δ / p99·ms — the three columns where the operator's eye
 * wants to resolve "is this within tolerance?" in one glance.
 *
 * Locked picks behind this config:
 *   - p99 inline (drop p50) — L3
 *   - state badge color carries LIVE / SHADOW / AWAITING-PROMOTION; the cell
 *     itself does NOT cellColor (categorical, not percentile)
 */

import type { ModelRegistryRow } from "../../data/ops-fixtures";
import {
  ECE_DELTA_METRIC,
  LATENCY_METRIC,
  PSI_METRIC,
} from "../../data/ops-fixtures";
import { colors, typography } from "../../design/tokens";
import { StatTable } from "../shared/stat-table";
import type { StatTableColumn, StatTableRow } from "../shared/stat-table";

export type ModelFleetTableProps = {
  rows: ModelRegistryRow[];
  caption?: string;
};

function fleetColumns(): StatTableColumn[] {
  return [
    {
      key: "state",
      label: "State",
      // No metricMeta — categorical badge. format() emits the bare token; the
      // StatTable cell renders mono so the state reads as a tag.
      format: (v) => String(v),
    },
    {
      key: "traffic",
      label: "Traffic",
      format: (v) => String(v),
    },
    {
      key: "predictions24h",
      label: "Preds·24h",
      format: (v) => Number(v).toLocaleString("en-US"),
    },
    {
      key: "psiMax",
      label: "PSI·Max",
      metricMeta: PSI_METRIC,
      format: (v) => Number(v).toFixed(2),
    },
    {
      key: "eceDelta",
      label: "ECE·Δ",
      metricMeta: ECE_DELTA_METRIC,
      format: (v) => {
        const n = Number(v);
        const sign = n > 0 ? "+" : "";
        return `${sign}${n.toFixed(3)}`;
      },
    },
    {
      key: "p99Ms",
      label: "p99·ms",
      metricMeta: LATENCY_METRIC,
      format: (v) => `${Number(v)} ms`,
    },
    {
      key: "lastRegistered",
      label: "Registered",
      format: (v) => String(v),
    },
  ];
}

function rowsFor(models: ModelRegistryRow[]): StatTableRow[] {
  return models.map((m) => ({
    label: `${m.modelName} ${m.version}`,
    values: {
      state: m.state,
      traffic: m.traffic,
      predictions24h: m.predictions24h,
      psiMax: m.psiMax,
      eceDelta: m.eceDelta,
      p99Ms: m.p99Ms,
      lastRegistered: m.lastRegistered,
    },
  }));
}

export function ModelFleetTable({ rows, caption }: ModelFleetTableProps) {
  return (
    <div
      style={{
        // Light wrapper so the state-badge legend below ties visually to the
        // table without adding a second card chrome.
        display: "flex",
        flexDirection: "column",
        gap: 8,
      }}
    >
      <StatTable
        columns={fleetColumns()}
        rows={rowsFor(rows)}
        caption={caption}
      />
      <div
        style={{
          display: "flex",
          flexWrap: "wrap",
          gap: 12,
          fontFamily: typography.fonts.mono,
          fontSize: 11,
          color: colors.textMuted,
          letterSpacing: "0.04em",
          textTransform: "uppercase",
        }}
        aria-label="Drift and latency tint legend"
      >
        <span>
          <span style={{ color: colors.condFormat.good3 }}>■</span> Within
          tolerance
        </span>
        <span>
          <span style={{ color: colors.condFormat.neutral }}>■</span> Watch
        </span>
        <span>
          <span style={{ color: colors.condFormat.bad3 }}>■</span> Action
        </span>
      </div>
    </div>
  );
}
