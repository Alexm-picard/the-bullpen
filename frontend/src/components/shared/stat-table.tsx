/**
 * <StatTable> — the signature component of the scouting-report identity.
 *
 * A conditionally-formatted stat sheet built on a Mantine <Table> substrate
 * with custom broadcast-chrome styling per design.md §7 + §8.
 *
 * Layout:
 *   - Header row: navy background, cream text, Saira Condensed uppercase 14px
 *     tracked +0.04em, weight 700. Click to sort.
 *   - First column (row labels): silver background, IBM Plex Sans semibold 14px.
 *   - Data cells: bgSheet by default; conditionally-formatted cells get the
 *     condFormat ramp color as a background tint via cellColor(). The ramp colors
 *     are already light (good1/bad1 are pale; good3/bad3 are saturated but the
 *     value text is high-contrast mono on top — a11y rule: color is never the
 *     sole carrier).
 *   - Values: IBM Plex Mono tabular-nums 14px, textStrong.
 *   - 1-px borders in bgEmphasis — no shadows.
 *
 * Accessibility: every table has a caption via <caption> (visible or sr-only).
 * Sort controls use aria-sort on column headers. The sort indicator is an SVG
 * with aria-hidden.
 *
 * @module
 */

import { useState } from "react";

import type { MetricMeta } from "../../design/cellColor";
import { cellColor } from "../../design/cellColor";
import { colors, typography } from "../../design/tokens";

// ── Types ────────────────────────────────────────────────────────────────────

export type StatTableColumn = {
  /** Unique key — must match keys in StatTableRow.values. */
  key: string;
  /** Display label in the header row. */
  label: string;
  /**
   * Optional metric meta for conditional formatting. If absent, the cell
   * renders with bgSheet and plain textDefault (no heat-map fill).
   */
  metricMeta?: MetricMeta;
  /**
   * Optional custom renderer for the display value. Receives the raw value
   * and returns a formatted string (e.g., ".342", "127+").
   * If absent, the raw value is cast to string.
   */
  format?: (value: unknown) => string;
};

export type StatTableRow = {
  /** Row label rendered in the first (silver) column. */
  label: string;
  /** Values keyed by column.key. null renders as em-dash. */
  values: Record<string, number | string | null>;
  /**
   * Optional DOM id applied to the <tr> element. Enables external
   * scroll-jump anchors (e.g. switcher → row.scrollIntoView()).
   */
  id?: string;
};

export type StatTableProps = {
  columns: StatTableColumn[];
  rows: StatTableRow[];
  /**
   * Optional caption displayed above the table.
   * Rendered as a visible <caption> element for accessibility.
   * Omit for decorative tables that are already labelled by surrounding context.
   */
  caption?: string;
};

type SortState = { key: string; dir: "asc" | "desc" } | null;

// ── Sort icon ────────────────────────────────────────────────────────────────

function SortIcon({ active, dir }: { active: boolean; dir: "asc" | "desc" }) {
  const activeColor = colors.textOnNavy;
  const inactiveColor = "rgba(247, 244, 236, 0.4)"; // textOnNavy at 40% opacity
  return (
    <svg
      role="presentation"
      aria-label=""
      aria-hidden="true"
      width="10"
      height="10"
      viewBox="0 0 10 10"
      style={{ marginLeft: 4, flexShrink: 0 }}
    >
      {/* Up chevron */}
      <path
        d="M5 2 L8 6 L2 6 Z"
        fill={active && dir === "asc" ? activeColor : inactiveColor}
      />
      {/* Down chevron */}
      <path
        d="M5 8 L8 4 L2 4 Z"
        fill={active && dir === "desc" ? activeColor : inactiveColor}
      />
    </svg>
  );
}

// ── Main component ───────────────────────────────────────────────────────────

