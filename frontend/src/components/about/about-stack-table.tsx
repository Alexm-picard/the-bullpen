/**
 * <AboutStackTable> — the 10-row LAYER / CHOICE / WHY table.
 *
 * Lifts the visual chrome of <StatTable> (navy header, silver row-label,
 * bgEmphasis borders, mono body cells) but renders an explicit 3-column
 * layout with a real LAYER header rather than the implicit empty header
 * cell StatTable uses above its row-label column. This keeps the colophon
 * table reading as 3 named columns without modifying the shared primitive.
 *
 * No conditional-format ramps; the data is editorial, not metric. No sort —
 * row order is editorial too (most-foundational to most-peripheral).
 */

import type { StackRow } from "../../data/about-fixtures";
import { radii, colors, typography } from "../../design/broadcast";

export type AboutStackTableProps = {
  rows: StackRow[];
};

export function AboutStackTable({ rows }: AboutStackTableProps) {
  const border = `1px solid ${colors.rule}`;

  const headerCellStyle: React.CSSProperties = {
    backgroundColor: colors.chrome,
    color: colors.textOnChrome,
    fontFamily: typography.fonts.display,
    fontSize: 14,
    fontWeight: typography.weights.bold,
    textTransform: "uppercase",
    letterSpacing: "0.04em",
    lineHeight: typography.lineHeights.display,
    padding: "8px 12px",
    borderBottom: border,
    borderRight: border,
    textAlign: "left",
    whiteSpace: "nowrap",
    verticalAlign: "middle",
  };

  const labelCellStyle: React.CSSProperties = {
    backgroundColor: colors.fieldSubtle,
    color: colors.ink,
    fontFamily: typography.fonts.body,
    fontSize: 14,
    fontWeight: typography.weights.semibold,
    padding: "7px 12px",
    borderBottom: border,
    borderRight: border,
    whiteSpace: "nowrap",
    verticalAlign: "middle",
    textTransform: "uppercase",
    letterSpacing: "0.04em",
  };

  const choiceCellStyle: React.CSSProperties = {
    fontFamily: typography.fonts.display,
    fontSize: 15,
    fontWeight: typography.weights.semibold,
    color: colors.ink,
    padding: "7px 12px",
    borderBottom: border,
    borderRight: border,
    verticalAlign: "middle",
    backgroundColor: colors.panel,
    whiteSpace: "nowrap",
  };

  const whyCellStyle: React.CSSProperties = {
    fontFamily: typography.fonts.body,
    fontSize: 14,
    color: colors.text,
    padding: "7px 12px",
    borderBottom: border,
    borderRight: border,
    verticalAlign: "middle",
    backgroundColor: colors.panel,
    lineHeight: 1.45,
  };

  return (
    <div
      style={{
        overflowX: "auto",
        border,
        borderRadius: radii.sm,
        backgroundColor: colors.panel,
      }}
    >
      <table
        style={{
          borderCollapse: "collapse",
          width: "100%",
          backgroundColor: colors.panel,
          tableLayout: "auto",
        }}
      >
        <thead>
          <tr>
            <th style={{ ...headerCellStyle, minWidth: 140 }}>Layer</th>
            <th style={headerCellStyle}>Choice</th>
            <th style={{ ...headerCellStyle, width: "100%" }}>Why</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row, idx) => (
            <tr key={`${row.layer}-${idx}`}>
              <td style={labelCellStyle}>{row.layer}</td>
              <td style={choiceCellStyle}>{row.choice}</td>
              <td style={whyCellStyle}>{row.why}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
