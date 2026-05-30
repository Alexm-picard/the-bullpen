/**
 * <OpsLogTable> — recent ops events rendered as a structured table (locked
 * pick O2). NOT a StatTable wrapper — the TYPE column conditionally renders
 * in scarlet for ALERT-class events, which means the cell needs JSX inside
 * (not just a formatted string), and StatTable.format() returns string only.
 *
 * Same precedent as <TonightsMatchupsTable> on /home: when a row needs an
 * interactive element or a colored span, we hand-roll the table using the
 * StatTable chrome tokens (navy header, silver row-label, bgEmphasis borders,
 * IBM Plex Mono cells) so the visual rhythm matches but the semantics stay
 * honest.
 *
 * Columns:
 *   row-label (silver) — TIMESTAMP, mono uppercase 12px
 *   col 1 (TYPE)       — Saira heavy 13px, scarlet for ALERT/DRIFT-ALERT
 *   col 2 (DETAIL)     — body 13px, free text
 */

import type { OpsLogEntry, OpsLogType } from "../../data/ops-fixtures";
import { colors, typography } from "../../design/tokens";

export type OpsLogTableProps = {
  entries: OpsLogEntry[];
  caption?: string;
};

function isAlertType(type: OpsLogType): boolean {
  return type === "ALERT";
}

function typeColor(type: OpsLogType): string {
  return isAlertType(type) ? colors.scarlet : colors.textStrong;
}

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
  fontFamily: typography.fonts.mono,
  fontSize: 12,
  fontWeight: typography.weights.semibold,
  letterSpacing: "0.02em",
  textTransform: "uppercase",
  padding: "8px 12px",
  borderBottom: tableBorder,
  borderRight: tableBorder,
  whiteSpace: "nowrap",
  verticalAlign: "middle",
};

const dataCellBaseStyle: React.CSSProperties = {
  fontFamily: typography.fonts.body,
  fontSize: 13,
  color: colors.textStrong,
  padding: "8px 12px",
  borderBottom: tableBorder,
  borderRight: tableBorder,
  verticalAlign: "middle",
};

export function OpsLogTable({ entries, caption }: OpsLogTableProps) {
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
              Timestamp
            </th>
            <th scope="col" style={headerCellStyle}>
              Type
            </th>
            <th scope="col" style={{ ...headerCellStyle, borderRight: "none" }}>
              Detail
            </th>
          </tr>
        </thead>
        <tbody>
          {entries.map((entry) => (
            <tr key={entry.id}>
              <th scope="row" style={labelCellStyle}>
                {entry.timestamp}
              </th>
              <td
                style={{
                  ...dataCellBaseStyle,
                  fontFamily: typography.fonts.display,
                  fontSize: 13,
                  fontWeight: typography.weights.heavy,
                  textTransform: "uppercase",
                  letterSpacing: "0.06em",
                  color: typeColor(entry.type),
                  whiteSpace: "nowrap",
                }}
              >
                {entry.type}
              </td>
              <td
                style={{
                  ...dataCellBaseStyle,
                  borderRight: "none",
                }}
              >
                {entry.detail}
              </td>
            </tr>
          ))}
          {entries.length === 0 && (
            <tr>
              <td
                colSpan={3}
                style={{
                  ...dataCellBaseStyle,
                  textAlign: "center",
                  color: colors.textMuted,
                  padding: "24px 12px",
                  borderRight: "none",
                }}
              >
                No events in window
              </td>
            </tr>
          )}
        </tbody>
      </table>
    </div>
  );
}