export function StatTable({ columns, rows, caption }: StatTableProps) {
  const [sort, setSort] = useState<SortState>(null);

  function handleHeaderClick(key: string) {
    setSort((prev) => {
      if (prev?.key === key) {
        // Toggle direction on second click; clear on third.
        if (prev.dir === "desc") return { key, dir: "asc" };
        return null;
      }
      return { key, dir: "desc" };
    });
  }

  // Sort rows by the active column. String values sort lexicographically.
  const sortedRows = sort
    ? [...rows].sort((a, b) => {
        const va = a.values[sort.key];
        const vb = b.values[sort.key];
        if (va === null && vb === null) return 0;
        if (va === null) return 1;
        if (vb === null) return -1;
        const numA = typeof va === "number" ? va : parseFloat(String(va));
        const numB = typeof vb === "number" ? vb : parseFloat(String(vb));
        const isNumeric = !isNaN(numA) && !isNaN(numB);
        let cmp: number;
        if (isNumeric) {
          cmp = numA - numB;
        } else {
          cmp = String(va).localeCompare(String(vb));
        }
        return sort.dir === "desc" ? -cmp : cmp;
      })
    : rows;

  // ── Styles (inline, token-sourced) ────────────────────────────────────────

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
    cursor: "pointer",
    userSelect: "none",
    whiteSpace: "nowrap",
    verticalAlign: "middle",
  };

  const labelCellStyle: React.CSSProperties = {
    backgroundColor: colors.silver,
    color: colors.textStrong,
    fontFamily: typography.fonts.body,
    fontSize: 14,
    fontWeight: typography.weights.semibold,
    padding: "7px 12px",
    borderBottom: tableBorder,
    borderRight: tableBorder,
    whiteSpace: "nowrap",
    verticalAlign: "middle",
  };

  const dataCellBaseStyle: React.CSSProperties = {
    fontFamily: typography.fonts.mono,
    fontSize: 14,
    color: colors.textStrong,
    padding: "7px 12px",
    borderBottom: tableBorder,
    borderRight: tableBorder,
    verticalAlign: "middle",
    // Smooth cell-color transitions for live data updates (§8 motion rule).
    transition: "background-color 200ms cubic-bezier(0.4, 0, 0.2, 1)",
    fontFeatureSettings: '"tnum" 1',
    tabularNums: true,
  } as React.CSSProperties;

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
            {/* Empty header cell above the row-label column */}
            <th
              style={{ ...headerCellStyle, cursor: "default", minWidth: 120 }}
            />
            {columns.map((col) => {
              const isActive = sort?.key === col.key;
              const ariaSortValue: React.AriaAttributes["aria-sort"] = isActive
                ? sort!.dir === "asc"
                  ? "ascending"
                  : "descending"
                : "none";
              return (
                <th
                  key={col.key}
                  aria-sort={ariaSortValue}
                  onClick={() => handleHeaderClick(col.key)}
                  onKeyDown={(e) => {
                    if (e.key === "Enter" || e.key === " ") {
                      e.preventDefault();
                      handleHeaderClick(col.key);
                    }
                  }}
                  tabIndex={0}
                  role="columnheader"
                  style={{
                    ...headerCellStyle,
                    outline: "none",
                  }}
                >
                  <span
                    style={{ display: "inline-flex", alignItems: "center" }}
                  >
                    {col.label}
                    <SortIcon active={isActive} dir={sort?.dir ?? "desc"} />
                  </span>
                </th>
              );
            })}
          </tr>
        </thead>
        <tbody>
          {sortedRows.map((row, rowIdx) => (
            <tr key={row.label + rowIdx} id={row.id}>
              {/* Row-label cell: silver column */}
              <td style={labelCellStyle}>{row.label}</td>
              {columns.map((col) => {
                const rawValue = row.values[col.key] ?? null;
                const displayValue =
                  rawValue === null
                    ? "—"
                    : col.format
                      ? col.format(rawValue)
                      : String(rawValue);

                // Only apply heat-map fill when metricMeta is provided
                // and the raw value is numeric.
                let cellBg: string = colors.bgSheet;
                if (col.metricMeta !== undefined) {
                  const numericValue =
                    rawValue === null
                      ? null
                      : typeof rawValue === "number"
                        ? rawValue
                        : parseFloat(String(rawValue));
                  const coerced =
                    numericValue !== null && isNaN(numericValue)
                      ? null
                      : numericValue;
                  cellBg = cellColor(coerced, col.metricMeta);
                }

                return (
                  <td
                    key={col.key}
                    style={{
                      ...dataCellBaseStyle,
                      backgroundColor: cellBg,
                      textAlign: "right",
                    }}
                  >
                    {displayValue}
                  </td>
                );
              })}
            </tr>
          ))}
          {sortedRows.length === 0 && (
            <tr>
              <td
                colSpan={columns.length + 1}
                style={{
                  ...dataCellBaseStyle,
                  textAlign: "center",
                  color: colors.textMuted,
                  fontFamily: typography.fonts.body,
                  padding: "24px 12px",
                }}
              >
                No data
              </td>
            </tr>
          )}
        </tbody>
      </table>
    </div>
  );
}
