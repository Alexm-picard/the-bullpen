/**
 * <DriftSnapshotGrid> — two parallel StatTables (PSI by feature | ECE Δ by
 * output). The section heading is the PAGE's responsibility (ops-page renders
 * the single "Drift Snapshot" LowerThird); the grid is headerless so the
 * heading and its DOM id exist exactly once.
 *
 * Layout (CSS grid via `ops-drift__pair`):
 *   [ PSI BY FEATURE       ] [ ECE Δ BY OUTPUT       ]
 *
 * At <900px the subgrid stacks (PSI on top), matching the matchup-report__pair
 * breakpoint precedent. Stacking is handled by CSS so we never measure the
 * viewport from JS.
 *
 * The ECE table renders an em-dash for `batted_ball` rows other than `in_play`
 * — that's the StatTable default for null values, and it honestly reflects
 * the two-head architecture (batted_ball only emits for in-play outcomes).
 */

import type {
  DriftFeatureRow,
  DriftOutputRow,
  ModelRegistryRow,
} from "../../data/ops-fixtures";
import { ECE_DELTA_METRIC, PSI_METRIC } from "../../data/ops-fixtures";
import { broadcastStatTablePalette } from "../broadcast/palettes";
import { StatTable } from "../shared/stat-table";
import type { StatTableColumn, StatTableRow } from "../shared/stat-table";

export type DriftSnapshotGridProps = {
  models: ModelRegistryRow[];
  psiByFeature: DriftFeatureRow[];
  eceByOutput: DriftOutputRow[];
};

/**
 * Returns one column per LIVE model (we suppress shadow / awaiting columns
 * from the drift snapshot so the table reads as "models currently serving
 * predictions"). cellColor metric depends on the caller (psi or ece).
 */
function modelColumns(
  models: ModelRegistryRow[],
  metricMeta: typeof PSI_METRIC | typeof ECE_DELTA_METRIC,
  formatter: (v: unknown) => string,
): StatTableColumn[] {
  // Deduplicate by modelName — there can be two registry rows for the same
  // model (e.g. v3.2 LIVE + v3.3 AWAITING-PROMOTION); drift is measured per
  // model name, not per version.
  const seen = new Set<string>();
  const cols: StatTableColumn[] = [];
  for (const m of models) {
    if (seen.has(m.modelName)) continue;
    if (m.state !== "LIVE") continue;
    seen.add(m.modelName);
    cols.push({
      key: m.modelName,
      label: m.modelName,
      metricMeta,
      format: formatter,
    });
  }
  return cols;
}

function psiRows(features: DriftFeatureRow[]): StatTableRow[] {
  return features.map((f) => ({
    label: f.feature,
    values: f.byModel as Record<string, number | string | null>,
  }));
}

function eceRows(outputs: DriftOutputRow[]): StatTableRow[] {
  return outputs.map((o) => ({
    label: o.output,
    values: o.byModel as Record<string, number | string | null>,
  }));
}

function formatPsi(v: unknown): string {
  const n = Number(v);
  // A non-numeric string sentinel from live /v1/ops data (StatTable already
  // maps null -> em-dash, but passes strings through) would render "NaN" (DEF-L7).
  if (!Number.isFinite(n)) return "—";
  return n.toFixed(2);
}

function formatEce(v: unknown): string {
  const n = Number(v);
  if (!Number.isFinite(n)) return "—";
  const sign = n > 0 ? "+" : "";
  return `${sign}${n.toFixed(3)}`;
}

export function DriftSnapshotGrid({
  models,
  psiByFeature,
  eceByOutput,
}: DriftSnapshotGridProps) {
  const psiCols = modelColumns(models, PSI_METRIC, formatPsi);
  const eceCols = modelColumns(models, ECE_DELTA_METRIC, formatEce);

  return (
    <div className="ops-drift__pair">
      <div>
        <StatTable
          palette={broadcastStatTablePalette}
          columns={psiCols}
          rows={psiRows(psiByFeature)}
          caption="PSI by feature · 0 stable / 0.20 action"
        />
      </div>
      <div>
        <StatTable
          palette={broadcastStatTablePalette}
          columns={eceCols}
          rows={eceRows(eceByOutput)}
          caption="ECE Δ by output · target 0 / |Δ| > 0.01 action"
        />
      </div>
    </div>
  );
}
