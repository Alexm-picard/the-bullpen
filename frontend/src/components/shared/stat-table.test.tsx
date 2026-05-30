/**
 * Unit tests for <StatTable> — the signature scouting-report component.
 *
 * Covers:
 *   - Header row renders column labels
 *   - Row-label column renders row.label values
 *   - Data cells render formatted values
 *   - Conditionally-formatted cells get a background color from cellColor
 *   - Plain (no metricMeta) cells get bgSheet background
 *   - Empty rows renders a "No data" placeholder
 *   - Caption renders when provided, omitted when not
 */
import { MantineProvider } from "@mantine/core";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import { colors } from "../../design/tokens";
import { theme } from "../../design/theme";
import type { MetricMeta } from "../../design/cellColor";
import { StatTable } from "./stat-table";
import type { StatTableColumn, StatTableRow } from "./stat-table";

function render(ui: React.ReactElement): string {
  return renderToStaticMarkup(
    <MantineProvider theme={theme}>{ui}</MantineProvider>,
  );
}

const WHIFF_META: MetricMeta = {
  key: "whiff_rate",
  direction: "higher-is-better",
  reference: { min: 0.05, p25: 0.18, median: 0.24, p75: 0.31, max: 0.5 },
};

const COLUMNS: StatTableColumn[] = [
  { key: "pa", label: "PA" },
  {
    key: "whiff",
    label: "Whiff%",
    metricMeta: WHIFF_META,
    format: (v) => `${(Number(v) * 100).toFixed(1)}%`,
  },
];

const ROWS: StatTableRow[] = [
  { label: "Player A", values: { pa: 200, whiff: 0.3 } },
  { label: "Player B", values: { pa: 150, whiff: null } },
];

describe("StatTable", () => {
  it("renders column labels in the header row", () => {
    const html = render(<StatTable columns={COLUMNS} rows={ROWS} />);
    expect(html).toContain("PA");
    expect(html).toContain("Whiff%");
  });

  it("renders row labels in the first column", () => {
    const html = render(<StatTable columns={COLUMNS} rows={ROWS} />);
    expect(html).toContain("Player A");
    expect(html).toContain("Player B");
  });

  it("renders formatted cell values using the format function", () => {
    const html = render(<StatTable columns={COLUMNS} rows={ROWS} />);
    // whiff 0.30 formatted as "30.0%"
    expect(html).toContain("30.0%");
  });

  it("renders em-dash for null values", () => {
    const html = render(<StatTable columns={COLUMNS} rows={ROWS} />);
    // Player B has null whiff → should render —
    expect(html).toContain("—");
  });

  it("applies a condFormat color to conditionally-formatted cells", () => {
    const html = render(<StatTable columns={COLUMNS} rows={ROWS} />);
    // condFormat colors should appear as background-color in the cell style
    const hasCondColor = Object.values(colors.condFormat).some((hex) =>
      html.toLowerCase().includes(hex.toLowerCase()),
    );
    expect(hasCondColor).toBe(true);
  });

  it("applies bgSheet background to plain (no metricMeta) cells", () => {
    const html = render(<StatTable columns={COLUMNS} rows={ROWS} />);
    // bgSheet color should appear for the plain "PA" column cells
    expect(html.toLowerCase()).toContain(colors.bgSheet.toLowerCase());
  });

  it("renders a caption when provided", () => {
    const html = render(
      <StatTable columns={COLUMNS} rows={ROWS} caption="Test caption" />,
    );
    expect(html).toContain("Test caption");
  });

  it("omits the caption element when not provided", () => {
    const html = render(<StatTable columns={COLUMNS} rows={ROWS} />);
    expect(html).not.toContain("Test caption");
  });

  it("renders No data placeholder when rows array is empty", () => {
    const html = render(<StatTable columns={COLUMNS} rows={[]} />);
    expect(html).toContain("No data");
  });

  it("renders the navy header background", () => {
    const html = render(<StatTable columns={COLUMNS} rows={ROWS} />);
    expect(html.toLowerCase()).toContain(colors.navy.toLowerCase());
  });

  it("renders the silver row-label column background", () => {
    const html = render(<StatTable columns={COLUMNS} rows={ROWS} />);
    expect(html.toLowerCase()).toContain(colors.silver.toLowerCase());
  });

  it("includes aria-sort attribute on sortable column headers", () => {
    const html = render(<StatTable columns={COLUMNS} rows={ROWS} />);
    expect(html).toContain("aria-sort");
  });
});
