/**
 * <RecentPredictionsTable> — recent matchup-context predictions, broadcast-chrome
 * styled.
 *
 * Visual: Mantine <Table> but skinned to match the scouting-report identity
 * (navy header, silver first column, IBM Plex Mono data cells, 1-px
 * bgEmphasis borders). Extending <StatTable> was rejected because the agreed
 * column is non-numeric and needs the ✓ / ✗ glyph + <abbr> for accessibility.
 *
 * The agreed column carries meaning three ways (a11y §8 — color is never the
 * sole carrier):
 *   1. Glyph (✓ / ✗)
 *   2. <abbr title="…"> tooltip on hover / focus
 *   3. Color (scarlet on ✗, textStrong on ✓)
 */

import { Table } from "@mantine/core";

import type { MatchupPrediction } from "../../data/matchup-fixtures";
import { radii, colors, typography } from "../../design/tokens";

export type RecentPredictionsTableProps = {
  rows: MatchupPrediction[];
  /** Optional caption (rendered above the table, visible). */
  caption?: string;
};

function formatTs(iso: string): string {
  // "2026-05-22T19:14" → "May 22 · 7:14 PM"
  const match = iso.match(/^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})/);
  if (!match) return iso;
  const [, , mm, dd, hh, mi] = match;
  const monthNames = [
    "Jan",
    "Feb",
    "Mar",
    "Apr",
    "May",
    "Jun",
    "Jul",
    "Aug",
    "Sep",
    "Oct",
    "Nov",
    "Dec",
  ];
  const month = monthNames[parseInt(mm, 10) - 1] ?? mm;
  const h = parseInt(hh, 10);
  const ampm = h >= 12 ? "PM" : "AM";
  const h12 = h % 12 === 0 ? 12 : h % 12;
  return `${month} ${parseInt(dd, 10)} · ${h12}:${mi} ${ampm}`;
}

const headerCellStyle: React.CSSProperties = {
  backgroundColor: colors.navy,
  color: colors.textOnNavy,
  fontFamily: typography.fonts.display,
  fontSize: 13,
  fontWeight: typography.weights.bold,
  textTransform: "uppercase",
  letterSpacing: "0.04em",
  padding: "8px 12px",
  borderRight: `1px solid ${colors.bgEmphasis}`,
  whiteSpace: "nowrap",
  textAlign: "left",
};

const dataCellStyle: React.CSSProperties = {
  fontFamily: typography.fonts.mono,
  fontSize: 13,
  color: colors.textStrong,
  padding: "6px 12px",
  borderBottom: `1px solid ${colors.bgEmphasis}`,
  borderRight: `1px solid ${colors.bgEmphasis}`,
  fontFeatureSettings: '"tnum" 1',
  verticalAlign: "middle",
};

export function RecentPredictionsTable({
  rows,
  caption,
}: RecentPredictionsTableProps) {
  return (
    <div
      style={{
        backgroundColor: colors.bgSheet,
        border: `1px solid ${colors.bgEmphasis}`,
        borderRadius: radii.sm,
        overflowX: "auto",
      }}
    >
      <Table
        verticalSpacing={0}
        horizontalSpacing={0}
        style={{
          borderCollapse: "collapse",
          width: "100%",
          backgroundColor: colors.bgSheet,
        }}
      >
        {caption ? (
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
        ) : null}
        <Table.Thead>
          <Table.Tr>
            <Table.Th style={headerCellStyle}>When</Table.Th>
            <Table.Th style={headerCellStyle}>Predicted</Table.Th>
            <Table.Th style={{ ...headerCellStyle, textAlign: "right" }}>
              p
            </Table.Th>
            <Table.Th style={headerCellStyle}>Actual</Table.Th>
            <Table.Th
              style={{
                ...headerCellStyle,
                textAlign: "center",
                borderRight: "none",
              }}
            >
              Agreed
            </Table.Th>
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {rows.length === 0 ? (
            <Table.Tr>
              <Table.Td
                colSpan={5}
                style={{
                  ...dataCellStyle,
                  textAlign: "center",
                  color: colors.textMuted,
                  fontFamily: typography.fonts.body,
                  padding: "24px 12px",
                }}
              >
                No recent predictions for this matchup.
              </Table.Td>
            </Table.Tr>
          ) : (
            rows.map((r, i) => {
              const isLast = i === rows.length - 1;
              return (
                <Table.Tr key={`${r.when}-${i}`}>
                  <Table.Td
                    style={{
                      ...dataCellStyle,
                      backgroundColor: colors.silver,
                      color: colors.textStrong,
                      fontFamily: typography.fonts.mono,
                      borderBottom: isLast
                        ? "none"
                        : `1px solid ${colors.bgEmphasis}`,
                    }}
                  >
                    {formatTs(r.when)}
                  </Table.Td>
                  <Table.Td
                    style={{
                      ...dataCellStyle,
                      fontFamily: typography.fonts.body,
                      borderBottom: isLast
                        ? "none"
                        : `1px solid ${colors.bgEmphasis}`,
                    }}
                  >
                    {r.predicted}
                  </Table.Td>
                  <Table.Td
                    style={{
                      ...dataCellStyle,
                      textAlign: "right",
                      borderBottom: isLast
                        ? "none"
                        : `1px solid ${colors.bgEmphasis}`,
                    }}
                  >
                    {r.prob.toFixed(2)}
                  </Table.Td>
                  <Table.Td
                    style={{
                      ...dataCellStyle,
                      fontFamily: typography.fonts.body,
                      borderBottom: isLast
                        ? "none"
                        : `1px solid ${colors.bgEmphasis}`,
                    }}
                  >
                    {r.actual}
                  </Table.Td>
                  <Table.Td
                    style={{
                      ...dataCellStyle,
                      textAlign: "center",
                      borderRight: "none",
                      borderBottom: isLast
                        ? "none"
                        : `1px solid ${colors.bgEmphasis}`,
                    }}
                  >
                    {r.agreed ? (
                      <abbr
                        title="Model prediction matched the observed outcome"
                        style={{
                          textDecoration: "none",
                          color: colors.textStrong,
                          fontWeight: typography.weights.bold,
                        }}
                      >
                        ✓
                      </abbr>
                    ) : (
                      <abbr
                        title="Model prediction did not match the observed outcome"
                        style={{
                          textDecoration: "none",
                          color: colors.scarlet,
                          fontWeight: typography.weights.bold,
                        }}
                      >
                        ✗
                      </abbr>
                    )}
                  </Table.Td>
                </Table.Tr>
              );
            })
          )}
        </Table.Tbody>
      </Table>
    </div>
  );
}
