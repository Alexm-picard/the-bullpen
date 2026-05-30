/**
 * <AgreementByInningTable> — thin wrapper around `<StatTable>` for the
 * per-inning model agreement summary at the bottom of /games.
 *
 * The AGREED% column is the only conditionally-formatted one (via the
 * AGREEMENT_METRIC from games-fixtures.ts); the other four columns are
 * plain counters with no heat-map fill — they're "facts," not "reads."
 *
 * Existence as its own component is justified by the column-set being
 * games-specific and the format functions being non-trivial; inlining it
 * in the page would crowd games-page.tsx with table-config logic.
 */

import {
  AGREEMENT_METRIC,
  type InningAgreementRow,
} from "../../data/games-fixtures";
import { StatTable } from "../shared/stat-table";
import type { StatTableColumn, StatTableRow } from "../shared/stat-table";

export type AgreementByInningTableProps = {
  rows: InningAgreementRow[];
  caption?: string;
};

function columns(): StatTableColumn[] {
  return [
    {
      key: "pitches",
      label: "Pitches",
      format: (v) => String(v),
    },
    {
      key: "agreed",
      label: "Agreed%",
      metricMeta: AGREEMENT_METRIC,
      format: (v) => `${(Number(v) * 100).toFixed(0)}%`,
    },
    {
      key: "inPlay",
      label: "In-play",
      format: (v) => String(v),
    },
    {
      key: "ks",
      label: "K's",
      format: (v) => String(v),
    },
    {
      key: "swings",
      label: "Swings",
      format: (v) => String(v),
    },
  ];
}

export function AgreementByInningTable({
  rows,
  caption,
}: AgreementByInningTableProps) {
  const tableRows: StatTableRow[] = rows.map((r) => ({
    label: `Inning ${r.inning}`,
    values: {
      pitches: r.pitches,
      agreed: r.agreed,
      inPlay: r.inPlay,
      ks: r.ks,
      swings: r.swings,
    },
  }));
  return <StatTable columns={columns()} rows={tableRows} caption={caption} />;
}
