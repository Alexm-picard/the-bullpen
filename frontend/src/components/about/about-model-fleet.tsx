/**
 * <AboutModelFleet> — the two-paragraph fleet write-up plus the 4-row fleet
 * table on /about.
 *
 * Same visual chrome as <AboutStackTable>: navy header, silver row-label,
 * mono body cells, no conditional-format ramps. The STATE column uses a
 * dimmed treatment: LIVE renders bold on bgEmphasis; SHADOW renders muted
 * on bgSubtle. No aggressive tinting — colophon table, not a live ops grid.
 *
 * Prose paragraphs are wrapped in <AboutOpeningPitch>-style 62ch column
 * (about-prose class) so the editorial measure stays consistent across the
 * page.
 */

import type { FleetRow, FleetRowState } from "../../data/about-fixtures";
import { radii, colors, typography } from "../../design/broadcast";

export type AboutModelFleetProps = {
  paragraphs: string[];
  rows: FleetRow[];
};

function stateCellStyle(state: FleetRowState): React.CSSProperties {
  // Dimmed treatment — no aggressive tinting. LIVE reads as bgEmphasis with
  // strong text; SHADOW reads as bgSubtle with textMuted. Both row backgrounds
  // are warm-cream variants from the printed-sheet palette.
  if (state === "LIVE") {
    return {
      backgroundColor: colors.rule,
      color: colors.ink,
      fontWeight: typography.weights.bold,
    };
  }
  return {
    backgroundColor: colors.fieldSubtle,
    color: colors.textMuted,
    fontWeight: typography.weights.semibold,
  };
}

export function AboutModelFleet({ paragraphs, rows }: AboutModelFleetProps) {
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

  const monoCellStyle: React.CSSProperties = {
    fontFamily: typography.fonts.mono,
    fontSize: 14,
    color: colors.ink,
    padding: "7px 12px",
    borderBottom: border,
    borderRight: border,
    verticalAlign: "middle",
    backgroundColor: colors.panel,
    whiteSpace: "nowrap",
  };

  const backboneCellStyle: React.CSSProperties = {
    fontFamily: typography.fonts.body,
    fontSize: 14,
    color: colors.text,
    padding: "7px 12px",
    borderBottom: border,
    borderRight: border,
    verticalAlign: "middle",
    backgroundColor: colors.panel,
  };

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 16 }}>
      <div
        className="about-prose"
        style={{
          display: "flex",
          flexDirection: "column",
          gap: 14,
          fontFamily: typography.fonts.body,
          fontSize: typography.scale[2], // 16
          lineHeight: 1.55,
          color: colors.text,
        }}
      >
        {paragraphs.map((p, i) => (
          <p key={i} style={{ margin: 0 }}>
            {p}
          </p>
        ))}
      </div>

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
              <th style={{ ...headerCellStyle, minWidth: 200 }}>Model</th>
              <th style={headerCellStyle}>Version</th>
              <th style={headerCellStyle}>State</th>
              <th style={{ ...headerCellStyle, width: "100%" }}>Backbone</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((row, idx) => {
              const stateStyle = stateCellStyle(row.state);
              return (
                <tr key={`${row.model}-${row.version}-${idx}`}>
                  <td
                    style={{
                      ...monoCellStyle,
                      fontFamily: typography.fonts.mono,
                    }}
                  >
                    {row.model}
                  </td>
                  <td style={monoCellStyle}>{row.version}</td>
                  <td
                    style={{
                      ...monoCellStyle,
                      backgroundColor: stateStyle.backgroundColor,
                      color: stateStyle.color,
                      fontWeight: stateStyle.fontWeight,
                      textAlign: "center",
                      letterSpacing: "0.06em",
                    }}
                  >
                    {row.state}
                  </td>
                  <td style={backboneCellStyle}>{row.backbone}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </div>
  );
}
