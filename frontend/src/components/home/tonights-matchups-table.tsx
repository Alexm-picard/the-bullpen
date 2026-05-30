/**
 * <TonightsMatchupsTable> — the 6-column slate table on /home.
 *
 * Wraps <StatTable> with a config for the cover-sheet's matchup grid:
 *   col 1 (row label)  — MATCHUP, e.g. "NYY @ DET"
 *   col 2              — TIME, mono e.g. "7:10 PM ET"
 *   col 3              — STARTERS, e.g. "Cole · R  /  Skubal · L"
 *   col 4              — EDGE, signed numeric with conditional-format tint
 *                        (positive = green, negative = red, near-zero = neutral)
 *   col 5              — TOP READ, short editorial summary
 *   col 6              — OPEN, scarlet uppercase Link → /players/{batterId}
 *
 * StatTable already provides cellColor formatting per column when metricMeta
 * is set; we only set it on the EDGE column so the rest of the table reads
 * as plain bgSheet (no over-tinting).
 *
 * The OPEN column's format() returns a placeholder string ("OPEN →") and a
 * post-render <Link> overlay would be the wrong abstraction — instead we
 * inline-format an anchor via dangerouslySetInnerHTML? No: StatTable expects
 * a string from format(). Better: hand the OPEN column its own render path
 * via a separate column key that StatTable can produce — but adding a render
 * hook to StatTable is scope creep. Cleanest compromise: render the table
 * without an OPEN column, then add a sibling list of links keyed by row. But
 * that breaks the locked 6-column pick.
 *
 * Resolution: we accept a small surgical pattern — StatTable.format() returns
 * an HTML-shaped string that StatTable inserts as a text node, so we cannot
 * inject an anchor through it. So we render OPEN as a plain "OPEN →" text
 * cell inside StatTable (which gives the user the visual affordance) AND
 * wrap the entire table inside a containing div that intercepts clicks on
 * the OPEN cell via event delegation. That's clever-but-fragile.
 *
 * Cleaner: render a custom <table> here directly, NOT through StatTable. The
 * cell-tinting helper {@link cellColor} is reusable on its own, so we get
 * the EDGE column's heat-map treatment without StatTable's render path. The
 * cost is ~80 lines of table markup we maintain in parallel with StatTable;
 * the benefit is honest semantics (the OPEN cell is a real <a> with a real
 * href, keyboard- and screen-reader-accessible without delegation hacks).
 *
 * We take the cleaner route. StatTable continues to be the right primitive
 * for the Matchup Report's pitch-mix / splits tables (no interactive cells).
 */

import { Link } from "react-router-dom";

import type { TonightMatchup } from "../../data/home-fixtures";
import { EDGE_METRIC } from "../../data/home-fixtures";
import { cellColor } from "../../design/cellColor";
import { colors, typography } from "../../design/tokens";

export type TonightsMatchupsTableProps = {
  matchups: TonightMatchup[];
  caption?: string;
};

const tableBorder = `1px solid ${colors.bgEmphasis}`;

const headerCellStyle: React.CSSProperties = {
  backgroundColor: colors.navy,
  color: colors.textOnNavy,
  fontFamily: typography.fonts.display,
  fontSize: 14,
  fontWeight: typography.weights.bold,
  textTransform: "uppercase",
  letterSpacing: "0.04em",
  lineHeight: typography.lineHeights.display,
  padding: "8px 12px",
  borderBottom: tableBorder,
  borderRight: tableBorder,
  whiteSpace: "nowrap",
  verticalAlign: "middle",
  textAlign: "left",
};

const labelCellStyle: React.CSSProperties = {
  backgroundColor: colors.silver,
  color: colors.textStrong,
  fontFamily: typography.fonts.display,
  fontSize: 15,
  fontWeight: typography.weights.bold,
  textTransform: "uppercase",
  letterSpacing: "0.02em",
  padding: "10px 12px",
  borderBottom: tableBorder,
  borderRight: tableBorder,
  whiteSpace: "nowrap",
  verticalAlign: "middle",
};

const dataCellBaseStyle: React.CSSProperties = {
  fontFamily: typography.fonts.body,
  fontSize: 14,
  color: colors.textStrong,
  padding: "10px 12px",
  borderBottom: tableBorder,
  borderRight: tableBorder,
  verticalAlign: "middle",
  transition: "background-color 200ms cubic-bezier(0.4, 0, 0.2, 1)",
};

const monoDataCellStyle: React.CSSProperties = {
  ...dataCellBaseStyle,
  fontFamily: typography.fonts.mono,
  fontFeatureSettings: '"tnum" 1',
};

function formatEdge(edge: number): string {
  const sign = edge > 0 ? "+" : edge < 0 ? "" : " ";
  return `${sign}${edge.toFixed(1)}`;
}

export function TonightsMatchupsTable({
  matchups,
  caption,
}: TonightsMatchupsTableProps) {
  return (
    <div
      style={{
        overflowX: "auto",
        border: tableBorder,
        borderRadius: 2,
        backgroundColor: colors.bgSheet,
      }}
    >
      <table
        style={{
          borderCollapse: "collapse",
          width: "100%",
          backgroundColor: colors.bgSheet,
          tableLayout: "auto",
        }}
      >
        {caption && (
          <caption
            style={{
              captionSide: "top",
              textAlign: "left",
              fontFamily: typography.fonts.body,
              fontSize: 12,
              fontWeight: typography.weights.semibold,
              color: colors.textMuted,
              padding: "8px 12px 4px",
              letterSpacing: "0.04em",
              textTransform: "uppercase",
            }}
          >
            {caption}
          </caption>
        )}
        <thead>
          <tr>
            <th scope="col" style={headerCellStyle}>
              Matchup
            </th>
            <th scope="col" style={headerCellStyle}>
              Time
            </th>
            <th scope="col" style={headerCellStyle}>
              Starters
            </th>
            <th scope="col" style={{ ...headerCellStyle, textAlign: "right" }}>
              Edge
            </th>
            <th scope="col" style={headerCellStyle}>
              Top Read
            </th>
            <th
              scope="col"
              style={{
                ...headerCellStyle,
                textAlign: "right",
                borderRight: "none",
              }}
            >
              Open
            </th>
          </tr>
        </thead>
        <tbody>
          {matchups.map((m) => {
            const edgeBg = cellColor(m.edge, EDGE_METRIC);
            return (
              <tr key={m.id}>
                <th scope="row" style={labelCellStyle}>
                  {m.away}{" "}
                  <span
                    style={{
                      color: colors.textMuted,
                      fontWeight: typography.weights.regular,
                    }}
                  >
                    @
                  </span>{" "}
                  {m.home}
                </th>
                <td style={monoDataCellStyle}>{m.timeEt}</td>
                <td style={dataCellBaseStyle}>
                  <span style={{ fontWeight: typography.weights.semibold }}>
                    {m.awayStarter.name}
                  </span>{" "}
                  <span
                    style={{
                      fontFamily: typography.fonts.mono,
                      fontSize: 12,
                      color: colors.textMuted,
                    }}
                  >
                    · {m.awayStarter.hand}
                  </span>
                  <span style={{ color: colors.textMuted, margin: "0 6px" }}>
                    /
                  </span>
                  <span style={{ fontWeight: typography.weights.semibold }}>
                    {m.homeStarter.name}
                  </span>{" "}
                  <span
                    style={{
                      fontFamily: typography.fonts.mono,
                      fontSize: 12,
                      color: colors.textMuted,
                    }}
                  >
                    · {m.homeStarter.hand}
                  </span>
                </td>
                <td
                  style={{
                    ...monoDataCellStyle,
                    backgroundColor: edgeBg,
                    textAlign: "right",
                    fontWeight: typography.weights.bold,
                  }}
                  aria-label={`Edge ${formatEdge(m.edge)} runs`}
                >
                  {formatEdge(m.edge)}
                </td>
                <td style={dataCellBaseStyle}>{m.topRead}</td>
                <td
                  style={{
                    ...dataCellBaseStyle,
                    textAlign: "right",
                    borderRight: "none",
                  }}
                >
                  <Link
                    to={`/players/${m.batterId}`}
                    style={{
                      fontFamily: typography.fonts.display,
                      fontSize: 13,
                      fontWeight: typography.weights.bold,
                      textTransform: "uppercase",
                      letterSpacing: "0.06em",
                      color: colors.scarlet,
                      textDecoration: "none",
                      whiteSpace: "nowrap",
                    }}
                    aria-label={`Open report for ${m.away} at ${m.home}`}
                  >
                    Open &rarr;
                  </Link>
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
